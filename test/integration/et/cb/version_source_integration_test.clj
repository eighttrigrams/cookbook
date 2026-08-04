(ns et.cb.version-source-integration-test
  "Per-version provenance over HTTP: which token labels a version `ui` and which
  labels it `machine`, what `/versions` carries, and the split the listing serves
  the card.

  The db layer takes `:human?` as a fact it is handed and the unit namespace covers
  what it does with it. What is only testable here is who gets to hand it over —
  the label comes from the token's `:machine?` claim and from nothing else — so
  these cases are written with a real machine token rather than by passing a flag
  in, exactly like the `has_human_edit` namespace next door.

  Every case reads the label back through the API rather than off the row: the
  point of part 1 is the shape part 2 will consume."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

;; ---------------------------------------------------------------------------
;; the two callers, as `human_edit_integration_test` has them: the owner is dev
;; skip-logins (no token, the x-user-id header) and the machine is a real machine
;; token, minted the way `login-handler` mints one.

(defn- machine [method path & [body]]
  (h/API method path (cond-> {:token (h/machine-token-for h/*user-id*)}
                       body (assoc :body body))))

(defn- create-as-human! [title]
  (:body (POST-json "/api/recipes" {:title title :useful_when (str "when " title)
                                    :description (str "body of " title)})))

(defn- create-as-machine! [title]
  (:body (machine :post "/api/recipes" {:title title :useful_when (str "when " title)
                                        :description (str "body of " title)})))

(defn- versions-of [id]
  (:versions (:body (GET-json (str "/api/recipes/" id "/versions")))))

(defn- sources-by-version [id]
  (into {} (map (juxt :version :source) (versions-of id))))

(defn- listed [id]
  (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes")))))

(defn- split-of [id]
  (select-keys (listed id) [:version :machine_versions :ui_versions :unrecorded_versions]))

;; ---------------------------------------------------------------------------
;; which token labels a version how

(deftest the-label-comes-from-the-token
  (testing "a create without a machine token is the owner's hand"
    (is (= "ui" (:source (create-as-human! "Written by hand")))))
  (testing "and one with a machine token is an agent's"
    (is (= "machine" (:source (create-as-machine! "Written by an agent"))))))

(deftest a-machine-edit-does-not-relabel-what-the-owner-wrote
  ;; The bug the design invites, over HTTP: the version the agent displaced must
  ;; keep its own label. Backwards, an unsupervised writer would rewrite the record
  ;; of who wrote everything before it.
  (let [{:keys [id]} (create-as-human! "The owner's")]
    (is (= 200 (:status (machine :put (str "/api/recipes/" id) {:description "the agent's body"}))))
    (is (= {1 "ui" 2 "machine"} (sources-by-version id)))
    (testing "and the owner saving again leaves the agent's version labelled as the
              agent's"
      (PUT-json (str "/api/recipes/" id) {:description "the owner's body again"})
      (is (= {1 "ui" 2 "machine" 3 "ui"} (sources-by-version id))))))

(deftest the-label-cannot-be-carried-in-the-body
  (testing "POST cannot claim it — like `published` and like the mark, it comes
            from the token"
    (is (= "machine" (:source (:body (machine :post "/api/recipes"
                                              {:title "Claiming to be a human"
                                               :source "ui"}))))))
  (let [{:keys [id]} (create-as-machine! "Still an agent's")]
    (testing "nor can PUT"
      (machine :put (str "/api/recipes/" id) {:source "ui" :description "x"})
      (is (= {1 "machine" 2 "machine"} (sources-by-version id))))))

(deftest publishing-writes-no-version-and-no-label
  (let [{:keys [id]} (create-as-machine! "Signed but not written")]
    (is (= 200 (:status (h/API :post (str "/api/recipes/" id "/publish") {}))))
    (is (= 1 (:published (listed id))))
    (testing "the owner put his name to the agent's text; he did not write it"
      (is (= "machine" (:source (listed id))))
      (is (= {1 "machine"} (sources-by-version id)))
      (is (= {:version 1 :machine_versions 1 :ui_versions 0 :unrecorded_versions 0}
             (split-of id))))))

(deftest a-save-that-changes-nothing-labels-nothing
  (let [{:keys [id description]} (create-as-machine! "Unchanged")]
    (testing "the owner re-saving the same text is a no-op — no version, so no
              label either, even though this caller is the human"
      (let [resp (:body (PUT-json (str "/api/recipes/" id) {:description description}))]
        (is (= 1 (:version resp)))
        (is (= "machine" (:source resp)))
        (is (= {1 "machine"} (sources-by-version id)))))
    (testing "changing something does label it"
      (is (= "ui" (:source (:body (PUT-json (str "/api/recipes/" id)
                                            {:description "actually different"}))))))))

;; ---------------------------------------------------------------------------
;; /versions — the shape part 2 reads

(deftest every-version-entry-carries-a-source
  (let [{:keys [id]} (create-as-machine! "Much revised")]
    (PUT-json (str "/api/recipes/" id) {:description "the owner's body"})
    (is (= 200 (:status (machine :put (str "/api/recipes/" id) {:description "and the agent's"}))))
    (let [versions (versions-of id)]
      (testing "newest first, the current row flagged, one uniform list"
        (is (= [3 2 1] (map :version versions)))
        (is (true? (:current (first versions))))
        (is (every? nil? (map :current (rest versions)))))
      (testing "the current entry's label comes off the row, the others off their
                own history rows"
        (is (= ["machine" "ui" "machine"] (map :source versions))))
      (testing "and the key is on every entry, which is what part 2 relies on"
        (is (every? #(contains? % :source) versions))))))

(deftest an-unrecorded-version-reads-as-null-and-keeps-its-key
  ;; A Recipe from before the column: written straight into the table, then saved
  ;; through the API, so the ladder holds one unlabelled version and one labelled.
  (let [{:keys [id]} (create-as-machine! "Older than the column")]
    (h/clear-source! id)
    (PUT-json (str "/api/recipes/" id) {:description "saved since"})
    (let [versions (versions-of id)]
      (is (= {2 "ui" 1 nil} (sources-by-version id)))
      (testing "the null entry still has the key — a reader must be able to tell
                'not recorded' from 'no such field'"
        (is (contains? (last versions) :source))
        (is (nil? (:source (last versions)))))
      (testing "and the card counts it in the third bucket rather than rounding it
                to the machine one"
        (is (= {:version 2 :machine_versions 0 :ui_versions 1 :unrecorded_versions 1}
               (split-of id)))))))

;; ---------------------------------------------------------------------------
;; the split on the listing

(deftest the-listing-carries-the-split-because-the-card-is-collapsed
  (let [{:keys [id]} (create-as-machine! "Much revised")]
    (PUT-json (str "/api/recipes/" id) {:description "v2 by the owner"})
    (machine :put (str "/api/recipes/" id) {:description "v3 by the agent"})
    (PUT-json (str "/api/recipes/" id) {:description "v4 by the owner"})
    (testing "the badge's numbers are on the lean listing row itself"
      (is (= {:version 4 :machine_versions 2 :ui_versions 2 :unrecorded_versions 0}
             (split-of id))))
    (testing "and the lean listing is still lean — the join reaches a table with a
              `description` of its own, and no body may come back through it"
      (is (every? #(false? (contains? % :description)) (:body (GET-json "/api/recipes")))))
    (testing "the three sum to the version, which is the invariant behind the badge"
      (let [{:keys [version machine_versions ui_versions unrecorded_versions]} (split-of id)]
        (is (= version (+ machine_versions ui_versions unrecorded_versions)))))
    (testing "?detail=full adds the recipe's own body and nothing from history"
      (is (= "v4 by the owner"
             (:description (first (filter #(= id (:id %))
                                          (:body (GET-json "/api/recipes?detail=full"))))))))))

(deftest the-split-survives-the-narrowings
  ;; The counts are aggregated in the same query the search and the human filter
  ;; put their clauses on, so this is where a join that broke one of them shows up.
  (let [{sourdough :id} (create-as-human! "Sourdough starter")
        {agents :id} (create-as-machine! "Sourdough by an agent")]
    (PUT-json (str "/api/recipes/" sourdough) {:description "second thoughts"})
    (let [rows (fn [query] (into {} (map (juxt :id identity)
                                         (:body (GET-json (str "/api/recipes" query))))))]
      (testing "a search still narrows, and the row it returns still has its counts"
        (let [found (rows "?search=sourdough+star")]
          (is (= #{sourdough} (set (keys found))))
          (is (= 2 (:ui_versions (get found sourdough))))))
      (testing "so does ?human=true — and the agent's Recipe is absent rather than
                present with an empty split"
        (let [found (rows "?human=true")]
          (is (= #{sourdough} (set (keys found))))
          (is (= 0 (:machine_versions (get found sourdough))))))
      (testing "unfiltered, both rows carry their own split"
        (let [found (rows "")]
          (is (= 2 (:ui_versions (get found sourdough))))
          (is (= 1 (:machine_versions (get found agents))))
          (is (= 0 (:ui_versions (get found agents)))))))))

;; ---------------------------------------------------------------------------
;; documented where a caller will read it

(deftest the-shape-is-documented-in-describe
  (let [doc-for (fn [method path]
                  (:doc (first (filter #(and (= path (:path %)) (= method (:method %)))
                                       (:body (GET-json "/api/describe"))))))]
    (testing "the version list says what `source` is, including that a null in it
              means never-recorded rather than withheld"
      (let [doc (doc-for "GET" "/api/recipes/:id/versions")]
        (is (re-find #"source" doc))
        (is (re-find #"(?i)machine" doc))
        (is (re-find #"(?i)null" doc))
        (is (re-find #"(?i)not recorded|never recorded|before cookbook recorded" doc))))
    (testing "and the listing says the counts are there and that they sum to the
              version"
      (let [doc (doc-for "GET" "/api/recipes")]
        (is (re-find #"machine_versions" doc))
        (is (re-find #"ui_versions" doc))
        (is (re-find #"unrecorded_versions" doc))
        (is (re-find #"(?i)sum to `?version" doc))))))

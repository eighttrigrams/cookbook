(ns et.cb.human-edit-integration-test
  "Provenance over HTTP: which writes mark a Recipe as human-edited, and what
  `?human=true` narrows.

  The db layer takes `:human?` as a fact it is handed — the unit namespace covers
  what it does with it. What is only testable here is who gets to hand it over:
  the mark comes from the token's `:machine?` claim and from nothing else, so
  these cases are written with a real machine token rather than by passing the
  flag in.

  Every case reads the row through the API's own `has_human_edit`, and the ones
  about refusals read the listing as well: a mark that was set but never narrowed
  by, or a narrowing that filtered rows after the fact, would both pass on a
  single assertion."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

;; ---------------------------------------------------------------------------
;; the two callers
;;
;; The owner is dev skip-logins (no token, the x-user-id header) and the machine
;; is a real machine token, minted the way `login-handler` mints one. Nothing
;; here passes `:human?` by hand; that is the point.

(defn- machine [method path & [body]]
  (h/API method path (cond-> {:token (h/machine-token-for h/*user-id*)}
                       body (assoc :body body))))

(defn- create-as-human! [title]
  (:body (POST-json "/api/recipes" {:title title :useful_when (str "when " title)
                                    :description (str "body of " title)})))

(defn- create-as-machine! [title]
  (:body (machine :post "/api/recipes" {:title title :useful_when (str "when " title)
                                        :description (str "body of " title)})))

(defn- mark-of [id]
  (:has_human_edit (:body (GET-json (str "/api/recipes/" id)))))

(defn- listed-with-filter []
  (set (map :id (:body (GET-json "/api/recipes?human=true")))))

;; ---------------------------------------------------------------------------
;; which writes set the mark

(deftest a-machine-create-then-a-human-edit
  (let [{:keys [id]} (create-as-machine! "Written by an agent")]
    (testing "the agent's own Recipe carries no mark, and the filter passes it by"
      (is (= 0 (:has_human_edit (:body (GET-json (str "/api/recipes/" id))))))
      (is (false? (contains? (listed-with-filter) id)))
      (is (contains? (set (map :id (:body (GET-json "/api/recipes")))) id)
          "it is on the unfiltered shelf — what the filter does is narrow"))

    (testing "the owner saving it earns the mark, on the save that bumps the version"
      (let [saved (:body (PUT-json (str "/api/recipes/" id) {:description "the owner's body"}))]
        (is (= 1 (:has_human_edit saved)))
        (is (= 2 (:version saved)))))

    (testing "and now the filter shows it"
      (is (contains? (listed-with-filter) id)))))

(deftest a-human-create-then-a-machine-edit
  (let [{:keys [id]} (create-as-human! "Written by hand")]
    (is (= 1 (mark-of id)))

    (testing "an agent may rewrite the content — cookbook is for unsupervised
              writes and this is not a gate"
      (let [resp (machine :put (str "/api/recipes/" id) {:description "the agent's body"})]
        (is (= 200 (:status resp)))
        (is (= "the agent's body"
               (:description (:body (GET-json (str "/api/recipes/" id "?detail=full"))))))
        (is (= 2 (:version (:body resp))))))

    (testing "but it cannot take the mark back off — nothing clears it"
      (is (= 1 (mark-of id)))
      (is (contains? (listed-with-filter) id)))))

(deftest publishing-does-not-make-a-recipe-human-edited
  (let [{:keys [id]} (create-as-machine! "Signed but not written")]
    (testing "the owner publishing an agent's Recipe leaves the mark at 0: he put
              his name to the text, he did not write it"
      (is (= 200 (:status (h/API :post (str "/api/recipes/" id "/publish") {}))))
      (is (= 1 (:published (:body (GET-json (str "/api/recipes/" id))))))
      (is (= 0 (mark-of id)))
      (is (false? (contains? (listed-with-filter) id))))

    (testing "and the mark is still the owner's to earn by saving"
      (PUT-json (str "/api/recipes/" id) {:description "rewritten by the owner"})
      (is (= 1 (mark-of id)))
      (is (contains? (listed-with-filter) id)))))

(deftest a-save-that-changes-nothing-earns-nothing
  (let [{:keys [id description]} (create-as-machine! "Unchanged")]
    (testing "the owner re-saving the same text is a no-op — no version, and no
              mark either"
      (let [resp (:body (PUT-json (str "/api/recipes/" id) {:description description}))]
        (is (= 1 (:version resp)))
        (is (= 0 (:has_human_edit resp)))
        (is (= 0 (mark-of id)))))
    (testing "changing something does earn it"
      (is (= 1 (:has_human_edit (:body (PUT-json (str "/api/recipes/" id)
                                                 {:description "actually different"}))))))))

(deftest the-mark-cannot-be-carried-in-the-body
  (testing "POST cannot claim it — like `published`, it comes from the token"
    (is (= 0 (:has_human_edit (:body (machine :post "/api/recipes"
                                              {:title "Claiming to be a human"
                                               :has_human_edit 1}))))))
  (let [{:keys [id]} (create-as-machine! "Still an agent's")]
    (testing "nor can PUT — and a machine PUT that changes content still does not
              set it"
      (machine :put (str "/api/recipes/" id) {:has_human_edit 1 :description "x"})
      (is (= 0 (mark-of id))))))

;; ---------------------------------------------------------------------------
;; ?human=true

(deftest only-the-value-true-narrows
  (let [{by-hand :id} (create-as-human! "By hand")
        {by-agent :id} (create-as-machine! "By an agent")
        ids (fn [query] (set (map :id (:body (GET-json (str "/api/recipes" query))))))]
    (testing "?human=true narrows"
      (is (= #{by-hand} (ids "?human=true"))))
    (testing "absent, false and garbage all leave the listing alone — the same
              reading ?detail gets"
      (is (= #{by-hand by-agent} (ids "")))
      (is (= #{by-hand by-agent} (ids "?human=false")))
      (is (= #{by-hand by-agent} (ids "?human=1")))
      (is (= #{by-hand by-agent} (ids "?human=yes")))
      (is (= #{by-hand by-agent} (ids "?human="))))))

(deftest the-filter-composes-with-the-search
  (let [{sourdough :id} (create-as-human! "Sourdough starter")
        _ (create-as-human! "Sourdough discard")
        {agents :id} (create-as-machine! "Sourdough by an agent")
        ids (fn [query] (set (map :id (:body (GET-json (str "/api/recipes" query))))))]
    (testing "both narrowings apply — neither wins"
      (is (= #{sourdough} (ids "?search=sourdough+star&human=true")))
      (is (= 3 (count (ids "?search=sourdough")))))
    (testing "the order of the params does not matter"
      (is (= #{sourdough} (ids "?human=true&search=sourdough+star"))))
    (testing "a term matching only an agent's Recipe finds nothing with the
              filter on"
      (is (empty? (ids "?search=agent&human=true")))
      (is (= #{agents} (ids "?search=agent"))))))

(deftest the-filter-narrows-inside-the-visitor-audience
  ;; The clause sits beside the audience clause in the query rather than filtering
  ;; the rows it returned, and this is the case that tells the two apart: a
  ;; visitor's filtered shelf must be the human-edited ones *among the published*,
  ;; never a human-edited draft that the latch was keeping out.
  (let [{drafted :id} (create-as-human! "Drafted by hand")
        {signed :id} (create-as-human! "Signed and by hand")
        {agents :id} (create-as-machine! "Signed, by an agent")
        anon (fn [query] (h/with-real-auth
                           (set (map :id (:body (h/API :get (str "/api/recipes" query)
                                                       {:anonymous? true}))))))]
    (doseq [id [signed agents]]
      (is (= 200 (:status (h/API :post (str "/api/recipes/" id "/publish") {})))))

    (testing "the visitor's filtered shelf is the published human-edited row alone"
      (is (= #{signed} (anon "?human=true"))))
    (testing "the human-edited draft is not in it — the filter takes rows away
              from the visitor's audience and can never add one"
      (is (false? (contains? (anon "?human=true") drafted)))
      (is (false? (contains? (anon "") drafted))))
    (testing "and the owner does see the draft under the same filter, so what the
              visitor is missing is the latch and not the mark"
      (is (= #{drafted signed} (listed-with-filter))))))

(deftest the-filter-is-documented-where-a-caller-will-read-it
  (let [doc (:doc (first (filter #(and (= "/api/recipes" (:path %)) (= "GET" (:method %)))
                                 (:body (GET-json "/api/describe")))))]
    (is (re-find #"\?human=true" doc))
    (testing "including the two things that would otherwise read as bugs: that
              only `true` narrows, and that the mark is recorded going forward"
      (is (re-find #"(?i)true" doc))
      (is (re-find #"(?i)going forward|before that|never recorded" doc)))))

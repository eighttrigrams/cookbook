(ns et.cb.publish-unseal-integration-test
  "Publishing a sealed Recipe, which is a one-way unseal.

  Cookbook's prose is end-to-end encrypted in the clients and this server holds
  no key. A visitor has no key either and must never have one, so publishing a
  Recipe whose text is still `enc:v1:…` would put base64 in front of a stranger —
  and there is no unpublish. The owner's browser therefore opens every envelope in
  the Recipe's trail and hands the plaintext back with the publish, in one
  transaction, and the latch closes only if nothing behind it is still sealed.

  **The ciphertexts in here are the fixture's real ones**, with their real
  plaintexts, rather than invented strings that merely start with the prefix. The
  server cannot tell the difference — a prefix is all it reads — but a test that
  invented them would be free to invent a shape the clients do not produce, and
  the payloads below are then exactly what a browser sends.

  Sealed rows are written with SQL rather than through the API for the reason the
  migration will run that way too: sealing goes through no write path in this app,
  because a write path would bump versions and write history rows in order to
  change an encoding."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.envelope :as envelope]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json]]))

(use-fixtures :each h/with-integration-db)

;; ---------------------------------------------------------------------------
;; the fixture, and the two shapes it gives this suite

(def ^:private fixture
  (delay
    (let [f (io/file "test/fixtures/seal-vectors.edn")]
      (when-not (.exists f)
        (throw (ex-info "The seal fixture is missing" {})))
      (edn/read-string (slurp f)))))

(defn- envelope-for
  "A real `[ciphertext plaintext]` pair for that column, out of the fixture. The
  `n`th distinct one, so a test can seal four columns and tell them apart
  afterwards."
  [column n]
  (let [vs (filterv #(= column (:column %)) (:vectors @fixture))
        v (nth vs (mod n (count vs)))]
    [(:sealed v) (:plaintext v)]))

(defn- ciphertext [column n] (first (envelope-for column n)))
(defn- plaintext [column n] (second (envelope-for column n)))

;; ---------------------------------------------------------------------------
;; reaching behind the API, the way the migration will

(defn- sql-exec! [statement]
  (jdbc/execute-one! (db/get-conn h/*ds*) (sql/format statement)))

(defn- sql-one [statement]
  (jdbc/execute-one! (db/get-conn h/*ds*) (sql/format statement) db/jdbc-opts))

(defn- sql-all [statement]
  (jdbc/execute! (db/get-conn h/*ds*) (sql/format statement) db/jdbc-opts))

(defn- seal-row!
  "Put envelopes into a row's prose columns behind the app's back."
  [table where values]
  (sql-exec! {:update table :set values :where where}))

(defn- row [id] (sql-one {:select [:*] :from [:recipes] :where [:= :id id]}))
(defn- history [id] (sql-all {:select [:*] :from [:recipe_history]
                              :where [:= :recipe_id id] :order-by [[:version :asc]]}))
(defn- proposals [id] (sql-all {:select [:*] :from [:recipe_proposals]
                                :where [:= :recipe_id id] :order-by [[:id :asc]]}))

(defn- anything-sealed?
  "Whether any prose column anywhere in the trail is still an envelope — the
  question the guard asks, asked independently of it."
  [id]
  (boolean (or (seq (envelope/sealed-in :recipes (row id)))
               (some #(seq (envelope/sealed-in :recipe_history %)) (history id))
               (some #(seq (envelope/sealed-in :recipe_proposals %)) (proposals id)))))

;; ---------------------------------------------------------------------------
;; the API

(defn- create! [title]
  (:body (POST-json "/api/recipes" {:title title
                                    :useful_when (str "when " title)
                                    :description (str "body of " title)})))

(defn- publish!
  ([id] (h/API :post (str "/api/recipes/" id "/publish") {}))
  ([id unsealed] (h/API :post (str "/api/recipes/" id "/publish")
                        {:body {:unsealed unsealed}})))

(defn- sealed-trail [id] (GET-json (str "/api/recipes/" id "/sealed")))

(defn- machine-opts [] {:token (h/machine-token-for h/*user-id*)})

(defn- inbox-count [] (count (:body (GET-json "/api/inbox"))))

(defn- versions [id] (:versions (:body (GET-json (str "/api/recipes/" id "/versions")))))

;; ---------------------------------------------------------------------------
;; a Recipe with something in all three tables, sealed

(defn- sealed-recipe!
  "A Recipe with three versions, a pending proposal, and an envelope in every
  prose column of all three tables. Answers the id and what each envelope must
  open to.

  Built through the API first — so the version ladder, the history rows and the
  proposal are the real ones this app makes — and then sealed in place, which is
  exactly the order the migration runs in."
  []
  (let [{:keys [id]} (create! "Sealed")]
    ;; two more versions, so there are two history rows to walk
    (h/API :put (str "/api/recipes/" id)
           {:body {:title "Sealed" :description "second body"
                   :modified_at (:modified_at (row id))}})
    (h/API :put (str "/api/recipes/" id)
           {:body {:title "Sealed" :description "third body"
                   :modified_at (:modified_at (row id))}})
    ;; a proposal an agent filed and nobody has answered
    (h/API :put (str "/api/recipes/" id)
           (assoc (machine-opts) :body {:title "Sealed" :description "the agent's body"}))
    (let [proposal-id (:id (first (proposals id)))]
      (seal-row! :recipes [:= :id id]
                 {:description (ciphertext :description 0)
                  :useful_when (ciphertext :useful_when 0)
                  :reason (ciphertext :reason 0)
                  :context (ciphertext :context 0)})
      (seal-row! :recipe_history [:and [:= :recipe_id id] [:= :version 1]]
                 {:description (ciphertext :description 1)})
      (seal-row! :recipe_history [:and [:= :recipe_id id] [:= :version 2]]
                 {:description (ciphertext :description 2)
                  :useful_when (ciphertext :useful_when 1)})
      (seal-row! :recipe_proposals [:= :id proposal-id]
                 {:description (ciphertext :description 0)
                  :reason (ciphertext :reason 0)})
      {:id id
       :proposal-id proposal-id
       :payload {:recipe {:description (plaintext :description 0)
                          :useful_when (plaintext :useful_when 0)
                          :reason (plaintext :reason 0)
                          :context (plaintext :context 0)}
                 :versions [{:version 1 :description (plaintext :description 1)}
                            {:version 2 :description (plaintext :description 2)
                             :useful_when (plaintext :useful_when 1)}]
                 :proposals [{:id proposal-id
                              :description (plaintext :description 0)
                              :reason (plaintext :reason 0)}]}})))

;; ---------------------------------------------------------------------------
;; GET /api/recipes/:id/sealed — the work order

(deftest the-trail-read-enumerates-every-envelope-and-nothing-else
  (let [{:keys [id proposal-id]} (sealed-recipe!)
        resp (sealed-trail id)
        {:keys [sealed total]} (:body resp)]
    (is (= 200 (:status resp)))
    (testing "the current row's four columns"
      (is (= #{:description :useful_when :reason :context} (set (keys (:recipe sealed)))))
      (is (every? envelope/sealed? (vals (:recipe sealed)))))

    (testing "every history version that has one, named by its version number —
              recipe_history has no id of its own"
      (is (= [{:version 2 :description (ciphertext :description 2)
               :useful_when (ciphertext :useful_when 1)}
              {:version 1 :description (ciphertext :description 1)}]
             (:versions sealed))))

    (testing "and the proposal, named by its id"
      (is (= [{:id proposal-id
               :description (ciphertext :description 0)
               :reason (ciphertext :reason 0)}]
             (:proposals sealed))))

    (testing "total is how many values there are to open"
      (is (= 9 total)))

    (testing "ciphertext only: a column in the clear is absent rather than null,
              because what this shape says is 'these must be opened'"
      (is (false? (contains? (:recipe sealed) :title)))
      (is (not-any? #(contains? % :title) (:versions sealed)))
      (is (every? (fn [entry] (every? envelope/sealed? (vals (dissoc entry :version))))
                  (:versions sealed))))))

(deftest a-plaintext-recipe-has-nothing-to-unseal
  (let [{:keys [id]} (create! "Clear")
        {:keys [sealed total]} (:body (sealed-trail id))]
    (is (= 0 total))
    (is (= {} (:recipe sealed)))
    (is (= [] (:versions sealed)))
    (is (= [] (:proposals sealed)))))

(deftest the-trail-read-is-the-owners
  (let [{:keys [id payload]} (sealed-recipe!)
        visitor-read #(h/with-real-auth
                        (h/API :get (str "/api/recipes/" id "/sealed") {:anonymous? true}))]
    (testing "a machine token is answered 404 — it cannot publish, so what it
              would take to publish is not its question"
      (is (= 404 (:status (h/API :get (str "/api/recipes/" id "/sealed") (machine-opts))))))
    (testing "and so is a visitor"
      (is (= 404 (:status (visitor-read)))))
    (testing "an id that matches nothing is the same answer"
      (is (= 404 (:status (sealed-trail 999999)))))
    (testing "and publishing does not open this door: the prose is public now, the
              question of what it took to get there is still the owner's"
      (is (= 200 (:status (publish! id payload))))
      (is (= 404 (:status (visitor-read))))
      (is (= 200 (:status (sealed-trail id)))))))

(deftest a-deleted-recipe-has-no-trail-to-publish
  (let [{:keys [id]} (sealed-recipe!)]
    (h/API :delete (str "/api/recipes/" id) {})
    (is (= 404 (:status (sealed-trail id))))
    (is (= 404 (:status (publish! id))))))

;; ---------------------------------------------------------------------------
;; the publish itself

(deftest the-payload-unseals-the-whole-trail-and-publishes
  (let [{:keys [id proposal-id payload]} (sealed-recipe!)
        before (row id)
        inbox-before (inbox-count)
        resp (publish! id payload)]
    (testing "200, and the latch is set"
      (is (= 200 (:status resp)))
      (is (= 1 (:published (:body resp))))
      (is (some? (:published_at (:body resp)))))

    (testing "the row's four columns are the plaintext that was sent"
      (is (= (plaintext :description 0) (:description (row id))))
      (is (= (plaintext :useful_when 0) (:useful_when (row id))))
      (is (= (plaintext :reason 0) (:reason (row id))))
      (is (= (plaintext :context 0) (:context (row id)))))

    (testing "and so is every history row and the proposal"
      (is (= [(plaintext :description 1) (plaintext :description 2)]
             (mapv :description (history id))))
      (is (= (plaintext :useful_when 1) (:useful_when (second (history id)))))
      (is (= (plaintext :description 0) (:description (first (proposals id)))))
      (is (= (plaintext :reason 0) (:reason (first (proposals id))))))

    (testing "nothing anywhere in the trail is still an envelope"
      (is (false? (anything-sealed? id)))
      (is (= 0 (:total (:body (sealed-trail id))))))

    (testing "**it is an encoding change, not a content change**: no version bump,
              no history row, no modified_at, no relabelling"
      (is (= (:version before) (:version (row id))))
      (is (= 2 (count (history id))))
      (is (= (:modified_at before) (:modified_at (row id))))
      (is (= (:source before) (:source (row id))))
      (is (= (:has_human_edit before) (:has_human_edit (row id)))))

    (testing "and no inbox noise: publishing is not an act the queue is for"
      (is (= inbox-before (inbox-count))))

    (testing "the proposal is still pending — unsealing it is not answering it"
      (is (nil? (:resolved_at (first (proposals id)))))
      (is (= proposal-id (:id (first (proposals id))))))

    (testing "the response is the unsealed row, which is what the client caches"
      (is (= (plaintext :description 0) (:description (:body resp))))
      (is (= (plaintext :useful_when 0) (:useful_when (:body resp)))))

    (testing "a visitor now reads the prose, which is the whole point"
      (h/with-real-auth
        (let [full (:body (h/API :get (str "/api/recipes/" id "?detail=full")
                                 {:anonymous? true}))]
          (is (= (plaintext :description 0) (:description full)))
          (is (= (plaintext :useful_when 0) (:useful_when full))))))

    (testing "and the owner's version ladder reads back in the clear, which is what
              lets the server compute `caution` for a published Recipe again"
      (is (not-any? #(envelope/sealed? (:description %)) (versions id)))
      (is (some? (:caution (:body (GET-json (str "/api/recipes/" id "?detail=full")))))))

    (testing "publishing again is the 200 no-op it always was"
      (let [again (publish! id)]
        (is (= 200 (:status again)))
        (is (= (:published_at (:body resp)) (:published_at (:body again))))))))

(deftest publishing-a-plaintext-recipe-is-untouched-by-any-of-this
  (let [{:keys [id]} (create! "Clear")
        before (row id)
        resp (publish! id)]
    (is (= 200 (:status resp)))
    (is (= 1 (:published (:body resp))))
    (is (= "body of Clear" (:description (row id))))
    (is (= (:version before) (:version (row id))))
    (is (= (:modified_at before) (:modified_at (row id))))
    (is (= 0 (count (history id))))))

;; ---------------------------------------------------------------------------
;; the guard, which is the thing that must not be wrong

(deftest a-sealed-recipe-is-not-published-without-a-payload
  (let [{:keys [id]} (sealed-recipe!)
        before (row id)
        resp (publish! id)]
    (testing "400, and the message says what is still sealed and where to publish from"
      (is (= 400 (:status resp)))
      (is (= "sealed" (:reason (:body resp))))
      (is (re-find #"(?i)still encrypted" (:error (:body resp))))
      (is (re-find #"(?i)web ui" (:error (:body resp)))))

    (testing "and nothing was written: the latch is open and the text untouched"
      (is (= 0 (:published (row id))))
      (is (nil? (:published_at (row id))))
      (is (= (:description before) (:description (row id))))
      (is (= (:modified_at before) (:modified_at (row id)))))))

(deftest an-incomplete-payload-writes-nothing-at-all
  (doseq [[label payload]
          [["a history version left sealed"
            (fn [p] (update p :versions (comp vec rest)))]
           ["the proposal left sealed"
            (fn [p] (assoc p :proposals []))]
           ["one column of the row left sealed"
            (fn [p] (update p :recipe dissoc :context))]]]
    (let [{:keys [id payload]} (let [built (sealed-recipe!)]
                                 (update built :payload payload))
          before (row id)
          resp (publish! id payload)]
      (testing label
        (is (= 400 (:status resp)))
        (is (= "sealed" (:reason (:body resp))))
        (testing "the refusal names what is left"
          (is (seq (:remaining (:body resp)))))
        (testing "**and the replacements it did apply are rolled back** — that is
                  what the one transaction is for: a published Recipe with an
                  unreadable history, or a half-unsealed private one, are both
                  states this cannot end in"
          (is (= 0 (:published (row id))))
          (is (= (:description before) (:description (row id))))
          (is (= (:useful_when before) (:useful_when (row id))))
          (is (true? (anything-sealed? id)))
          (testing "including the rows the payload did name"
            (is (every? envelope/sealed? (keep :description (history id))))))))))

(deftest an-out-of-trail-target-refuses-the-whole-publish
  ;; Every payload below is **otherwise complete**, so the publish would succeed
  ;; if the one thing being tested were not refused — and each case names the
  ;; refusal it expects, because several of these checks would otherwise be caught
  ;; by the one after them and a test that only asserted 400 would pass while the
  ;; check it was written for did nothing.
  (let [other-proposal (:id (first (proposals (:id (sealed-recipe!)))))]
    (doseq [[label mangle expected]
            [["a version this Recipe does not have"
              (fn [p] (update p :versions conj {:version 99 :description "invented"}))
              #"no version 99"]
             ["a version named as a string"
              (fn [p] (update p :versions conj {:version "1" :description "invented"}))
              #"no version \"1\""]
             ["a proposal filed against another Recipe"
              (fn [p] (update p :proposals conj {:id other-proposal :description "invented"}))
              #"is not a proposal against this Recipe"]
             ["a column that is not prose"
              (fn [p] (assoc-in p [:recipe :title] "a new title"))
              #"`title` is not a sealed column of recipes"]
             ["a column that is not prose, on a history row"
              (fn [p] (update p :versions conj {:version 1 :source "ui"}))
              #"`source` is not a sealed column of recipe_history"]
             ["a value that is not text"
              (fn [p] (assoc-in p [:recipe :description] 7))
              #"is not text"]
             ["a value that is itself an envelope — it was not opened"
              (fn [p] (assoc-in p [:recipe :description] (ciphertext :description 1)))
              #"itself sealed"]
             ["a column that is not sealed, so there is nothing to unseal there —
               publishing rewrites an encoding, it does not write text"
              (fn [p] (update p :versions conj {:version 1 :context "an invented context"}))
              #"`context` is not sealed"]]]
      (let [{:keys [id payload]} (sealed-recipe!)
            before (row id)
            resp (publish! id (mangle payload))]
        (testing label
          (is (= 400 (:status resp)))
          (is (re-find expected (str (:error (:body resp)))) (:error (:body resp)))
          (is (= 0 (:published (row id))))
          (is (= (:description before) (:description (row id))))
          (is (true? (anything-sealed? id))))))))

(deftest a-payload-that-is-not-the-shape-is-refused-rather-than-coerced
  (doseq [[label payload expected]
          [["a string where the object should be" "unsealed!" #"must be an object"]
           ["a list where the object should be" ["a"] #"must be an object"]
           ["a string for the row" {:recipe "text"} #"unsealed.recipe"]
           ["an object for the versions" {:versions {:version 1}} #"unsealed.versions"]
           ["a string for the proposals" {:proposals "none"} #"unsealed.proposals"]
           ["a bare string among the versions" {:versions ["v1"]} #"is an object"]]]
    (let [{:keys [id]} (sealed-recipe!)
          resp (publish! id payload)]
      (testing label
        (is (= 400 (:status resp)))
        (is (re-find expected (str (:error (:body resp)))) (:error (:body resp)))
        (is (= 0 (:published (row id))))
        (is (true? (anything-sealed? id)))))))

(deftest the-guard-is-the-servers-and-not-the-payloads
  (testing "a proposal filed *after* the client read the trail is still caught:
            the check reads the database, not the work order"
    (let [{:keys [id payload]} (sealed-recipe!)
          late (:id (first (proposals id)))]
      ;; the payload is complete for the trail as it was; seal something the
      ;; client never saw, exactly as a concurrent write would
      (seal-row! :recipe_proposals [:= :id late] {:context (ciphertext :context 0)})
      (let [resp (publish! id payload)]
        (is (= 400 (:status resp)))
        (is (= 0 (:published (row id))))
        (is (some #(str/includes? % "context") (:remaining (:body resp))))))))

(deftest a-machine-cannot-publish-however-much-plaintext-it-sends
  (let [{:keys [id payload]} (sealed-recipe!)
        resp (h/API :post (str "/api/recipes/" id "/publish")
                    (assoc (machine-opts) :body {:unsealed payload}))]
    (testing "403 from the machine rules, before any of this is even asked"
      (is (= 403 (:status resp)))
      (is (re-find #"(?i)publishing is the owner" (:error (:body resp)))))
    (testing "and nothing was unsealed on the way past"
      (is (true? (anything-sealed? id)))
      (is (= 0 (:published (row id)))))))

;; ---------------------------------------------------------------------------
;; what an agent reading the catalogue is told

(deftest the-two-routes-say-what-they-do
  (let [entries (h/describe-endpoints)
        doc-for (fn [method path]
                  (:doc (first (filter #(= [method path] ((juxt :method :path) %)) entries))))]
    (testing "the trail read is in the catalogue"
      (is (some? (doc-for "GET" "/api/recipes/:id/sealed")))
      (is (re-find #"(?i)unseal" (doc-for "GET" "/api/recipes/:id/sealed"))))
    (testing "and the publish says it unseals, and that it is one way"
      (let [doc (doc-for "POST" "/api/recipes/:id/publish")]
        (is (re-find #"(?i)one-way unseal|one way" doc))
        (is (re-find #"unsealed" doc))
        (is (re-find #"(?i)no unpublish" doc))))))

;; ---------------------------------------------------------------------------
;; The other half of one way: a published Recipe may not gain an envelope
;;
;; Publishing unseals because a visitor has no key and there is no unpublish.
;; That lasts exactly one save unless something says otherwise — the clients seal
;; every prose write — so the live check's first published Recipe went back to
;; `enc:v1:` on the next edit, in public. These are the three doors onto a
;; published Recipe's prose.

(defn- published-recipe! []
  (let [{:keys [id]} (create! "Public")]
    (is (= 200 (:status (publish! id))))
    id))

(deftest a-save-cannot-seal-a-published-recipe
  (doseq [column [:description :useful_when :reason :context]]
    (let [id (published-recipe!)
          before (row id)
          resp (h/API :put (str "/api/recipes/" id)
                      {:body {column (ciphertext (if (#{:reason :context} column)
                                                   column :description) 0)
                              :modified_at (:modified_at before)}})]
      (testing (str "a sealed " (name column) " over a published Recipe")
        (is (= 400 (:status resp)))
        (is (= "sealed" (:reason (:body resp))))
        (is (= [(name column)] (:sealed (:body resp))))
        (is (re-find #"(?i)published" (:error (:body resp)))))
      (testing "and nothing was written — not the text, not the version, not the stamp"
        (is (= (:description before) (:description (row id))))
        (is (= (:version before) (:version (row id))))
        (is (= (:modified_at before) (:modified_at (row id))))
        (is (= 0 (count (history id)))))))

  (testing "**all four and not the two a visitor is served.** reason and context
            never reach a visitor, so sealing those leaks nothing — but *published
            means the whole trail is plaintext* is what the publish guard
            establishes and what step 7's walker leans on when it skips published
            Recipes"
    (is (= [:description :useful_when :reason :context]
           (:recipes envelope/prose-columns)))))

(deftest a-save-in-the-clear-on-a-published-recipe-is-untouched-by-the-guard
  (let [id (published-recipe!)
        before (row id)
        resp (h/API :put (str "/api/recipes/" id)
                    {:body {:description "rewritten in public, in the clear"
                            :modified_at (:modified_at before)}})]
    (is (= 200 (:status resp)))
    (is (= "rewritten in public, in the clear" (:description (row id))))
    (is (= 2 (:version (row id))))
    (is (= 1 (count (history id))))))

(deftest an-unpublished-recipe-may-be-sealed-freely
  (testing "the guard is about the latch and nothing else: the sealed shelf is the
            whole point of the seal"
    (let [{:keys [id]} (create! "Private")
          resp (h/API :put (str "/api/recipes/" id)
                      {:body {:description (ciphertext :description 0)
                              :modified_at (:modified_at (row id))}})]
      (is (= 200 (:status resp)))
      (is (envelope/sealed? (:description (row id)))))))

(deftest a-machine-cannot-propose-sealed-text-against-a-published-recipe
  (let [id (published-recipe!)
        before (row id)
        resp (h/API :put (str "/api/recipes/" id)
                    (assoc (machine-opts)
                           :body {:description (ciphertext :description 0)}))]
    (testing "400 as it is filed, rather than 202 now and an unapprovable entry later"
      (is (= 400 (:status resp)))
      (is (= "sealed" (:reason (:body resp)))))
    (testing "and nothing was filed, and nothing about the Recipe moved"
      (is (= [] (proposals id)))
      (is (= (:description before) (:description (row id))))
      (is (= (:version before) (:version (row id)))))))

(deftest a-machine-may-still-propose-against-a-published-recipe-in-the-clear
  (testing "the owner's own door — *its up to the human to approve or not* — is not
            what this guard closes"
    (let [id (published-recipe!)
          resp (h/API :put (str "/api/recipes/" id)
                      (assoc (machine-opts) :body {:description "the agent's plain body"}))]
      (is (= 202 (:status resp)))
      (is (= 1 (count (proposals id))))
      (testing "and approving it lands, which is the state the sealed one would
                never have reached"
        (let [entry (last (filter #(= "proposed" (:kind %)) (:body (GET-json "/api/inbox"))))
              approved (h/API :post (str "/api/inbox/" (:id entry) "/approve") {})]
          (is (= 200 (:status approved)))
          (is (= "the agent's plain body" (:description (row id)))))))))

(deftest approving-cannot-seal-a-published-recipe
  (testing "the guarantee behind the other two, and unreachable through them: a
            proposal can only be sealed here if it was filed before the publish,
            and publishing unseals every proposal in the trail. Seeded behind the
            app's back, which is the only way this state exists at all."
    (let [id (published-recipe!)
          before (row id)
          _ (h/API :put (str "/api/recipes/" id)
                   (assoc (machine-opts) :body {:description "the agent's plain body"}))
          proposal-id (:id (first (proposals id)))
          entry (last (filter #(= "proposed" (:kind %)) (:body (GET-json "/api/inbox"))))]
      (seal-row! :recipe_proposals [:= :id proposal-id]
                 {:description (ciphertext :description 0)})
      (let [resp (h/API :post (str "/api/inbox/" (:id entry) "/approve") {})]
        (is (= 400 (:status resp)))
        (is (= "sealed" (:reason (:body resp))))
        (testing "and the transaction rolled back: no version, no history row, and
                  the proposal is still waiting"
          (is (= (:version before) (:version (row id))))
          (is (= (:description before) (:description (row id))))
          (is (= 0 (count (history id))))
          (is (nil? (:resolved_at (first (proposals id))))))))))

(deftest the-write-guard-holds-at-the-db-layer-too
  (testing "**asked here rather than over HTTP, because over HTTP it cannot fail:**
    `update-recipe-handler` refuses a sealed write to a published Recipe before it
    picks between a save and a proposal, so the guard inside `update-recipe` is
    never the one that answers. It is kept because that handler is not the only
    thing that could ever call this — a script, a migration, a later route — and a
    guard nothing exercises is a guard that quietly stops working. So it is
    exercised."
    (let [id (published-recipe!)
          before (row id)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (db.recipe/update-recipe h/*ds* h/*user-id* id
                                            {:description (ciphertext :description 0)}
                                            nil)))
      (is (= (:description before) (:description (row id))))
      (is (= (:version before) (:version (row id))))
      (is (= 0 (count (history id))))
      (testing "and an unpublished Recipe goes through the same call untouched"
        (let [{other :id} (create! "Private too")]
          (is (some? (db.recipe/update-recipe h/*ds* h/*user-id* other
                                              {:description (ciphertext :description 0)}
                                              nil)))
          (is (envelope/sealed? (:description (row other)))))))))

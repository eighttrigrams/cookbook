(ns et.cb.shelf-order-integration-test
  "`?order=` over HTTP: how a caller asks for the other order, what an unknown value
  does, and who may ask.

  > i also need a switcher on the main page between the ranked order we have now, and
  > one order which is most recently added first

  The two orders' semantics are `shelf-order-db-test`'s. What is here is the parameter:
  that it reaches the query, that it is read the way this API's other flag parameters
  are read — one exact value means the other thing, everything else means the default —
  that it composes with the four narrowings over the wire, and that an anonymous caller
  may use it, which is where it differs from the two Scope parameters sitting beside it
  in the same handler."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json]]))

(use-fixtures :each h/with-integration-db)

(defn- create! [title]
  (:body (POST-json "/api/recipes"
                    {:title title :useful_when "when testing" :description "body v1"})))

(defn- backdate-created-at! [id stamp]
  (jdbc/execute-one! (db/get-conn h/*ds*)
    (sql/format {:update :recipes :set {:created_at stamp} :where [:= :id id]})))

(defn- shelf
  ([] (shelf ""))
  ([query] (mapv :title (:body (GET-json (str "/api/recipes" query))))))

(deftest order-newest-is-most-recently-added-and-anything-else-is-the-ranking
  (let [old (:id (create! "Added long ago"))
        new (:id (create! "Added just now"))]
    (backdate-created-at! old "2020-01-01 00:00:00")
    (backdate-created-at! new "2026-01-01 00:00:00")
    ;; the older one is the much-read one, so the two orders disagree about it —
    ;; without that this whole file could pass against one order
    (dotimes [_ 5] (GET-json (str "/api/recipes/" old "?detail=full")))
    (is (= ["Added long ago" "Added just now"] (shelf))
        "the default: the read one leads")
    (is (= ["Added just now" "Added long ago"] (shelf "?order=newest")))
    (testing "**read the way ?detail and ?human are read**: one exact value means the
              other order and everything else — absent, empty, a different case, junk —
              means the default, rather than each meaning something"
      (doseq [param ["" "?order=" "?order=ranked" "?order=NEWEST" "?order=Newest"
                     "?order=newest%20" "?order=oldest" "?order=1" "?order=sideways"]]
        (is (= ["Added long ago" "Added just now"] (shelf param))
            (str "GET /api/recipes" param))))
    (testing "and none of those is an error — a read that refused to answer would be
              this API's only one"
      (doseq [param ["?order=oldest" "?order=1"]]
        (is (= 200 (:status (GET-json (str "/api/recipes" param)))))))))

(deftest the-order-is-total-over-the-wire
  ;; `created_at` is second-resolution and these requests all land inside one second,
  ;; which is the *ordinary* case for a seeding script or an agent writing a few
  ;; Recipes — so this fixture needs no backdating to be the interesting one. Five
  ;; identical requests must answer identically.
  (doseq [n (range 5)] (create! (str "Written in one second " n)))
  (let [runs (repeatedly 5 #(shelf "?order=newest"))]
    (is (apply = runs))
    (is (= ["Written in one second 4" "Written in one second 3" "Written in one second 2"
            "Written in one second 1" "Written in one second 0"]
           (first runs))
        "insertion order reversed, which is what the id tiebreaker gives")))

(deftest it-composes-with-the-narrowings-over-the-wire
  (let [bread (:id (:body (POST-json "/api/scopes" {:title "Bread" :description ""})))
        old (:id (:body (POST-json "/api/recipes"
                                   {:title "Sourdough long ago" :useful_when ""
                                    :description "b" :scope_ids [bread]})))
        new (:id (:body (POST-json "/api/recipes"
                                   {:title "Sourdough just now" :useful_when ""
                                    :description "b" :scope_ids [bread]})))]
    (create! "Deploying")
    (backdate-created-at! old "2020-01-01 00:00:00")
    (backdate-created-at! new "2026-01-01 00:00:00")
    (dotimes [_ 5] (GET-json (str "/api/recipes/" old "?detail=full")))
    (testing "?search= and ?order= at once, and the order of the query string is not
              the order anything is applied in"
      (is (= ["Sourdough just now" "Sourdough long ago"]
             (shelf "?search=sourdough&order=newest")))
      (is (= ["Sourdough just now" "Sourdough long ago"]
             (shelf "?order=newest&search=sourdough")))
      (is (= ["Sourdough long ago" "Sourdough just now"]
             (shelf "?search=sourdough"))
          "and the same narrowing in the default order is the other way round"))
    (testing "the narrowing still narrows — an order that had replaced the clause
              would answer with three rows"
      (is (= 2 (count (shelf "?search=sourdough&order=newest")))))
    (testing "and with a Scope filter, which is the other kind of clause"
      (is (= ["Sourdough just now" "Sourdough long ago"]
             (shelf (str "?include-scopes=" bread "&order=newest")))))))

(deftest a-visitor-may-ask-for-either-order
  ;; **Where this parameter differs from the two beside it.** `?exclude-scopes` and
  ;; `?include-scopes` are ignored outright for an anonymous caller, because they would
  ;; answer questions about the owner's filing. An order answers nothing of the kind:
  ;; the ranking already explains the shelf a visitor is looking at, so the other order
  ;; is theirs to ask for. Asserted because the refusal is easy to over-apply to the
  ;; parameter that arrived in the same commit.
  (let [old (:id (create! "Published long ago"))
        new (:id (create! "Published just now"))]
    (POST-json (str "/api/recipes/" old "/publish") {})
    (POST-json (str "/api/recipes/" new "/publish") {})
    (backdate-created-at! old "2020-01-01 00:00:00")
    (backdate-created-at! new "2026-01-01 00:00:00")
    (dotimes [_ 5] (GET-json (str "/api/recipes/" old "?detail=full")))
    (h/with-real-auth
      (let [anon (fn [query] (mapv :title (:body (h/API :get (str "/api/recipes" query)
                                                        {:anonymous? true}))))]
        (is (= ["Published long ago" "Published just now"] (anon ""))
            "their default is the ranking")
        (is (= ["Published just now" "Published long ago"] (anon "?order=newest"))
            "and their ?order=newest is honoured, unlike their ?include-scopes")))))

(deftest the-parameter-is-in-the-catalogue-an-agent-reads
  (let [doc (:doc (first (filter #(= ["GET" "/api/recipes"] ((juxt :method :path) %))
                                 (h/describe-endpoints))))]
    (testing "named, with the one value that does anything"
      (is (re-find #"\?order=newest" doc)))
    (testing "and that everything else is the default, which is what stops an agent
              inventing `?order=oldest` and believing it"
      (is (re-find #"(?i)only\s+other\s+value" doc)))
    (testing "and the distinction a caller would otherwise get wrong: added is not
              touched"
      (is (re-find #"(?i)most\s+recently\s+\*?\*?added" doc))
      (is (re-find #"(?i)touched" doc)))
    (testing "and that the tie is broken, since an untotal order is the thing an agent
              paging through a listing would be bitten by"
      (is (re-find #"(?i)second-resolution" doc)))))

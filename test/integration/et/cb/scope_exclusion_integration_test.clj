(ns et.cb.scope-exclusion-integration-test
  "`?exclude-scopes=` over HTTP: how a caller spells the ids, what junk does, and
  who is refused the parameter altogether.

  The clause's own semantics are `et.cb.scope-exclusion-db-test`'s — a Recipe with
  no Scopes survives, one carrying an excluded Scope and a kept one does not, the
  survivors' counts and order are untouched. What is here is the layer above:
  that the parameter reaches that clause, that it composes with the other two
  narrowings over the wire, and the visitor rule, which is a rule about a request
  and so has to be asserted against one.

  **The visitor test is the privacy one.** It is built so that honouring the
  parameter would change its answer — the published Recipe in it carries the
  excluded Scope, and the owner is shown asking the identical question and losing
  that identical row. A test that could not have been narrowed would pass against
  an implementation that had merely stopped working."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.db.user :as db.user]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json]]))

(use-fixtures :each h/with-integration-db)

(defn- scope! [title]
  (:id (:body (POST-json "/api/scopes" {:title title :description ""}))))

(defn- recipe!
  ([title] (recipe! title []))
  ([title scope-ids]
   (:body (POST-json "/api/recipes" {:title title :useful_when "when testing"
                                     :description "body v1"
                                     :scope_ids (vec scope-ids)}))))

(defn- shelf
  "Titles from the listing, for whatever query string is given."
  ([] (shelf ""))
  ([query] (mapv :title (:body (GET-json (str "/api/recipes" query))))))

(deftest the-parameter-takes-a-comma-separated-list-of-scope-ids
  (let [bread (scope! "Bread")
        deployment (scope! "Deployment")]
    (recipe! "Sourdough" [bread])
    (recipe! "Deploying" [deployment])
    (recipe! "Unfiled")
    (is (= #{"Sourdough" "Deploying" "Unfiled"} (set (shelf))))
    (testing "one id"
      (is (= #{"Deploying" "Unfiled"} (set (shelf (str "?exclude-scopes=" bread))))))
    (testing "two, comma-separated, each taking its own away"
      (is (= ["Unfiled"] (shelf (str "?exclude-scopes=" bread "," deployment)))))
    (testing "and whitespace around one is not a different id"
      (is (= ["Unfiled"] (shelf (str "?exclude-scopes=" bread ",%20" deployment)))))))

(deftest junk-narrows-by-nothing-and-answers-200
  (let [bread (scope! "Bread")]
    (recipe! "Sourdough" [bread])
    (recipe! "Unfiled")
    (doseq [param ["" "abc" "abc,def" "," "0" "-1" "99999" "3.5"]]
      (let [resp (GET-json (str "/api/recipes?exclude-scopes=" param))]
        (is (= 200 (:status resp)) (str "?exclude-scopes=" param))
        (is (= #{"Sourdough" "Unfiled"} (set (mapv :title (:body resp))))
            (str "?exclude-scopes=" param))))
    (testing "junk beside a real id leaves the real id's work intact — the list is
              read one entry at a time rather than refused whole"
      (is (= ["Unfiled"] (shelf (str "?exclude-scopes=abc," bread ",def")))))
    (testing "and a well-formed id that is not the caller's excludes nothing,
              because answering 404 for it would say which ids exist"
      (let [stranger (:id (db.user/create-user h/*ds* "stranger" "pw"))
            theirs (:id (:body (h/API :post "/api/scopes" {:body {:title "Theirs"}
                                                           :as-user stranger})))]
        (is (= #{"Sourdough" "Unfiled"} (set (shelf (str "?exclude-scopes=" theirs)))))))))

(defn- machine-token! []
  (db.user/set-machine-user-password! h/*ds* h/*user-id* "machine-secret")
  (:token (:body (POST-json "/api/auth/login" {:username "machine-user"
                                               :password "machine-secret"}))))

(deftest it-composes-with-the-other-two-narrowings
  ;; Three Recipes and three narrowings, arranged so each one takes a **different**
  ;; row away: without that, a combination reads as composing while in fact only
  ;; the strictest of them is doing anything.
  (let [bread (scope! "Bread")]
    (recipe! "Sourdough starter" [bread])           ; the exclusion takes this one
    (recipe! "Starter culture")                     ; the survivor of all three
    (recipe! "Deploying")                           ; the search takes this one
    ;; And this one the human filter takes: the fixture's own requests carry no
    ;; machine token, so everything above is the owner's and carries the bit.
    (h/API :post "/api/recipes" {:token (machine-token!)
                                 :body {:title "Starter by the agent"}})
    (testing "each narrowing on its own"
      (is (= #{"Sourdough starter" "Starter culture" "Starter by the agent"}
             (set (shelf "?search=starter"))))
      (is (= #{"Starter culture" "Deploying" "Starter by the agent"}
             (set (shelf (str "?exclude-scopes=" bread)))))
      (is (= #{"Sourdough starter" "Starter culture" "Deploying"}
             (set (shelf "?human=true")))))
    (testing "with ?search=, both directions — the order of the query string is
              not the order anything is applied in"
      (is (= #{"Starter culture" "Starter by the agent"}
             (set (shelf (str "?search=starter&exclude-scopes=" bread)))))
      (is (= #{"Starter culture" "Starter by the agent"}
             (set (shelf (str "?exclude-scopes=" bread "&search=starter"))))))
    (testing "with ?human=true"
      (is (= #{"Starter culture" "Deploying"}
             (set (shelf (str "?human=true&exclude-scopes=" bread))))))
    (testing "and all three at once, each having taken its own row: one survivor"
      (is (= ["Starter culture"]
             (shelf (str "?search=starter&human=true&exclude-scopes=" bread)))))))

(deftest a-visitors-exclude-scopes-changes-nothing
  ;; **The privacy test.** A visitor is sent no `scopes` key on anything, and
  ;; unlike the tags — whose presence is testable through ?search — nothing
  ;; searches the Scopes, so their presence is not testable either. Honouring this
  ;; parameter would hand that back: rows vanishing on request is a way to ask
  ;; which published Recipes carry Scope 4, one id at a time.
  (let [bread (scope! "Bread")
        filed (recipe! "Sourdough" [bread])
        unfiled (recipe! "Ciabatta")]
    (POST-json (str "/api/recipes/" (:id filed) "/publish") {})
    (POST-json (str "/api/recipes/" (:id unfiled) "/publish") {})
    (h/with-real-auth
      (let [anon (fn [query] (mapv :title (:body (h/API :get (str "/api/recipes" query)
                                                        {:anonymous? true}))))]
        (testing "the flip took, and the published Recipe carrying the Scope is in
                  the answer — which is what gives the next assertion something to
                  lose"
          (is (= #{"Sourdough" "Ciabatta"} (set (anon ""))))
          (is (false? (contains? (first (:body (h/API :get "/api/recipes"
                                                      {:anonymous? true})))
                                 :scopes))))
        (testing "excluding the Scope the published Recipe carries changes nothing"
          (is (= (anon "") (anon (str "?exclude-scopes=" bread)))))
        (testing "and neither does sweeping every id there could be, which is the
                  binary search this refusal exists to refuse"
          (is (= (anon "") (anon "?exclude-scopes=1,2,3,4,5,6,7,8,9,10"))))
        (testing "while ?search= and ?human=true still narrow for them — this is a
                  refusal of one parameter, not a visitor who cannot filter"
          (is (= ["Sourdough"] (anon "?search=sour"))))))
    (testing "and the owner, naming the same id against the same Recipe, does lose
              it — so none of the above can pass because the exclusion stopped
              working"
      (is (= #{"Sourdough" "Ciabatta"} (set (shelf))))
      (is (= ["Ciabatta"] (shelf (str "?exclude-scopes=" bread)))))))

(deftest a-machine-token-is-on-the-owners-side-of-this
  ;; Every Scope read is, and this is one: an agent that may read the list and file
  ;; a Recipe under a Scope may narrow by one. The two rules that do refuse a
  ;; machine are both about the publish latch, which this is not.
  (let [bread (scope! "Bread")]
    (recipe! "Sourdough" [bread])
    (recipe! "Unfiled")
    (let [token (machine-token!)
          as-machine (fn [query] (mapv :title (:body (h/API :get (str "/api/recipes" query)
                                                            {:token token}))))]
      (is (= #{"Sourdough" "Unfiled"} (set (as-machine ""))))
      (is (= ["Unfiled"] (as-machine (str "?exclude-scopes=" bread)))))))

(deftest the-parameter-is-in-the-catalogue-an-agent-reads
  (let [doc (:doc (first (filter #(= ["GET" "/api/recipes"] ((juxt :method :path) %))
                                 (h/describe-endpoints))))]
    (testing "named, with what it takes"
      (is (re-find #"\?exclude-scopes" doc))
      (is (re-find #"(?i)scope\s+ids" doc)))
    (testing "and the two things a caller cannot guess: that this one only ever
              hides, and that a Recipe filed under nothing is never hidden by it"
      (is (re-find #"(?i)negative" doc))
      (is (re-find #"(?i)no\s+scope\s+at\s+all\s+is\s+never\s+hidden" doc)))
    ;; **This regex was loosened rather than deleted when ?include-scopes arrived.**
    ;; It read `visitor's ?exclude-scopes is ignored`, and the catalogue now refuses
    ;; both parameters in one sentence — so the assertion that survives is that this
    ;; parameter is named in whatever sentence does the refusing. Its mirror for
    ;; ?include-scopes is in `et.cb.scope-inclusion-integration-test`, where that
    ;; parameter's subject is.
    (testing "and that an anonymous caller is refused it, since an agent reading
              this is being told what the API does and not only what it wants"
      (is (re-find #"(?i)visitor'?s?[^.]*\?exclude-scopes[^.]*(?:is|are)\s+ignored" doc)))))

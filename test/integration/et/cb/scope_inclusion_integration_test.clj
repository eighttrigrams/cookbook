(ns et.cb.scope-inclusion-integration-test
  "`?include-scopes=` over HTTP: how a caller spells the ids, what junk does, and
  who is refused the parameter altogether.

  The clause's own semantics are `et.cb.scope-inclusion-db-test`'s — at least one
  of the named Scopes keeps a Recipe, a Recipe filed under none of them goes, an
  unowned id keeps nothing. What is here is the layer above: that the parameter
  reaches that clause, that it composes with the other three narrowings over the
  wire, and the visitor rule, which is a rule about a request and so has to be
  asserted against one.

  **The junk test is where the two parameters stop being symmetrical**, and it is
  the reason this file is not a copy of the exclusion's with one word changed. Over
  there every piece of junk leaves the listing *unchanged*, so one assertion covers
  the lot. Here a well-formed id that matches nothing narrows to **nothing** — an
  empty shelf is the correct answer to *show me the Recipes in a Scope that is not
  yours* — while unparseable junk is dropped before it reaches the clause and so
  narrows by nothing at all. Two behaviours, one for each side of
  `common/parse-id-list`, and the difference is invisible on the negative filter.

  **The visitor test is the privacy one**, and it carries more weight than its
  sibling: an exclusion lets an anonymous caller infer which published Recipes carry
  a Scope by diffing two listings, and this one hands the answer over in a single
  response. Built the same way — the published Recipe carries the Scope being named,
  and the owner is shown asking the identical question and keeping that identical
  row — so a test that could not have been narrowed cannot pass here either."
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
      (is (= ["Sourdough"] (shelf (str "?include-scopes=" bread)))))
    (testing "two, comma-separated — the **union**, which is the whole of what he
              asked for: neither Recipe carries both Scopes, and both are listed"
      (is (= #{"Sourdough" "Deploying"}
             (set (shelf (str "?include-scopes=" bread "," deployment))))))
    (testing "and whitespace around one is not a different id"
      (is (= #{"Sourdough" "Deploying"}
             (set (shelf (str "?include-scopes=" bread ",%20" deployment))))))
    (testing "the unfiled Recipe is gone from every one of those answers, which is
              this parameter's inversion of the exclusion's most-asked-about case"
      (is (not (contains? (set (shelf (str "?include-scopes=" bread "," deployment)))
                          "Unfiled"))))))

(deftest junk-narrows-by-nothing-but-a-well-formed-stranger-narrows-to-nothing
  ;; **The two halves of `parse-id-list`, and they answer differently here.**
  ;; Unparseable junk never becomes an id, so the clause is not built at all and
  ;; the listing is untouched; a well-formed id that matches no association builds
  ;; the clause and keeps nothing. On the exclusion both look identical — the
  ;; listing is unchanged either way — which is why this distinction is asserted
  ;; here and not there.
  (let [bread (scope! "Bread")]
    (recipe! "Sourdough" [bread])
    (recipe! "Unfiled")
    (testing "junk that parses to no ids at all leaves the shelf alone"
      (doseq [param ["" "abc" "abc,def" "," "3.5"]]
        (let [resp (GET-json (str "/api/recipes?include-scopes=" param))]
          (is (= 200 (:status resp)) (str "?include-scopes=" param))
          (is (= #{"Sourdough" "Unfiled"} (set (mapv :title (:body resp))))
              (str "?include-scopes=" param)))))
    (testing "a well-formed id nothing is filed under answers 200 with an empty
              shelf — not an error, because an error would say which ids exist"
      (doseq [param ["0" "99999"]]
        (let [resp (GET-json (str "/api/recipes?include-scopes=" param))]
          (is (= 200 (:status resp)) (str "?include-scopes=" param))
          (is (= [] (mapv :title (:body resp))) (str "?include-scopes=" param)))))
    (testing "junk beside a real id leaves the real id's work intact — the list is
              read one entry at a time rather than refused whole"
      (is (= ["Sourdough"] (shelf (str "?include-scopes=abc," bread ",def")))))
    (testing "and a well-formed id that is not the caller's keeps nothing, for the
              same reason it excludes nothing on the other parameter: the join runs
              through `scopes` and an unowned id matches no association"
      (let [stranger (:id (db.user/create-user h/*ds* "stranger" "pw"))
            theirs (:id (:body (h/API :post "/api/scopes" {:body {:title "Theirs"}
                                                           :as-user stranger})))]
        (is (= [] (shelf (str "?include-scopes=" theirs))))
        (testing "but alongside one of the caller's own it costs nothing, which is
                  the case a client with a stale id in its set is actually in"
          (is (= ["Sourdough"] (shelf (str "?include-scopes=" theirs "," bread)))))))))

(defn- machine-token! []
  (db.user/set-machine-user-password! h/*ds* h/*user-id* "machine-secret")
  (:token (:body (POST-json "/api/auth/login" {:username "machine-user"
                                               :password "machine-secret"}))))

(deftest it-composes-with-the-other-three-narrowings
  ;; Four Recipes and four narrowings, arranged so each one takes a **different**
  ;; row away: without that, a combination reads as composing while in fact only
  ;; the strictest of them is doing anything.
  (let [bread (scope! "Bread")
        favourites (scope! "Favourites")]
    (recipe! "Sourdough starter" [bread])           ; the survivor of all four
    (recipe! "Starter culture")                     ; the inclusion takes this one
    (recipe! "Deploying bread" [bread])             ; the search takes this one
    (recipe! "Favourite starter" [bread favourites]) ; the exclusion takes this one
    ;; And this one the human filter takes: the fixture's own requests carry no
    ;; machine token, so everything above is the owner's and carries the bit.
    (h/API :post "/api/recipes" {:token (machine-token!)
                                 :body {:title "Starter by the agent"
                                        :scope_ids [bread]}})
    (testing "each narrowing on its own"
      (is (= #{"Sourdough starter" "Starter culture" "Favourite starter"
               "Starter by the agent"}
             (set (shelf "?search=starter"))))
      (is (= #{"Sourdough starter" "Deploying bread" "Favourite starter"
               "Starter by the agent"}
             (set (shelf (str "?include-scopes=" bread)))))
      (is (= #{"Sourdough starter" "Starter culture" "Deploying bread"
               "Favourite starter"}
             (set (shelf "?human=true")))))
    (testing "with ?search=, both directions — the order of the query string is
              not the order anything is applied in"
      (is (= #{"Sourdough starter" "Favourite starter" "Starter by the agent"}
             (set (shelf (str "?search=starter&include-scopes=" bread)))))
      (is (= #{"Sourdough starter" "Favourite starter" "Starter by the agent"}
             (set (shelf (str "?include-scopes=" bread "&search=starter"))))))
    (testing "with ?human=true"
      (is (= #{"Sourdough starter" "Deploying bread" "Favourite starter"}
             (set (shelf (str "?human=true&include-scopes=" bread))))))
    (testing "and with ?exclude-scopes= — the pair the UI never puts up at once,
              and the endpoint has no opinion about: *in Bread and not in
              Favourites* is a coherent question with a coherent answer"
      (is (= #{"Sourdough starter" "Deploying bread" "Starter by the agent"}
             (set (shelf (str "?include-scopes=" bread
                              "&exclude-scopes=" favourites))))))
    (testing "and all four at once, each having taken its own row: one survivor"
      (is (= ["Sourdough starter"]
             (shelf (str "?search=starter&human=true&include-scopes=" bread
                         "&exclude-scopes=" favourites)))))))

(deftest the-parameter-is-in-the-catalogue-an-agent-reads
  ;; The mirror of the exclusion's catalogue test, and it is worth having both: an
  ;; agent reads this docstring to find out what the endpoint takes, so a parameter
  ;; that exists and is not described is a parameter no agent will use — and one
  ;; described without its inversions is worse, since every one of them is a thing a
  ;; caller would otherwise guess from the sibling and guess wrong.
  (let [doc (:doc (first (filter #(= ["GET" "/api/recipes"] ((juxt :method :path) %))
                                 (h/describe-endpoints))))]
    (testing "named, with what it takes"
      (is (re-find #"\?include-scopes" doc))
      (is (re-find #"(?i)scope\s+ids" doc)))
    (testing "and that it is the **positive** one, so the pair is named as a pair
              rather than leaving a reader to infer the direction from an example"
      (is (re-find #"(?i)positive" doc)))
    (testing "and the OR, in his own word for it — an agent that read this as an
              AND would file its query wrong and never know"
      (is (re-find #"(?i)at\s+least\s+one" doc))
      (is (re-find #"(?i)OR\s+filter" doc)))
    (testing "and the inversion a caller would otherwise carry over from
              ?exclude-scopes: the unfiled Recipe is kept by that one and dropped
              by this one"
      (is (re-find #"(?i)no\s+Scope\s+at\s+all\s+falls\s+out" doc)))
    (testing "and that an anonymous caller is refused it, which is the sentence
              that matters most here: this parameter answers the question the
              other one only lets a caller infer"
      (is (re-find #"(?i)visitor'?s?[^.]*\?include-scopes[^.]*(?:is|are)\s+ignored" doc)))))

(deftest a-visitors-include-scopes-changes-nothing
  ;; **The privacy test, and the more urgent of the pair.** A visitor is sent no
  ;; `scopes` key on anything, and unlike the tags — whose presence is testable
  ;; through ?search — nothing searches the Scopes, so their presence is not
  ;; testable either. The exclusion would hand that back by inference; this
  ;; parameter would hand it back outright, since the rows that came back would be
  ;; exactly the published Recipes carrying the named Scope.
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
                  single out"
          (is (= #{"Sourdough" "Ciabatta"} (set (anon ""))))
          (is (false? (contains? (first (:body (h/API :get "/api/recipes"
                                                      {:anonymous? true})))
                                 :scopes))))
        (testing "asking for the Recipes of the Scope the published one carries
                  changes nothing — they are not told which of the two it is"
          (is (= (anon "") (anon (str "?include-scopes=" bread)))))
        (testing "and an id nothing is filed under does not empty their shelf,
                  which is how this refusal fails if it is written as 'narrow by
                  less' rather than 'do not narrow at all'"
          (is (= (anon "") (anon "?include-scopes=99999")))
          (is (= (anon "") (anon "?include-scopes=1,2,3,4,5,6,7,8,9,10"))))
        (testing "while ?search= and ?human=true still narrow for them — this is a
                  refusal of one parameter, not a visitor who cannot filter"
          (is (= ["Sourdough"] (anon "?search=sour"))))))
    (testing "and the owner, naming the same id against the same Recipe, does keep
              only it — so none of the above can pass because the clause stopped
              working"
      (is (= #{"Sourdough" "Ciabatta"} (set (shelf))))
      (is (= ["Sourdough"] (shelf (str "?include-scopes=" bread)))))))

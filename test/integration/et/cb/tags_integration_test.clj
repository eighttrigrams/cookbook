(ns et.cb.tags-integration-test
  "Tags over HTTP: who is sent them, who may write them, and the one asymmetry
  the owner chose deliberately.

  The shape of the feature is a pair of opposite facts, so most of these cases
  assert both halves at once rather than one each:

  | caller  | reads tags | writes tags | searches tags |
  |---------|------------|-------------|---------------|
  | owner   | yes        | yes         | yes           |
  | machine | yes        | yes         | yes           |
  | anon    | **no key** | 401         | **yes**       |

  A machine token acts in the owner's scope by design, so an agent is on the
  owner's side of this line — cookbook is an agentic memory store and a curated
  retrieval index is most of what an agent gets out of one. The boundary is around
  anonymous readers.

  The db layer's own coverage of the projection and of the tags-only save is in
  `et.cb.tags-db-test`; what is only testable here is the wire shape — that the
  key is genuinely absent from the JSON rather than dissoc'd somewhere in the
  middle — and that ?detail=full does not widen it."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

(defn- create!
  ([title] (create! title nil))
  ([title tags]
   (:body (POST-json "/api/recipes" (cond-> {:title title :useful_when (str "when " title)
                                             :description (str "body of " title)}
                                      tags (assoc :tags tags))))))

(defn- publish! [id]
  (h/API :post (str "/api/recipes/" id "/publish") {}))

(defn- anon
  "A request carrying neither a token nor the dev skip-logins header — the only
  way to see what a visitor sees."
  [method path]
  (h/with-real-auth (h/API method path {:anonymous? true})))

(defn- machine [method path & [body]]
  (h/API method path (cond-> {:token (h/machine-token-for h/*user-id*)}
                       body (assoc :body body))))

(defn- history-row-count [recipe-id]
  (:n (jdbc/execute-one! (db/get-conn h/*ds*)
        (sql/format {:select [[[:count :*] :n]] :from [:recipe_history]
                     :where [:= :recipe_id recipe-id]})
        db/jdbc-opts)))

;; ---------------------------------------------------------------------------
;; the owner

(deftest the-owner-writes-and-reads-them-over-http
  (let [created (create! "Sourdough" "bread baking")
        path (str "/api/recipes/" (:id created))]
    (is (= "bread baking" (:tags created)))
    (testing "on the lean listing as well as the full read — tags are part of the
              retrieval index, which is what they are for"
      (is (= "bread baking" (:tags (first (:body (GET-json "/api/recipes"))))))
      (is (= "bread baking" (:tags (:body (GET-json path)))))
      (is (= "bread baking" (:tags (:body (GET-json (str path "?detail=full")))))))
    (testing "PUT edits them, and leaving the key out keeps them"
      (is (= "bread baking starter" (:tags (:body (PUT-json path {:tags "bread baking starter"})))))
      (is (= "bread baking starter" (:tags (:body (PUT-json path {:description "body v2"}))))))
    (testing "a create that says nothing about tags reads back untagged"
      (is (= "" (:tags (create! "Untagged")))))))

(deftest a-tags-only-put-writes-no-version-over-http
  (let [{:keys [id]} (create! "Sourdough")
        path (str "/api/recipes/" id)]
    (PUT-json path {:description "body v2"})
    (is (= 2 (:version (:body (GET-json path)))))
    (let [saved (:body (PUT-json path {:tags "bread baking"}))]
      (testing "the tags land"
        (is (= "bread baking" (:tags saved)))
        (is (= "bread baking" (:tags (:body (GET-json path))))))
      (testing "and the version ladder is untouched — no bump, no history row"
        (is (= 2 (:version saved)))
        (is (= 1 (history-row-count id)))
        (let [{:keys [versions total]} (:body (GET-json (str path "/versions")))]
          (is (= 2 total))
          (is (= [2 1] (map :version versions)))
          (testing "and no entry carries tags: a version is the content"
            (is (every? #(false? (contains? % :tags)) versions))))))
    (testing "the card's provenance split still sums to the version, which a tag
              change quietly counting as a `ui` version would have broken"
      (let [row (first (:body (GET-json "/api/recipes")))]
        (is (= 2 (:version row)))
        (is (= 2 (+ (:machine_versions row) (:ui_versions row)
                    (:unrecorded_versions row))))))))

;; ---------------------------------------------------------------------------
;; the machine: on the owner's side of this line

(deftest a-machine-caller-reads-and-writes-tags
  (let [created (:body (machine :post "/api/recipes"
                                {:title "Filed by an agent" :tags "agentic memory"}))
        id (:id created)
        path (str "/api/recipes/" id)]
    (testing "it writes them on create"
      (is (= "agentic memory" (:tags created))))
    (testing "it reads them back, lean and full"
      (is (= "agentic memory" (:tags (:body (machine :get path)))))
      (is (= "agentic memory" (:tags (:body (machine :get (str path "?detail=full"))))))
      (is (= "agentic memory" (:tags (first (:body (machine :get "/api/recipes")))))))
    (testing "and it edits them"
      (is (= "agentic memory retrieval"
             (:tags (:body (machine :put path {:tags "agentic memory retrieval"}))))))
    (testing "it searches them, like everybody"
      (is (= [id] (map :id (:body (machine :get "/api/recipes?search=retrie"))))))
    (testing "the owner sees what the agent filed — one shelf, not two"
      (is (= "agentic memory retrieval" (:tags (:body (GET-json path))))))))

;; ---------------------------------------------------------------------------
;; the visitor: both halves of the decision

(deftest a-visitor-is-sent-no-tags-key-anywhere
  (let [{:keys [id]} (create! "Signed" "the owner's own filing")]
    (publish! id)
    (let [listed (first (:body (anon :get "/api/recipes")))
          one (:body (anon :get (str "/api/recipes/" id)))
          full (:body (anon :get (str "/api/recipes/" id "?detail=full")))]
      (testing "the published recipe is served, so this is a projection and not
                a 404 passing for privacy"
        (is (= id (:id listed)))
        (is (= "Signed" (:title one))))
      (testing "and none of the three responses has a tags key — absent, not
                empty: an empty string would say 'untagged', which is itself a
                fact about the owner's filing"
        (doseq [resp [listed one full]]
          (is (false? (contains? resp :tags)))))
      (testing "?detail=full still widens the description, because verbosity and
                privacy are different axes — this is the field where the
                publish latch stopped being the whole boundary"
        (is (= "body of Signed" (:description full)))
        (is (false? (contains? full :tags)))))))

(deftest an-anonymous-search-matches-a-tag-and-the-answer-still-hides-it
  ;; **The owner's decision, pinned both ways in one test.** He was asked and
  ;; chose it: "its ok in any case, to match on tags as well, even on published
  ;; articles. we only dont show them" — because "this way we have a uniform
  ;; expectation about search hits". One search behaves one way; a term returns
  ;; the same recipes whoever asks. Do not "fix" this.
  (let [{:keys [id]} (create! "Signed" "sekrit filing")
        {plain :id} (create! "Plain")]
    (publish! id)
    (publish! plain)
    (testing "the hit comes from the tags and from nowhere else: the term is in
              no title, useful-when or body, and the untagged recipe beside it —
              published too, and newer — is not found by the same search"
      (is (= [id] (map :id (:body (GET-json "/api/recipes?search=sekrit")))))
      (is (false? (contains? (set (map :id (:body (GET-json "/api/recipes?search=sekrit"))))
                             plain))))
    (let [hits (:body (anon :get "/api/recipes?search=sekrit"))]
      (testing "an anonymous search for it finds the recipe — the searched
                columns do not depend on who is asking"
        (is (= [id] (map :id hits))))
      (testing "and the row it returns still carries no tags key: presence is
                testable, the values are not readable"
        (is (false? (contains? (first hits) :tags)))))
    (testing "the same holds with the terms split across the two columns"
      (is (= [id] (map :id (:body (anon :get "/api/recipes?search=sign+sekrit"))))))
    (testing "and it never reaches past the latch: an unpublished recipe with the
              same tag stays the owner's, so the uniform search is a narrowing
              inside the scope rather than a way around it"
      (let [{drafted :id} (create! "Draft" "sekrit filing")]
        (is (= [id] (map :id (:body (anon :get "/api/recipes?search=sekrit"))))
            "the draft is newer, so a leak would have put it first")
        (is (= #{id drafted}
               (set (map :id (:body (GET-json "/api/recipes?search=sekrit"))))))))))

(deftest an-anonymous-caller-cannot-write-tags
  (let [{:keys [id]} (create! "Signed" "the owner's own filing")]
    (publish! id)
    (h/with-real-auth
      (is (= 401 (:status (h/API :put (str "/api/recipes/" id)
                                 {:anonymous? true :body {:tags "not yours"}}))))
      (is (= 401 (:status (h/API :post "/api/recipes"
                                 {:anonymous? true :body {:title "x" :tags "y"}})))))
    (is (= "the owner's own filing" (:tags (:body (GET-json (str "/api/recipes/" id))))))))

;; ---------------------------------------------------------------------------
;; the catalogue an agent reads before it calls anything

(deftest the-tag-rules-are-in-describe
  (let [entries (:body (GET-json "/api/describe"))
        doc-for (fn [method path]
                  (:doc (first (filter #(and (= path (:path %)) (= method (:method %)))
                                       entries))))]
    (testing "the listing says tags are searched"
      (is (re-find #"(?i)tags" (doc-for "GET" "/api/recipes"))))
    (testing "and that a visitor is not sent them"
      (is (re-find #"(?i)no `?tags`? key|not sent|only to the owner"
                   (doc-for "GET" "/api/recipes"))))
    (testing "the single read says the same, since ?detail=full is where a caller
              would expect to be given everything"
      (is (re-find #"(?i)tags" (doc-for "GET" "/api/recipes/:id"))))
    (testing "and the two writes say tags are writable and not versioned"
      (is (re-find #"(?i)tags" (doc-for "POST" "/api/recipes")))
      (is (re-find #"(?i)tags" (doc-for "PUT" "/api/recipes/:id")))
      (is (re-find #"(?i)not versioned|no version" (doc-for "PUT" "/api/recipes/:id"))))))

(ns et.cb.publish-latch-integration-test
  "The publish latch over HTTP: what sets it, that it only ever goes one way,
  and what an anonymous visitor can see through it.

  Publishing is the whole privacy boundary of this app — there is no other gate
  — so the visitor cases are written out as a matrix rather than illustrated."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json DELETE-json]]))

(use-fixtures :each h/with-integration-db)

(defn- create! [title]
  (:body (POST-json "/api/recipes" {:title title
                                    :useful_when (str "when " title)
                                    :description (str "body of " title)})))

(defn- publish! [id]
  (h/API :post (str "/api/recipes/" id "/publish") {}))

(defn- anon
  "A request carrying neither a token nor the dev skip-logins header — the only
  way to see what a visitor sees."
  [method path]
  (h/with-real-auth (h/API method path {:anonymous? true})))

(defn- ids-in [resp]
  (set (map :id (:body resp))))

(defn- sql-exec! [statement]
  (jdbc/execute-one! (db/get-conn h/*ds*) (sql/format statement)))

(defn- history-row-count [recipe-id]
  (:n (jdbc/execute-one! (db/get-conn h/*ds*)
        (sql/format {:select [[[:count :*] :n]] :from [:recipe_history]
                     :where [:= :recipe_id recipe-id]})
        db/jdbc-opts)))

(deftest publishing-is-one-way-and-idempotent
  (let [{:keys [id]} (create! "Sourdough")
        first-publish (publish! id)]
    (testing "the first publish sets the latch and stamps it"
      (is (= 200 (:status first-publish)))
      (is (= 1 (:published (:body first-publish))))
      (is (some? (:published_at (:body first-publish)))))

    (testing "publishing is not a content change: no version bump, no history"
      (is (= 1 (:version (:body first-publish))))
      (is (= 0 (history-row-count id)))
      (is (= 1 (:total (:body (GET-json (str "/api/recipes/" id "/versions")))))))

    ;; datetime('now') is second-resolution, so a second publish in the same
    ;; second would leave the stamp looking untouched either way. Put a
    ;; distinguishable value in first.
    (sql-exec! {:update :recipes :set {:published_at "2020-01-01 00:00:00"}
                :where [:= :id id]})

    (testing "publishing again is a 200 no-op and published_at does not move"
      (let [again (publish! id)]
        (is (= 200 (:status again)))
        (is (= 1 (:published (:body again))))
        (is (= "2020-01-01 00:00:00" (:published_at (:body again))))
        (is (= "2020-01-01 00:00:00"
               (:published_at (:body (GET-json (str "/api/recipes/" id))))))
        (is (= 1 (:version (:body again))))
        (is (= 0 (history-row-count id)))))

    (testing "an unknown id is a 404, published or not"
      (is (= 404 (:status (publish! 9999)))))))

(deftest only-the-publish-route-sets-published
  (let [created (:body (POST-json "/api/recipes"
                                  {:title "Sneaky" :published 1
                                   :published_at "2020-01-01 00:00:00"}))
        id (:id created)
        path (str "/api/recipes/" id)]
    (testing "POST cannot carry it in"
      (is (= 0 (:published created)))
      (is (nil? (:published_at created))))
    (testing "nor can PUT"
      (PUT-json path {:published 1 :published_at "2020-01-01 00:00:00" :description "x"})
      (let [after (:body (GET-json path))]
        (is (= 0 (:published after)))
        (is (nil? (:published_at after)))))
    (testing "the latch route does, and then PUT cannot take it back off"
      (publish! id)
      (PUT-json path {:published 0 :published_at nil :description "y"})
      (let [after (:body (GET-json path))]
        (is (= 1 (:published after)))
        (is (some? (:published_at after)))))))

(deftest there-is-no-unpublish
  (let [{:keys [id]} (create! "Signed")]
    (publish! id)
    (testing "no route answers to the idea, refusing or otherwise"
      (is (= 404 (:status (h/API :post (str "/api/recipes/" id "/unpublish") {}))))
      (is (= 404 (:status (DELETE-json (str "/api/recipes/" id "/publish"))))))
    (testing "and the API catalogue offers none"
      (let [entries (:body (GET-json "/api/describe"))]
        (is (empty? (filter #(re-find #"unpublish" (:path %)) entries)))
        (is (empty? (filter #(re-find #"(?i)unpublish" (:name %)) entries)))))
    (testing "the recipe is still published after all that"
      (is (= 1 (:published (:body (GET-json (str "/api/recipes/" id)))))))))

(deftest the-publish-route-is-in-describe
  (let [entries (:body (GET-json "/api/describe"))
        publish (first (filter #(= "/api/recipes/:id/publish" (:path %)) entries))]
    (is (some? publish))
    (is (= "POST" (:method publish)))
    (testing "and says the two things a caller has to know"
      (is (re-find #"(?i)no unpublish" (:doc publish)))
      (is (re-find #"(?i)idempotent|no-op" (:doc publish))))))

;; ---------------------------------------------------------------------------
;; the visitor matrix

(deftest owner-sees-everything-he-owns
  (let [{drafted :id} (create! "Draft")
        {signed :id} (create! "Signed")]
    (publish! signed)
    (testing "both are in his listing"
      (is (= #{drafted signed} (ids-in (GET-json "/api/recipes")))))
    (testing "both answer a get, and ?detail=full gives all three fields"
      (doseq [[id title] [[drafted "Draft"] [signed "Signed"]]]
        (let [full (:body (GET-json (str "/api/recipes/" id "?detail=full")))]
          (is (= title (:title full)))
          (is (= (str "when " title) (:useful_when full)))
          (is (= (str "body of " title) (:description full))))))))

(deftest a-visitor-sees-published-recipes-only
  (let [{drafted :id} (create! "Draft")
        {signed :id} (create! "Signed")]
    (publish! signed)

    (testing "the draft is absent from a visitor's listing — not redacted in it"
      (let [ids (ids-in (anon :get "/api/recipes"))]
        (is (contains? ids signed))
        (is (false? (contains? ids drafted))))
      (testing "and no title of it leaks either"
        (is (= ["Signed"] (map :title (:body (anon :get "/api/recipes")))))))

    (testing "a visitor asking for the draft by id gets the same 404 as for an
              id that does not exist"
      (let [missing (anon :get "/api/recipes/999999")
            hidden (anon :get (str "/api/recipes/" drafted))]
        (is (= 404 (:status hidden)))
        (is (= (:body missing) (:body hidden))))
      (is (= 404 (:status (anon :get (str "/api/recipes/" drafted "?detail=full"))))))

    (testing "a search cannot widen what a visitor sees"
      (is (empty? (:body (anon :get "/api/recipes?search=Draft"))))
      (is (= #{signed} (ids-in (anon :get "/api/recipes?search=Signed")))))

    (testing "the published one is lean by default for a visitor too"
      (let [lean (:body (anon :get (str "/api/recipes/" signed)))]
        (is (= "Signed" (:title lean)))
        (is (false? (contains? lean :description))))
      (is (every? #(false? (contains? % :description))
                  (:body (anon :get "/api/recipes")))))

    (testing "?detail=full shows a visitor all three fields of a published
              recipe — the collapse is about verbosity, the privacy boundary is
              the latch"
      (let [full (:body (anon :get (str "/api/recipes/" signed "?detail=full")))]
        (is (= "Signed" (:title full)))
        (is (= "when Signed" (:useful_when full)))
        (is (= "body of Signed" (:description full)))))

    (testing "the version history is the owner's, published or not"
      (is (= 200 (:status (GET-json (str "/api/recipes/" signed "/versions")))))
      (is (= 404 (:status (anon :get (str "/api/recipes/" signed "/versions")))))
      (is (= 404 (:status (anon :get (str "/api/recipes/" drafted "/versions"))))))))

(deftest a-visitor-is-not-the-nil-owner
  (let [{drafted :id} (create! "Draft")]
    ;; A nil-owner row is what dev's admin writes, and `user-id-where-clause`
    ;; turns a missing user-id into `user_id IS NULL` — so a visitor routed
    ;; through it would be served exactly these rows instead of an error.
    (sql-exec! {:update :recipes :set {:user_id nil} :where [:= :id drafted]})
    (let [{signed :id} (create! "Signed")]
      (sql-exec! {:update :recipes :set {:user_id nil} :where [:= :id signed]})
      (sql-exec! {:update :recipes :set {:published 1
                                         :published_at "2020-01-01 00:00:00"}
                  :where [:= :id signed]})
      (testing "the unpublished nil-owner recipe stays out of a visitor's listing"
        (let [ids (ids-in (anon :get "/api/recipes"))]
          (is (false? (contains? ids drafted)))
          (is (contains? ids signed))))
      (testing "and out of a get"
        (is (= 404 (:status (anon :get (str "/api/recipes/" drafted)))))
        (is (= 200 (:status (anon :get (str "/api/recipes/" signed)))))))))

(deftest a-visitor-cannot-write
  (let [{drafted :id} (create! "Draft")
        {signed :id} (create! "Signed")
        {owners :id} (create! "The owner's")]
    (publish! signed)
    (h/with-prod-app
      (testing "the production chain refuses every write from an anonymous caller"
        (is (= 401 (:status (h/API :post "/api/recipes"
                                   {:anonymous? true :body {:title "By nobody"}}))))
        (is (= 401 (:status (h/API :put (str "/api/recipes/" drafted)
                                   {:anonymous? true :body {:title "Renamed"}}))))
        (is (= 401 (:status (h/API :delete (str "/api/recipes/" drafted)
                                   {:anonymous? true}))))
        (is (= 401 (:status (h/API :post (str "/api/recipes/" drafted "/publish")
                                   {:anonymous? true}))))
        (is (= 401 (:status (h/API :post (str "/api/recipes/" signed "/publish")
                                   {:anonymous? true})))))
      (testing "while the owner's token goes through the same chain, so those
                401s are about the caller and not a missing route"
        (is (= 200 (:status (h/API :post (str "/api/recipes/" owners "/publish")
                                   {:token (h/token-for h/*user-id*)}))))))
    (testing "and none of the refusals wrote"
      (let [after (:body (GET-json (str "/api/recipes/" drafted)))]
        (is (= "Draft" (:title after)))
        (is (= 0 (:published after)))
        (is (= 1 (:version after))))
      (is (= #{drafted signed owners} (ids-in (GET-json "/api/recipes")))))))

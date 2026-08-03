(ns et.cb.publish-latch-integration-test
  "The publish latch over HTTP: what sets it, and that it only ever goes one
  way."
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

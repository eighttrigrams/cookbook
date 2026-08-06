(ns et.cb.view-count-integration-test
  "How often a Recipe was **consumed**, over HTTP.

  The owner's definition is a triple and the first test is that triple: asking for
  a Recipe's description counts, asking for the same Recipe leanly does not, and
  listing the shelf does not — 'not listing the thing, but actually asking for
  that whole Recipe including its description; which proves that some user
  actually seem to have used it'.

  `GET /api/recipes/:id?detail=full` is the only request in this API that returns
  one Recipe's description, which is what lets one counter at one endpoint cover
  the UI and the machine user uniformly, with no client telling the server what to
  count. The tests below are written to fail if the counting ever moves off it:
  onto the listing, onto a write path, or onto a 404."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

(defn- create! [title]
  (:body (POST-json "/api/recipes"
                    {:title title :useful_when "when testing" :description "body v1"})))

(defn- views
  "Off the listing, which is where the card reads it too."
  [id]
  (:view_count (first (filter #(= id (:id %)) (:body (GET-json "/api/recipes"))))))

(deftest a-full-read-counts-a-lean-read-does-not-and-a-listing-does-not
  (let [id (:id (create! "Sourdough"))
        path (str "/api/recipes/" id)]
    (is (= 0 (views id)) "a Recipe nobody has opened")

    (testing "?detail=full is the read that counts — it is the only request that
              hands back this Recipe's description"
      (is (= "body v1" (:description (:body (GET-json (str path "?detail=full"))))))
      (is (= 1 (views id)))
      (GET-json (str path "?detail=full"))
      (GET-json (str path "?detail=full"))
      (is (= 3 (views id))))

    (testing "a lean read of the same id does not: it answers with the retrieval
              index and no body at all, which is a scan and not a use"
      (let [before (views id)
            lean (:body (GET-json path))]
        (is (false? (contains? lean :description)))
        (is (= before (views id)))))

    (testing "and any other ?detail is lean, so it does not count either"
      (let [before (views id)]
        (GET-json (str path "?detail=lean"))
        (GET-json (str path "?detail=true"))
        (is (= before (views id)))))

    (testing "listing the shelf does not count, at any ?detail — including the one
              that widens the projection, because a listing is still the scan the
              owner said was not a use"
      (let [before (views id)]
        (GET-json "/api/recipes")
        (GET-json "/api/recipes?detail=full")
        (GET-json "/api/recipes?search=sour")
        (is (= before (views id)))))

    (testing "nor does reading the version history"
      (let [before (views id)]
        (GET-json (str path "/versions"))
        (is (= before (views id)))))))

(deftest the-response-carries-the-count-as-it-was-before-this-read
  ;; Which of the two readings the handler took, pinned rather than left to be
  ;; assumed: the row that was read is the row that is returned, so a caller is
  ;; told how many reads happened *before* its own. A re-read after the UPDATE
  ;; would be a second SELECT on the hottest path to tell the caller something it
  ;; already knows about itself.
  (let [id (:id (create! "Focaccia"))
        path (str "/api/recipes/" id "?detail=full")]
    (is (= 0 (:view_count (:body (GET-json path)))) "the first reader sees 0")
    (is (= 1 (:view_count (:body (GET-json path)))) "the second sees the first's")
    (is (= 2 (views id)) "and the listing has both")))

(deftest a-404-does-not-count
  (testing "an id that does not exist has nothing to count against, and the
            request must not create anything either"
    (is (= 404 (:status (GET-json "/api/recipes/9999?detail=full"))))
    (is (empty? (:body (GET-json "/api/recipes")))))

  (let [id (:id (create! "Private by default"))]
    (testing "and an unpublished Recipe a visitor asked for is the same 404 —
              'as good as absent', so it is as good as unread"
      (h/with-real-auth
        (is (= 404 (:status (h/API :get (str "/api/recipes/" id "?detail=full")
                                   {:anonymous? true}))))
        (is (= 404 (:status (h/API :get (str "/api/recipes/" id) {:anonymous? true})))))
      (is (= 0 (views id))))

    (testing "a machine token refused by nothing here still counts, so the 404 is
              the reason above and not the caller"
      (let [token (h/machine-token-for h/*user-id*)]
        (h/API :get (str "/api/recipes/" id "?detail=full") {:token token})
        (is (= 1 (views id)))))))

(deftest the-machine-user-and-the-owner-count-the-same-counter
  ;; The 'OR via machine user' half of the request. The code path looks shared;
  ;; this is what says it is.
  (let [id (:id (create! "Sourdough"))
        path (str "/api/recipes/" id "?detail=full")
        token (h/machine-token-for h/*user-id*)]
    (GET-json path)
    (is (= 1 (views id)) "the owner's read")
    (is (= "body v1" (:description (:body (h/API :get path {:token token}))))
        "and the agent gets the body it asked for")
    (is (= 2 (views id)) "into the same number, not one of its own")))

(deftest a-visitor-of-a-published-recipe-counts-too
  ;; Every audience's read counts: the number answers 'was this actually used',
  ;; and a published Recipe read by a stranger was used. Stated here as a
  ;; decision, since it is what makes a published Recipe's count — and so its
  ;; place on the shelf — inflatable by anyone who can reach the endpoint.
  (let [id (:id (create! "Sourdough"))
        path (str "/api/recipes/" id "?detail=full")]
    (POST-json (str "/api/recipes/" id "/publish") {})
    (h/with-real-auth
      (testing "the flip took: a tokenless listing is the published shelf"
        (is (= [id] (map :id (:body (h/API :get "/api/recipes" {:anonymous? true}))))))
      (is (= "body v1" (:description (:body (h/API :get path {:anonymous? true})))))
      (is (= 1 (views id))))))

(deftest the-lean-projection-gained-a-column-and-promised-nothing-else
  ;; `view_count` went into `lean-select-columns`, which is also the visitor's
  ;; projection — so this checks what else that projection is promising rather
  ;; than only that the new key arrived.
  (let [id (:id (create! "Sourdough"))]
    (PUT-json (str "/api/recipes/" id) {:tags "bread baking"})
    (POST-json (str "/api/recipes/" id "/publish") {})
    (h/API :get (str "/api/recipes/" id "?detail=full") {})

    (testing "the owner's lean row carries the count and his tags"
      (let [row (first (:body (GET-json "/api/recipes")))]
        (is (= 1 (:view_count row)))
        (is (= "bread baking" (:tags row)))
        (is (false? (contains? row :description)))))

    (h/with-real-auth
      (let [[row :as all] (:body (h/API :get "/api/recipes" {:anonymous? true}))]
        (is (= 1 (count all)) "the visitor is looking at the published Recipe")
        (testing "a visitor's row is ranked by the count, so it carries it"
          (is (= 1 (:view_count row))))
        (testing "and it still carries no tags, no scopes and no body — the new
                  column widened the projection by exactly one thing"
          (is (false? (contains? row :tags)))
          (is (false? (contains? row :scopes)))
          (is (false? (contains? row :description))))))))

(deftest the-write-paths-do-not-count-over-http
  ;; The guard against the counter migrating into `db.recipe/get-recipe`, which
  ;; every one of these goes through. Across each write, not merely after them
  ;; all, so a failure names the write that counted.
  (let [id (:id (create! "Ciabatta"))
        path (str "/api/recipes/" id)]
    (is (= 0 (views id)))

    (PUT-json path {:description "body v2"})
    (is (= 0 (views id)) "a save")

    (PUT-json path {:tags "bread"})
    (is (= 0 (views id)) "a tags-only save")

    (PUT-json path {:description "body v2"})
    (is (= 0 (views id)) "a save that changes nothing")

    (PUT-json path {:description "x" :modified_at "1999-01-01 00:00:00"})
    (is (= 0 (views id)) "a 409, which reads the row twice on its way out")

    (POST-json (str path "/publish") {})
    (is (= 0 (views id)) "a publish")

    (testing "and one real read still moves it, so the assertions above were not
              passing because the counter is broken"
      (GET-json (str path "?detail=full"))
      (is (= 1 (views id))))))

(deftest the-counting-rule-is-published-where-an-agent-will-read-it
  (let [doc (:doc (first (filter #(and (= "/api/recipes/:id" (:path %)) (= "GET" (:method %)))
                                 (h/describe-endpoints))))]
    (is (re-find #"(?i)view_count" doc))
    (is (re-find #"(?i)listing" doc) "and that a listing is not one")))

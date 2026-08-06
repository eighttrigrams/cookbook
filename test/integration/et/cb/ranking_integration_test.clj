(ns et.cb.ranking-integration-test
  "The shelf's order over HTTP — 'in the ui and via machine listings', which is
  one listing endpoint and therefore one order.

  The db-layer namespace pins the arithmetic. What is here is that the order
  survives the round trip for every audience, that reading a Recipe through the
  API is what moves it up, and that the endpoint's own documentation says so —
  `/api/describe` serves these docstrings, so a stale one is the API lying to the
  agent it is written for."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json]]))

(use-fixtures :each h/with-integration-db)

(defn- create! [title]
  (:body (POST-json "/api/recipes" {:title title :useful_when "" :description "v1"})))

(defn- read-times! [id n]
  (dotimes [_ n] (GET-json (str "/api/recipes/" id "?detail=full"))))

(defn- shelf [] (map :title (:body (GET-json "/api/recipes"))))

(deftest reading-a-recipe-through-the-api-moves-it-up-the-shelf
  (let [older (:id (create! "Written first"))
        newer (:id (create! "Written second"))]
    (is (= ["Written second" "Written first"] (shelf))
        "the tie falls back to the old order, newest first")
    (read-times! older 1)
    (testing "one full read is enough to pass a Recipe written afterwards"
      (is (= ["Written first" "Written second"] (shelf))))
    (testing "and a lean read of the other one does not pull it back"
      (GET-json (str "/api/recipes/" newer))
      (GET-json "/api/recipes")
      (is (= ["Written first" "Written second"] (shelf))))
    (testing "while a full read of it does — two reads to one"
      (read-times! newer 2)
      (is (= ["Written second" "Written first"] (shelf))))))

(deftest the-machine-listing-is-the-same-shelf-in-the-same-order
  ;; 'in the ui and via machine listings' — one endpoint, so this is a check that
  ;; nothing about the token changes the query, not a second implementation.
  (let [a (:id (create! "Kept coming back to"))
        _ (create! "Written since")
        token (h/machine-token-for h/*user-id*)]
    (read-times! a 2)
    (let [machine-rows (:body (h/API :get "/api/recipes" {:token token}))]
      (is (= ["Kept coming back to" "Written since"] (map :title machine-rows)))
      (is (= (shelf) (map :title machine-rows)))
      (testing "and the agent is told the counts it is being ranked by"
        (is (= [2 0] (map :view_count machine-rows)))))))

(deftest a-visitors-shelf-is-ranked-too
  (let [a (:id (create! "Published, and read"))
        b (:id (create! "Published, written later"))]
    (POST-json (str "/api/recipes/" a "/publish") {})
    (POST-json (str "/api/recipes/" b "/publish") {})
    (h/with-real-auth
      (testing "the flip took — this is a genuinely anonymous caller"
        (is (= 2 (count (:body (h/API :get "/api/recipes" {:anonymous? true}))))))
      (let [before (map :title (:body (h/API :get "/api/recipes" {:anonymous? true})))]
        (is (= ["Published, written later" "Published, and read"] before)))
      (h/API :get (str "/api/recipes/" a "?detail=full") {:anonymous? true})
      (testing "a visitor's own read counts and reorders the visitor's own shelf"
        (is (= ["Published, and read" "Published, written later"]
               (map :title (:body (h/API :get "/api/recipes" {:anonymous? true})))))))))

(deftest a-save-still-moves-a-recipe-up-a-little
  ;; The 0.3 term, over HTTP: a version is worth something, and a filing-only
  ;; save is not a version and so is worth nothing here — it only moves the
  ;; tiebreaker.
  (let [a (:id (create! "Edited"))
        b (:id (create! "Left alone"))]
    (PUT-json (str "/api/recipes/" b) {:description "v2"})
    (is (= ["Left alone" "Edited"] (shelf)) "b is on v2 and a is on v1")
    (PUT-json (str "/api/recipes/" a) {:description "v2"})
    (PUT-json (str "/api/recipes/" a) {:description "v3"})
    (is (= ["Edited" "Left alone"] (shelf)) "a is on v3")
    (testing "a tags-only save makes no version, so it does not move the score"
      (PUT-json (str "/api/recipes/" b) {:tags "bread"})
      (is (= ["Edited" "Left alone"] (shelf))))))

(deftest the-ranking-is-published-where-an-agent-will-read-it
  ;; `/api/describe` serves the handler docstring, so this docstring *is* what an
  ;; agent is told about the ordering. If it still said most-recent-first the API
  ;; would be lying to its only documented consumer.
  (let [doc (:doc (first (filter #(and (= "/api/recipes" (:path %)) (= "GET" (:method %)))
                                 (h/describe-endpoints))))]
    (is (re-find #"(?i)weighted sum" doc) "that it is a weighted sum")
    (is (re-find #"0\.7" doc) "and what the weights are")
    (is (re-find #"0\.3" doc))
    (is (re-find #"view_count" doc) "and of what")
    (is (re-find #"version" doc))
    (is (not (re-find #"(?i)most recently saved first" doc))
        "and not the order it used to be")))

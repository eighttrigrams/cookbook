(ns et.cb.recipes-integration-test
  "The Recipe API over HTTP: the lean default, ?detail=full, the version ladder
  and the version listing."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.integration-helpers :as h :refer [GET-json POST-json PUT-json DELETE-json]]))

(use-fixtures :each h/with-integration-db)

(defn- create! [title]
  (:body (POST-json "/api/recipes"
                    {:title title :useful_when "when testing" :description "body v1"})))

(deftest create-read-and-list
  (let [created (create! "Sourdough")]
    (is (= 1 (:version created)))
    (is (= "body v1" (:description created)))
    (testing "the listing is lean — no description key at all"
      (let [[recipe :as all] (:body (GET-json "/api/recipes"))]
        (is (= 1 (count all)))
        (is (= "Sourdough" (:title recipe)))
        (is (= "when testing" (:useful_when recipe)))
        (is (false? (contains? recipe :description)))))
    (testing "?detail=full adds it"
      (let [[recipe] (:body (GET-json "/api/recipes?detail=full"))]
        (is (= "body v1" (:description recipe)))))
    (testing "a plain get is lean, ?detail=full is not"
      (let [id (:id created)]
        (is (false? (contains? (:body (GET-json (str "/api/recipes/" id))) :description)))
        (is (= "body v1" (:description (:body (GET-json (str "/api/recipes/" id "?detail=full"))))))))
    (testing "any other value of ?detail stays lean"
      (is (false? (contains? (first (:body (GET-json "/api/recipes?detail=lean"))) :description)))
      (is (false? (contains? (first (:body (GET-json "/api/recipes?detail=true"))) :description))))))

(deftest a-blank-title-is-refused
  (is (= 400 (:status (POST-json "/api/recipes" {:title "   "}))))
  (is (= 400 (:status (POST-json "/api/recipes" {:useful_when "no title"}))))
  (let [id (:id (create! "Fine"))]
    (is (= 400 (:status (PUT-json (str "/api/recipes/" id) {:title ""}))))))

(deftest the-version-ladder-over-http
  (let [id (:id (create! "Sourdough"))
        path (str "/api/recipes/" id)]
    (PUT-json path {:description "body v2"})
    (PUT-json path {:description "body v3"})
    (testing "a save that changes nothing does not move the version"
      (is (= 3 (:version (:body (PUT-json path {:description "body v3"}))))))
    (let [{:keys [versions total]} (:body (GET-json (str path "/versions")))]
      (is (= 3 total))
      (is (= [3 2 1] (map :version versions)))
      (is (= ["body v3" "body v2" "body v1"] (map :description versions)))
      (is (true? (:current (first versions))))
      (testing "the newest entry is the current row"
        (is (= (:description (:body (GET-json (str path "?detail=full"))))
               (:description (first versions))))))))

(deftest stale-modified-at-is-a-409
  (let [created (create! "Baguette")
        path (str "/api/recipes/" (:id created))]
    (is (= 409 (:status (PUT-json path {:description "x"
                                        :modified_at "1999-01-01 00:00:00"}))))
    (testing "the refusal did not write"
      (is (= 1 (:version (:body (GET-json path)))))
      (is (= "body v1" (:description (:body (GET-json (str path "?detail=full")))))))
    (testing "the current modified_at goes through"
      (is (= 200 (:status (PUT-json path {:description "x"
                                          :modified_at (:modified_at created)})))))))

(deftest unknown-ids-are-404
  (is (= 404 (:status (GET-json "/api/recipes/9999"))))
  (is (= 404 (:status (PUT-json "/api/recipes/9999" {:title "x"}))))
  (is (= 404 (:status (DELETE-json "/api/recipes/9999"))))
  (is (= 404 (:status (GET-json "/api/recipes/9999/versions")))))

(deftest delete-removes-it
  (let [id (:id (create! "Ciabatta"))]
    (PUT-json (str "/api/recipes/" id) {:description "v2"})
    (is (= 200 (:status (DELETE-json (str "/api/recipes/" id)))))
    (is (= 404 (:status (GET-json (str "/api/recipes/" id)))))
    (is (empty? (:body (GET-json "/api/recipes"))))))

(deftest search-narrows-over-the-title
  (create! "Sourdough")
  (:body (POST-json "/api/recipes" {:title "Risotto" :useful_when "rice night"
                                    :description "sourdough is not involved"}))
  (is (= ["Sourdough"] (map :title (:body (GET-json "/api/recipes?search=sourdough")))))
  (is (= ["Risotto"] (map :title (:body (GET-json "/api/recipes?search=riso")))))
  (testing "useful-when is not searched any more — `rice night` is Risotto's, and
            the whole listing now says no"
    (is (empty? (:body (GET-json "/api/recipes?search=rice")))))
  (testing "and not over the body, which a lean read never loads"
    (is (= 1 (count (:body (GET-json "/api/recipes?search=sourdough")))))))

(defn- titles-found [query]
  (set (map :title (:body (GET-json (str "/api/recipes?search=" query))))))

(deftest search-is-a-word-prefix-match-over-http
  (create! "abc cde")
  (create! "ad cd")
  (create! "abcd")
  (create! "50 % rye")
  (testing "the owner's example, with the query string carrying the space either
            way it can be encoded"
    (is (= #{"abc cde"} (titles-found "ab%20cd")))
    (is (= #{"abc cde"} (titles-found "ab+cd"))))
  (testing "a prefix is not a substring, over HTTP as at the db layer: cd is a
            prefix of the word cde and of the word cd, but not of abcd"
    (is (= #{"abc cde" "ad cd"} (titles-found "cd"))))
  (testing "a % arrives as a %: it finds the title with one in it, and it is not
            a wildcard that drags in the other three"
    (is (= #{"50 % rye"} (titles-found "%25"))))
  (testing "the semantics are published where a caller will read them"
    (let [doc (:doc (first (filter #(and (= "/api/recipes" (:path %)) (= "GET" (:method %)))
                                   (:body (GET-json "/api/describe")))))]
      (is (re-find #"(?i)word-prefix" doc))
      (is (re-find #"(?i)title" doc)))))

(deftest recipes-are-in-describe
  (let [entries (:body (GET-json "/api/describe"))
        by-path (group-by :path entries)]
    (is (contains? by-path "/api/recipes"))
    (is (contains? by-path "/api/recipes/:id"))
    (is (contains? by-path "/api/recipes/:id/versions"))
    (is (contains? by-path "/api/recipes/:id/publish"))
    (is (= #{"GET" "POST"} (set (map :method (get by-path "/api/recipes")))))
    (is (= #{"GET" "PUT" "DELETE"} (set (map :method (get by-path "/api/recipes/:id")))))
    (testing "the lean rule is documented where an agent will read it"
      (is (re-find #"(?i)detail=full"
                   (:doc (first (filter #(and (= "/api/recipes" (:path %))
                                              (= "GET" (:method %)))
                                        entries))))))))

(deftest an-anonymous-visitor-sees-nothing-unpublished
  (create! "Private by default")
  (h/with-real-auth
    (testing "a recipe is private until it is published: absent, not redacted.
              The full visitor matrix lives in the publish-latch namespace."
      (is (empty? (:body (h/API :get "/api/recipes" {:anonymous? true})))))))

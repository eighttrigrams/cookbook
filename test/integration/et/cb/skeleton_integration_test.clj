(ns et.cb.skeleton-integration-test
  "The app boots and answers. Thin on purpose — the entity tests carry the
  weight. What is asserted here is the wiring that everything else assumes, plus
  the one absence this project is defined by."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.cb.integration-helpers :as h :refer [GET-json]]))

(use-fixtures :each h/with-integration-db)

(deftest serves-its-index
  (let [resp (h/API-raw :get "/" {})]
    (is (= 200 (:status resp)))
    (is (re-find #"(?i)<title>Cookbook</title>" (str (:body resp))))))

(deftest describe-lists-routes-and-nothing-else
  (let [resp (GET-json "/api/describe")
        paths (set (map :path (:body resp)))]
    (is (= 200 (:status resp)))
    (is (contains? paths "/api/describe"))
    (is (contains? paths "/api/auth/login"))
    (testing "every entry is a real route, not an incidentally-documented helper"
      (is (every? #(re-matches #"GET|POST|PUT|DELETE|PATCH" (:method %)) (:body resp)))
      (is (every? #(.startsWith ^String (:path %) "/") (:body resp))))))

(deftest no-recording-mode-gate
  (testing "cookbook has no machine-write gate — its absence is the feature"
    (is (nil? (find-ns 'et.cb.server.recording-mode)))
    (is (= 404 (:status (GET-json "/api/recording-mode"))))
    (is (= 404 (:status (h/API :post "/api/recording-mode/toggle" {}))))
    (is (empty? (filter #(re-find #"recording" (:path %))
                        (:body (GET-json "/api/describe")))))))

(ns et.cb.envelope-test
  "The server's half of the drift control — and it is a half rather than a third,
  because what the server knows about the seal is a prefix and a list of column
  names. It holds no key, so there is no ciphertext in here to match: what has to
  agree with the two clients is *which strings are sealed* and *which columns can
  be*.

  Both come out of `test/fixtures/seal-vectors.edn`, the same file
  `et.cb.seal-test` (ClojureScript, `make test-cljs`) and plurama-cli's
  `cookbook-seal-test` (`bb test`) read. Nothing here invents a value.

  **Why this matters more than it looks.** The publish guard is the last thing
  between a sealed Recipe and a public page, and there is no unpublish. If this
  process thought `ENC:V1:` were an envelope it would refuse publishes nobody
  needs refusing; if it thought `context` were not a prose column, a sealed
  `context` would ride out through the column the guard did not walk. Neither is
  discoverable from inside this namespace — only against the file the clients
  seal by."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [et.cb.envelope :as envelope]))

(def ^:private vectors-path
  "Relative to the repo root, which is where `clj -X:test` runs from."
  "test/fixtures/seal-vectors.edn")

(def ^:private fixture
  ;; **Fails rather than skips** when the file is missing, exactly as both client
  ;; suites do: a drift control you can silently not run is not one.
  (delay
    (let [f (io/file vectors-path)]
      (when-not (.exists f)
        (throw (ex-info (str "The seal fixture is the whole point of this namespace and it is not at "
                            vectors-path)
                        {:path vectors-path})))
      (edn/read-string (slurp f)))))

(deftest the-prefix-is-the-one-the-fixture-names
  (is (= (:prefix (:envelope @fixture)) envelope/prefix)))

(deftest sealed?-agrees-with-the-fixture-about-every-class-in-it
  (testing "every vector's ciphertext is sealed"
    (doseq [{:keys [name sealed]} (:vectors @fixture)]
      (is (true? (envelope/sealed? sealed)) name)))

  (testing "so is every envelope that carries the prefix and does not open —
            unreadable is unreadable, and it is what must not be published"
    (doseq [v (:unopenable @fixture)]
      (is (true? (envelope/sealed? v)) v)))

  (testing "and so is every tampered one"
    (doseq [{:keys [name sealed]} (:tamper @fixture)]
      (is (true? (envelope/sealed? sealed)) name)))

  (testing "a near miss is a plaintext — enc:v0:, ENC:V1:, a leading space. Mixed
            state is legal forever, so these are values a Recipe may hold"
    (doseq [v (:passthrough @fixture)]
      (is (false? (envelope/sealed? v)) v)))

  (testing "blank is never sealed, which is what keeps NULL apart from empty"
    (doseq [v (:blank @fixture)]
      (is (false? (envelope/sealed? v)) (pr-str v)))
    (is (false? (envelope/sealed? nil))))

  (testing "and neither is anything that is not a string: SQLite would take a
            number into a TEXT column, and a guard that threw on one would be a
            500 where a 400 was meant"
    (is (false? (envelope/sealed? 7)))
    (is (false? (envelope/sealed? :description)))
    (is (false? (envelope/sealed? ["enc:v1:x"])))))

(deftest the-prose-columns-are-the-ones-the-fixture-names
  (let [inventory (:sealed-columns @fixture)]
    (testing "twelve columns over the three tables a Recipe's prose lives in"
      (is (= 12 (reduce + (map count (vals envelope/prose-columns)))))
      (is (= (into {} (for [[table columns] inventory
                            :when (not= :scopes table)]
                        [table (mapv keyword columns)]))
             envelope/prose-columns)))

    (testing "`scopes` is in the fixture and deliberately not here: a published
              Recipe's Scopes stay the owner's, so a Scope's prose is never served
              to the audience publishing creates"
      (is (contains? inventory :scopes))
      (is (false? (contains? envelope/prose-columns :scopes))))

    (testing "title and tags are the search surface and are in no list anywhere"
      (doseq [[_ columns] envelope/prose-columns]
        (is (not-any? #{:title :tags} columns))))))

(deftest sealed-in-names-the-columns-a-publish-would-have-to-open
  (let [envelope-value (:sealed (first (:vectors @fixture)))]
    (is (= [:description] (envelope/sealed-in :recipes {:description envelope-value
                                                        :useful_when "in the clear"})))
    (is (= [:description :reason]
           (envelope/sealed-in :recipe_history {:description envelope-value
                                                :useful_when "clear"
                                                :reason envelope-value
                                                :context nil}))
        "in inventory order, and a nil column is not one of them")
    (is (= [] (envelope/sealed-in :recipes {:description "clear" :useful_when ""})))
    (testing "a row that does not carry a column at all — cookbook's projections
              are lean on purpose — contributes nothing"
      (is (= [] (envelope/sealed-in :recipes {:title "T" :version 3}))))))

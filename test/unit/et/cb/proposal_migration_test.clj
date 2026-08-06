(ns et.cb.proposal-migration-test
  "Migration 011: the `recipe_proposals` table, and the one constraint that carries
  the whole design.

  **At most one unresolved proposal per Recipe is said by a partial unique index.**
  That is what makes *so there are no merge conflicts* a property of the database
  rather than of whichever handler happens to run — so it is tested by trying to
  insert the second row directly, not by going through the write path. A handler
  check would pass this file's other tests and still lose a race."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.migrations :as migrations]))

(defn- temp-file-db [label]
  (let [dir (java.nio.file.Files/createTempDirectory
              label (into-array java.nio.file.attribute.FileAttribute []))
        ds (db/init-conn {:type :sqlite-file :path (str dir "/" label ".db")})]
    [ds (fn []
          (when-let [pc (:persistent-conn ds)] (.close pc))
          (doseq [f (reverse (file-seq (io/file (str dir))))] (.delete f)))]))

(defn- one [ds q] (jdbc/execute-one! (db/get-conn ds) (sql/format q) db/jdbc-opts))

(defn- tables [ds]
  (set (map :name (jdbc/execute! (db/get-conn ds)
                                 ["SELECT name FROM sqlite_master WHERE type = 'table'"]
                                 db/jdbc-opts))))

(defn- columns [ds table]
  (set (map :name (jdbc/execute! (db/get-conn ds) [(str "PRAGMA table_info(" table ")")]
                                 db/jdbc-opts))))

(defn- propose!
  "A proposal straight at the table, so a test can try what no handler would."
  [ds recipe-id & [{:keys [resolved-at resolution base-version]
                    :or {base-version 1}}]]
  (one ds {:insert-into :recipe_proposals
           :values [(cond-> {:recipe_id recipe-id :base_version base-version
                             :title "the agent's title"}
                      resolved-at (assoc :resolved_at resolved-at)
                      resolution (assoc :resolution resolution))]
           :returning [:id]}))

(deftest migration-011-adds-the-proposals-table-and-its-partial-index
  (let [[ds clean!] (temp-file-db "cb-proposals-up")]
    (try
      (is (contains? (tables ds) "recipe_proposals"))
      (is (= #{"id" "recipe_id" "user_id" "base_version" "title" "useful_when"
               "description" "created_at" "modified_at" "resolved_at" "resolution"}
             (columns ds "recipe_proposals")))
      (testing "three content fields and no filing: a proposal is a proposed
                *version*, and tags and Scopes are not versioned"
        (is (not (contains? (columns ds "recipe_proposals") "tags")))
        (is (not (contains? (columns ds "recipe_proposals") "scope_ids"))))
      (testing "and no `source`: only a machine ever proposes, so the column could
                only ever hold one answer — 007's argument about `category_type`"
        (is (not (contains? (columns ds "recipe_proposals") "source"))))
      (is (contains? (set (map :name (jdbc/execute! (db/get-conn ds)
                                       ["SELECT name FROM sqlite_master
                                          WHERE type = 'index'"]
                                       db/jdbc-opts)))
                     "idx_recipe_proposals_one_pending"))
      (testing "it touched nothing else — 010's constraint included"
        (is (contains? (tables ds) "recipe_events"))
        (is (re-find #"source\s+TEXT NOT NULL"
                     (:sql (one ds {:select [:sql] :from [:sqlite_master]
                                    :where [:= :name "recipes"]})))))
      (finally (clean!)))))

(deftest the-index-refuses-a-second-unresolved-proposal-for-one-recipe
  ;; **The test this file exists for**, and it goes through the table rather than a
  ;; handler on purpose: the claim is that the *database* holds the invariant, so a
  ;; handler check would be no evidence at all.
  (let [[ds clean!] (temp-file-db "cb-proposals-index")]
    (try
      (is (some? (propose! ds 1)) "the first pending proposal goes in")
      (testing "and a second one for the same Recipe is refused"
        (is (thrown? org.sqlite.SQLiteException (propose! ds 1))))
      (testing "while another Recipe's is unaffected — the constraint is per Recipe"
        (is (some? (propose! ds 2))))
      (testing "resolving the first frees the Recipe: the index is partial, which is
                also why resolved rows can be kept rather than deleted"
        (one ds {:update :recipe_proposals
                 :set {:resolved_at "2026-01-01 00:00:00" :resolution "dismissed"}
                 :where [:= :recipe_id 1]})
        (is (some? (propose! ds 1)))
        (testing "and now *that* one blocks the next"
          (is (thrown? org.sqlite.SQLiteException (propose! ds 1)))))
      (testing "any number of resolved rows can pile up for one Recipe, because none
                of them is in the index"
        (one ds {:update :recipe_proposals
                 :set {:resolved_at "2026-01-02 00:00:00" :resolution "approved"}
                 :where [:= :recipe_id 1]})
        (is (some? (propose! ds 1 {:resolved-at "2026-01-03 00:00:00"
                                   :resolution "dismissed"})))
        (is (some? (propose! ds 1 {:resolved-at "2026-01-04 00:00:00"
                                   :resolution "dismissed"})))
        (is (= 4 (:n (one ds {:select [[[:count :*] :n]] :from [:recipe_proposals]
                              :where [:= :recipe_id 1]})))))
      (finally (clean!)))))

(deftest the-resolution-check-accepts-two-words-and-nil
  (let [[ds clean!] (temp-file-db "cb-proposals-resolution")]
    (try
      (doseq [word ["approved" "dismissed"]]
        (is (some? (propose! ds 1 {:resolved-at "2026-01-01 00:00:00" :resolution word}))
            (str "'" word "' is one of the two answers he can give")))
      (testing "and nil is allowed, which is the third case that is not an answer:
                the Recipe was deleted, so `resolved_at` closes the proposal while
                `resolution` stays silent about a decision he never made"
        (is (some? (propose! ds 1 {:resolved-at "2026-01-01 00:00:00"}))))
      (testing "but an invented word is refused by the database"
        (is (thrown? org.sqlite.SQLiteException
              (propose! ds 1 {:resolved-at "2026-01-01 00:00:00" :resolution "merged"})))
        (is (thrown? org.sqlite.SQLiteException
              (propose! ds 1 {:resolved-at "2026-01-01 00:00:00" :resolution ""}))))
      (finally (clean!)))))

(deftest a-proposal-needs-a-title-and-a-base-version
  (let [[ds clean!] (temp-file-db "cb-proposals-notnull")]
    (try
      (testing "a version with no title is not a version"
        (is (thrown? org.sqlite.SQLiteException
              (one ds {:insert-into :recipe_proposals
                       :values [{:recipe_id 1 :base_version 1}]}))))
      (testing "and a proposal that does not say what it was written against cannot
                tell him whether it is stale"
        (is (thrown? org.sqlite.SQLiteException
              (one ds {:insert-into :recipe_proposals
                       :values [{:recipe_id 1 :title "no base"}]}))))
      (testing "the other two default to empty, exactly as they do on `recipes`"
        (let [{:keys [id]} (propose! ds 1)
              row (one ds {:select [:useful_when :description] :from [:recipe_proposals]
                           :where [:= :id id]})]
          (is (= "" (:useful_when row)))
          (is (= "" (:description row)))))
      (finally (clean!)))))

(deftest migration-011-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-proposals-down")]
    (try
      (propose! ds 1)
      (migrations/rollback! (:conn ds) "010-backfill-version-source")
      (testing "the table and its index are gone and they took nothing with them"
        (is (not (contains? (tables ds) "recipe_proposals")))
        (is (not (contains? (set (map :name (jdbc/execute! (db/get-conn ds)
                                              ["SELECT name FROM sqlite_master
                                                 WHERE type = 'index'"]
                                              db/jdbc-opts)))
                            "idx_recipe_proposals_one_pending")))
        (is (contains? (tables ds) "recipes"))
        (is (contains? (tables ds) "recipe_events"))
        (is (re-find #"source\s+TEXT NOT NULL"
                     (:sql (one ds {:select [:sql] :from [:sqlite_master]
                                    :where [:= :name "recipes"]})))
            "010's constraint is still there — 011's rollback must not reach it"))
      (testing "and 011 re-applies onto an empty table"
        (migrations/migrate! (:conn ds))
        (is (contains? (tables ds) "recipe_proposals"))
        (is (zero? (:n (one ds {:select [[[:count :*] :n]] :from [:recipe_proposals]})))))
      (finally (clean!)))))

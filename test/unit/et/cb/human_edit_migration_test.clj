(ns et.cb.human-edit-migration-test
  "Migration 004: the `has_human_edit` column, and what it does to a database
  that already has Recipes in it.

  The one decision this migration makes is what happens to those rows, and it is
  a decision rather than a default falling where it fell — nothing in this schema
  ever recorded who wrote a Recipe, so the provenance of everything that exists is
  unknown, and unknown is not human. Asserted here rather than left to the column
  default, because a later `DEFAULT 1` would be a silent claim about every Recipe
  the owner already has."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.migrations :as migrations]))

;; A file database of its own, like the 003 rollback tests: rolling back the
;; suite's shared in-memory schema would take every other test with it.

(defn- temp-file-db [label]
  (let [dir (java.nio.file.Files/createTempDirectory
              label (into-array java.nio.file.attribute.FileAttribute []))
        ds (db/init-conn {:type :sqlite-file :path (str dir "/" label ".db")})]
    [ds (fn []
          (when-let [pc (:persistent-conn ds)] (.close pc))
          (doseq [f (reverse (file-seq (io/file (str dir))))] (.delete f)))]))

(defn- columns [ds]
  (set (map :name (jdbc/execute! (db/get-conn ds) ["PRAGMA table_info(recipes)"] db/jdbc-opts))))

(defn- insert-recipe! [ds title]
  (:id (jdbc/execute-one! (db/get-conn ds)
         (sql/format {:insert-into :recipes
                      :values [{:title title :useful_when "" :description "" :version 1}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- mark-of [ds id]
  (:has_human_edit (jdbc/execute-one! (db/get-conn ds)
                     (sql/format {:select [:has_human_edit] :from [:recipes]
                                  :where [:= :id id]})
                     db/jdbc-opts)))

(deftest migration-004-adds-the-provenance-column
  (let [[ds clean!] (temp-file-db "cb-human-edit-up")]
    (try
      (is (contains? (columns ds) "has_human_edit"))
      (testing "and it lands on `recipes`, not on `recipe_history` — this is one
                fact about the row, not per-version authorship"
        (is (not (contains?
                   (set (map :name (jdbc/execute! (db/get-conn ds)
                                     ["PRAGMA table_info(recipe_history)"] db/jdbc-opts)))
                   "has_human_edit"))))
      (finally (clean!)))))

(deftest recipes-that-predate-the-column-are-not-human-edited
  ;; The shape of the owner's dev database: rows written before anything recorded
  ;; provenance. Made by rolling 004 back, writing a row into the old schema, and
  ;; migrating forward again — which is exactly the sequence his database will run.
  (let [[ds clean!] (temp-file-db "cb-human-edit-existing")]
    (try
      (migrations/rollback! (:conn ds) "003-machine-user")
      (is (not (contains? (columns ds) "has_human_edit")) "004 is rolled back")

      (let [older (insert-recipe! ds "Written before anyone was counting")]
        (migrations/migrate! (:conn ds))
        (testing "the column is back"
          (is (contains? (columns ds) "has_human_edit")))
        (testing "and the row that predates it reads 0 — not human-edited, because
                  nobody recorded that it was"
          (is (= 0 (mark-of ds older))))
        (testing "so the filter passes it by until it is saved again"
          (is (empty? (jdbc/execute! (db/get-conn ds)
                        (sql/format {:select [:id] :from [:recipes]
                                     :where [:= :has_human_edit 1]})
                        db/jdbc-opts)))))
      (finally (clean!)))))

(deftest migration-004-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-human-edit-down")]
    (try
      (let [id (insert-recipe! ds "Sourdough")]
        (jdbc/execute-one! (db/get-conn ds)
          (sql/format {:update :recipes :set {:has_human_edit 1} :where [:= :id id]}))

        (migrations/rollback! (:conn ds) "003-machine-user")

        (testing "the column is gone and it took nothing else with it"
          (is (not (contains? (columns ds) "has_human_edit")))
          (is (contains? (columns ds) "version"))
          (is (contains? (columns ds) "published"))
          (is (= "Sourdough" (:title (jdbc/execute-one! (db/get-conn ds)
                                       (sql/format {:select [:title] :from [:recipes]
                                                    :where [:= :id id]})
                                       db/jdbc-opts)))))

        (testing "and 004 re-applies cleanly, with the mark back at 0 — a rollback
                  drops the record of provenance, so re-migrating cannot restore a
                  claim about who wrote this"
          (migrations/migrate! (:conn ds))
          (is (contains? (columns ds) "has_human_edit"))
          (is (= 0 (mark-of ds id)))))
      (finally (clean!)))))

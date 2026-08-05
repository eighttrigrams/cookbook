(ns et.cb.version-source-migration-test
  "Migration 005: the two `source` columns, and what they say about a database
  that already has versions in it.

  The decision here is the same shape as 004's and it is asserted for the same
  reason: everything that exists when this runs comes out NULL, and NULL is a
  *third* category rather than a synonym for `'machine'`. Nothing ever recorded who
  wrote those versions — `recipe_history` had no author column, and
  `recipes.user_id` cannot answer it, because a machine token reads in the owner's
  audience — so either default would be a silent claim about work the owner may well
  have done by hand. Left to the column default this would read as a detail; pinned
  here it reads as the decision."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.migrations :as migrations]))

;; A file database of its own, like the 003 and 004 rollback tests: rolling back
;; the suite's shared in-memory schema would take every other test with it.

(defn- temp-file-db [label]
  (let [dir (java.nio.file.Files/createTempDirectory
              label (into-array java.nio.file.attribute.FileAttribute []))
        ds (db/init-conn {:type :sqlite-file :path (str dir "/" label ".db")})]
    [ds (fn []
          (when-let [pc (:persistent-conn ds)] (.close pc))
          (doseq [f (reverse (file-seq (io/file (str dir))))] (.delete f)))]))

(defn- columns [ds table]
  (set (map :name (jdbc/execute! (db/get-conn ds) [(str "PRAGMA table_info(" table ")")]
                                 db/jdbc-opts))))

(defn- insert-recipe!
  "Straight at the table, in whatever schema is current — so this still works with
  005 rolled back, which is the point."
  [ds title version]
  (:id (jdbc/execute-one! (db/get-conn ds)
         (sql/format {:insert-into :recipes
                      :values [{:title title :useful_when "" :description "" :version version}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- insert-history! [ds recipe-id version]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:insert-into :recipe_history
                 :values [{:recipe_id recipe-id :version version
                           :title "older" :useful_when "" :description "older body"}]})))

(defn- source-of [ds id]
  (:source (jdbc/execute-one! (db/get-conn ds)
             (sql/format {:select [:source] :from [:recipes] :where [:= :id id]})
             db/jdbc-opts)))

(deftest migration-005-adds-a-source-column-to-both-tables
  (let [[ds clean!] (temp-file-db "cb-version-source-up")]
    (try
      (testing "the current version's label lives on the row"
        (is (contains? (columns ds "recipes") "source")))
      (testing "and each superseded version's on its own history row — this is
                per-version authorship, so unlike 004's bit it cannot live on the
                row alone"
        (is (contains? (columns ds "recipe_history") "source")))
      (testing "004's bit is still there beside it: it is what ?human=true reads,
                and it is not replaced by anything derivable"
        (is (contains? (columns ds "recipes") "has_human_edit")))
      (finally (clean!)))))

(deftest versions-that-predate-the-columns-are-unrecorded-and-not-machine-written
  ;; The shape of the owner's dev database: rows and history rows written before
  ;; anything recorded provenance. Made the way his database will actually run
  ;; it — roll 005 back, write into the old schema, migrate forward.
  (let [[ds clean!] (temp-file-db "cb-version-source-existing")]
    (try
      (migrations/rollback! (:conn ds) "004-human-edit-provenance")
      (is (not (contains? (columns ds "recipes") "source")) "005 is rolled back")
      (is (not (contains? (columns ds "recipe_history") "source")))

      (let [older (insert-recipe! ds "Written before anyone was counting" 3)]
        (insert-history! ds older 1)
        (insert-history! ds older 2)
        (migrations/migrate! (:conn ds))

        (testing "both columns are back"
          (is (contains? (columns ds "recipes") "source"))
          (is (contains? (columns ds "recipe_history") "source")))

        (testing "and every version reads NULL — nothing is asserted about work
                  nobody recorded, in either direction"
          (is (nil? (source-of ds older)))
          (is (every? nil? (map :source
                                (jdbc/execute! (db/get-conn ds)
                                  (sql/format {:select [:source] :from [:recipe_history]
                                               :where [:= :recipe_id older]})
                                  db/jdbc-opts)))))

        (testing "so the listing puts all three of its versions in the unrecorded
                  bucket, and none in the machine one: `3(?)` is what this Recipe
                  shows, which is the third category doing its job"
          (let [row (first (db.recipe/list-recipes ds nil))]
            (is (= 3 (:version row)))
            (is (= 3 (:unrecorded_versions row)))
            (is (= 0 (:machine_versions row)))
            (is (= 0 (:ui_versions row))))))
      (finally (clean!)))))

(deftest migration-005-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-version-source-down")]
    (try
      (let [id (insert-recipe! ds "Sourdough" 2)]
        (insert-history! ds id 1)
        (jdbc/execute-one! (db/get-conn ds)
          (sql/format {:update :recipes :set {:source "ui"} :where [:= :id id]}))

        (migrations/rollback! (:conn ds) "004-human-edit-provenance")

        (testing "both columns are gone and they took nothing else with them"
          (is (not (contains? (columns ds "recipes") "source")))
          (is (not (contains? (columns ds "recipe_history") "source")))
          (is (contains? (columns ds "recipes") "version"))
          (is (contains? (columns ds "recipes") "published"))
          (is (contains? (columns ds "recipes") "has_human_edit"))
          (is (= "Sourdough" (:title (jdbc/execute-one! (db/get-conn ds)
                                       (sql/format {:select [:title] :from [:recipes]
                                                    :where [:= :id id]})
                                       db/jdbc-opts))))
          (testing "including the version history itself — the rollback drops the
                    labels, not the versions they were on"
            (is (= [1] (map :version (jdbc/execute! (db/get-conn ds)
                                       (sql/format {:select [:version] :from [:recipe_history]
                                                    :where [:= :recipe_id id]})
                                       db/jdbc-opts))))))

        (testing "and 005 re-applies with the label back at NULL: a rollback drops
                  the record of who wrote this, so re-migrating cannot restore a
                  claim about it"
          (migrations/migrate! (:conn ds))
          (is (contains? (columns ds "recipes") "source"))
          (is (nil? (source-of ds id)))))
      (finally (clean!)))))

(ns et.cb.view-count-migration-test
  "Migration 008: the `view_count` column, and what its `0` means.

  Two things are pinned here rather than left to the DDL. It lands on `recipes`
  and **not** on `recipe_history` — a read is a fact about the Recipe and about
  today, not about whichever version happened to be current, so there is no such
  thing as 'how often was v2 read'. And every existing row comes out `0`, which
  is neither 004's withheld claim nor 006's honestly-empty column: it is an
  epoch. Recipes 1, 2 and 3 in the owner's own database have been read many
  times; nothing counted, so `0` says 'no reads recorded yet', and what makes it
  honest is that every row starts from the same line."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.migrations :as migrations]))

;; A file database of its own, like the 003 to 006 rollback tests: rolling back
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
  "Straight at the table, in whatever schema is current — so this still works
  with 008 rolled back, which is the point."
  [ds title]
  (:id (jdbc/execute-one! (db/get-conn ds)
         (sql/format {:insert-into :recipes
                      :values [{:title title :useful_when "" :description "" :version 1}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- view-count-of [ds id]
  (:view_count (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:select [:view_count] :from [:recipes] :where [:= :id id]})
                 db/jdbc-opts)))

(deftest migration-008-adds-view-count-to-the-row-and-not-to-the-history
  (let [[ds clean!] (temp-file-db "cb-views-up")]
    (try
      (is (contains? (columns ds "recipes") "view_count"))
      (testing "and not to `recipe_history` — a read is not about the version that
                happened to be current when it happened, so there is no answer to
                'how often was v2 read' and nothing here pretends there is"
        (is (not (contains? (columns ds "recipe_history") "view_count"))))
      (testing "the columns it sits beside are untouched"
        (is (contains? (columns ds "recipes") "tags"))
        (is (contains? (columns ds "recipes") "has_human_edit"))
        (is (contains? (columns ds "recipes") "source"))
        (is (contains? (columns ds "recipe_history") "source")))
      (finally (clean!)))))

(deftest recipes-that-predate-the-column-start-at-the-same-line
  ;; The shape of the owner's dev database: rows written, read and re-read long
  ;; before anything counted. Made the way his database will actually run it —
  ;; roll 008 back, write into the old schema, migrate forward.
  (let [[ds clean!] (temp-file-db "cb-views-existing")]
    (try
      (migrations/rollback! (:conn ds) "007-scopes")
      (is (not (contains? (columns ds "recipes") "view_count")) "008 is rolled back")

      (let [older (insert-recipe! ds "Read a hundred times, counted zero")
            other (insert-recipe! ds "Written the same day")]
        (migrations/migrate! (:conn ds))
        (testing "the column is back"
          (is (contains? (columns ds "recipes") "view_count")))
        (testing "and both rows read 0 — not because they were never read, but
                  because the count starts here and starts equal for everybody,
                  which is what makes comparing them fair from day one"
          (is (= 0 (view-count-of ds older)))
          (is (= 0 (view-count-of ds other))))
        (testing "so the number is served with the lean row rather than hidden
                  behind ?detail=full: the card that shows it is a lean row"
          (is (= [0 0] (map :view_count (db.recipe/list-recipes ds nil))))
          (is (= 0 (:view_count (db.recipe/get-recipe ds nil older))))))
      (finally (clean!)))))

(deftest migration-008-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-views-down")]
    (try
      (let [id (insert-recipe! ds "Sourdough")]
        (db.recipe/record-view! ds id)
        (db.recipe/record-view! ds id)
        (is (= 2 (view-count-of ds id)))

        (migrations/rollback! (:conn ds) "007-scopes")

        (testing "the column is gone and it took nothing else with it"
          (is (not (contains? (columns ds "recipes") "view_count")))
          (is (contains? (columns ds "recipes") "version"))
          (is (contains? (columns ds "recipes") "published"))
          (is (contains? (columns ds "recipes") "tags"))
          (is (contains? (columns ds "recipes") "has_human_edit"))
          (is (contains? (columns ds "recipes") "source"))
          (is (= "Sourdough" (:title (jdbc/execute-one! (db/get-conn ds)
                                       (sql/format {:select [:title] :from [:recipes]
                                                    :where [:= :id id]})
                                       db/jdbc-opts)))))

        (testing "and 008 re-applies with the count back at 0: a rollback drops the
                  column, so re-migrating cannot restore reads nobody stored — the
                  epoch simply moves to the second migration"
          (migrations/migrate! (:conn ds))
          (is (contains? (columns ds "recipes") "view_count"))
          (is (= 0 (view-count-of ds id)))))
      (finally (clean!)))))

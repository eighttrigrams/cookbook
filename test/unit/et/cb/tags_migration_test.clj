(ns et.cb.tags-migration-test
  "Migration 006: the `tags` column, where it went and where it deliberately did
  not.

  Two decisions are pinned here rather than left to the DDL. It lands on
  `recipes` and **not** on `recipe_history`, because tags are not versioned — the
  same call `published` made, and the thing `update-recipe`'s tags-only branch
  exists to honour. And every existing row comes out `''`, which unlike 004's and
  005's defaults is not a claim being withheld: the column records what the owner
  curated, so 'nothing yet' is simply true of a Recipe nobody has tagged, and
  there is no third category to keep."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.migrations :as migrations]))

;; A file database of its own, like the 003, 004 and 005 rollback tests: rolling
;; back the suite's shared in-memory schema would take every other test with it.

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
  with 006 rolled back, which is the point."
  [ds title]
  (:id (jdbc/execute-one! (db/get-conn ds)
         (sql/format {:insert-into :recipes
                      :values [{:title title :useful_when "" :description "" :version 1}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- tags-of [ds id]
  (:tags (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:tags] :from [:recipes] :where [:= :id id]})
           db/jdbc-opts)))

(deftest migration-006-adds-tags-to-the-row-and-not-to-the-history
  (let [[ds clean!] (temp-file-db "cb-tags-up")]
    (try
      (is (contains? (columns ds "recipes") "tags"))
      (testing "and not to `recipe_history` — tags are not versioned, so there is
                no such thing as what a Recipe's tags were at v2"
        (is (not (contains? (columns ds "recipe_history") "tags"))))
      (testing "the columns it sits beside are untouched"
        (is (contains? (columns ds "recipes") "has_human_edit"))
        (is (contains? (columns ds "recipes") "source"))
        (is (contains? (columns ds "recipe_history") "source")))
      (finally (clean!)))))

(deftest recipes-that-predate-the-column-read-as-untagged
  ;; The shape of the owner's dev database: rows written before tags existed.
  ;; Made the way his database will actually run it — roll 006 back, write into
  ;; the old schema, migrate forward.
  (let [[ds clean!] (temp-file-db "cb-tags-existing")]
    (try
      (migrations/rollback! (:conn ds) "005-version-source")
      (is (not (contains? (columns ds "recipes") "tags")) "006 is rolled back")

      (let [older (insert-recipe! ds "Written before there were tags")]
        (migrations/migrate! (:conn ds))
        (testing "the column is back"
          (is (contains? (columns ds "recipes") "tags")))
        (testing "and the row that predates it reads the empty string — untagged,
                  which is the true state of a Recipe nobody has filed rather than
                  a fact being withheld"
          (is (= "" (tags-of ds older))))
        (testing "so it is served as untagged rather than as null, and a search
                  for anything still only consults its title"
          (let [row (first (db.recipe/list-recipes ds nil))]
            (is (= "" (:tags row))))
          (is (= 1 (count (db.recipe/list-recipes ds nil {:search-term "written"}))))
          (is (empty? (db.recipe/list-recipes ds nil {:search-term "untagged"})))))
      (finally (clean!)))))

(deftest migration-006-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-tags-down")]
    (try
      (let [id (insert-recipe! ds "Sourdough")]
        (jdbc/execute-one! (db/get-conn ds)
          (sql/format {:update :recipes :set {:tags "bread baking"} :where [:= :id id]}))

        (migrations/rollback! (:conn ds) "005-version-source")

        (testing "the column is gone and it took nothing else with it"
          (is (not (contains? (columns ds "recipes") "tags")))
          (is (contains? (columns ds "recipes") "version"))
          (is (contains? (columns ds "recipes") "published"))
          (is (contains? (columns ds "recipes") "has_human_edit"))
          (is (contains? (columns ds "recipes") "source"))
          (is (= "Sourdough" (:title (jdbc/execute-one! (db/get-conn ds)
                                       (sql/format {:select [:title] :from [:recipes]
                                                    :where [:= :id id]})
                                       db/jdbc-opts)))))

        (testing "and 006 re-applies with the tags back at empty: a rollback drops
                  the filing, so re-migrating cannot restore words nobody stored"
          (migrations/migrate! (:conn ds))
          (is (contains? (columns ds "recipes") "tags"))
          (is (= "" (tags-of ds id)))))
      (finally (clean!)))))

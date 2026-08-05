(ns et.cb.scopes-migration-test
  "Migration 007: the two tables, and the three decisions in their shape that are
  worth more than the DDL saying them once.

  `title` and not tracker's `name`, because cookbook's own entity calls that field
  `title`. `UNIQUE(title, user_id)` and not `UNIQUE(title)`, which is the bug
  tracker's own migration 007 exists to fix — so this starts where tracker ended
  up rather than repeating the step. And **no `category_type`** on the join table:
  tracker needs a discriminator because four kinds of category share its table,
  cookbook has one kind, and a column with one possible value answers nothing while
  still having to be threaded through every query.

  Also pinned: the tables land beside what was already there rather than altering
  it. Nothing about Scopes touches `recipes`, and nothing touches `recipe_history`
  — filing is not content, so there is no answer to 'which Scopes was this Recipe
  in at v2' and deliberately nowhere to record one."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.db.scope :as db.scope]
            [et.cb.migrations :as migrations]))

;; A file database of its own, like the 003–006 rollback tests: rolling back the
;; suite's shared in-memory schema would take every other test with it.

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

(defn- table-sql [ds table]
  (:sql (jdbc/execute-one! (db/get-conn ds)
          (sql/format {:select [[:sql :sql]] :from [:sqlite_master]
                       :where [:and [:= :type "table"] [:= :name table]]})
          db/jdbc-opts)))

(defn- tables [ds]
  (set (map :name (jdbc/execute! (db/get-conn ds)
                    (sql/format {:select [:name] :from [:sqlite_master]
                                 :where [:= :type "table"]})
                    db/jdbc-opts))))

(deftest migration-007-adds-the-two-tables-and-alters-nothing
  (let [[ds clean!] (temp-file-db "cb-scopes-up")]
    (try
      (is (= #{"id" "title" "description" "user_id"} (columns ds "scopes")))
      (is (= #{"recipe_id" "scope_id"} (columns ds "recipe_scopes")))
      (testing "no `category_type`: cookbook has one kind of category, so a
                discriminator could only ever hold one value"
        (is (false? (contains? (columns ds "recipe_scopes") "category_type"))))
      (testing "`title`, not tracker's `name`"
        (is (false? (contains? (columns ds "scopes") "name"))))
      (testing "unique per owner and not globally, which is where tracker's own
                007 ended up"
        (is (str/includes? (str/replace (table-sql ds "scopes") #"\s+" " ")
                           "UNIQUE(title, user_id)")))
      (testing "the join table's identity is the pair, so filing a Recipe under
                the same Scope twice is not something the code has to prevent"
        (is (str/includes? (str/replace (table-sql ds "recipe_scopes") #"\s+" " ")
                           "PRIMARY KEY (recipe_id, scope_id)")))
      (testing "and nothing was added to the Recipe or to its history: filing is
                not content, so there is nowhere to record what a Recipe's Scopes
                were at v2"
        (is (false? (contains? (columns ds "recipes") "scope_id")))
        (is (false? (contains? (columns ds "recipe_history") "scope_id")))
        (is (contains? (columns ds "recipes") "tags"))
        (is (contains? (columns ds "recipe_history") "source")))
      (finally (clean!)))))

(deftest recipes-that-predate-the-tables-read-as-unfiled
  ;; The shape of the owner's dev database: Recipes written before Scopes existed.
  ;; Made the way his database will actually run it — roll 007 back, write into the
  ;; old schema, migrate forward.
  (let [[ds clean!] (temp-file-db "cb-scopes-existing")]
    (try
      (migrations/rollback! (:conn ds) "006-recipe-tags")
      (is (false? (contains? (tables ds) "scopes")) "007 is rolled back")

      (let [older (:id (jdbc/execute-one! (db/get-conn ds)
                         (sql/format {:insert-into :recipes
                                      :values [{:title "Written before there were Scopes"
                                                :useful_when "" :description "" :version 1}]
                                      :returning [:id]})
                         db/jdbc-opts))]
        (migrations/migrate! (:conn ds))
        (testing "the tables are there"
          (is (contains? (tables ds) "scopes"))
          (is (contains? (tables ds) "recipe_scopes")))
        (testing "and the row that predates them is filed under nothing, with the
                  key present and empty — for the owner that is a true answer and
                  not a withheld one"
          (let [row (first (db.recipe/list-recipes ds nil))]
            (is (contains? row :scopes))
            (is (= [] (:scopes row)))))
        (testing "there is nothing to list yet, and a Scope can be made and used
                  straight away"
          (is (empty? (db.scope/list-scopes ds nil)))
          (let [{bread :id} (db.scope/create-scope ds nil {:title "Bread"})]
            (db.recipe/update-recipe ds nil older {:scope_ids [bread]} nil)
            (is (= ["Bread"] (mapv :title (:scopes (first (db.recipe/list-recipes ds nil)))))))))
      (finally (clean!)))))

(deftest migration-007-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-scopes-down")]
    (try
      (let [{bread :id} (db.scope/create-scope ds nil {:title "Bread"})
            {:keys [id]} (db.recipe/create-recipe ds nil {:title "Sourdough"
                                                         :scope_ids [bread]})]
        (is (= 1 (count (db.scope/list-scopes ds nil))))

        (migrations/rollback! (:conn ds) "006-recipe-tags")

        (testing "both tables are gone, the join one included — a `:down` that
                  dropped only `scopes` would leave a table pointing at nothing"
          (is (false? (contains? (tables ds) "scopes")))
          (is (false? (contains? (tables ds) "recipe_scopes"))))
        (testing "and they took nothing with them: the Recipe and its columns are
                  as they were"
          (is (contains? (tables ds) "recipes"))
          (is (contains? (columns ds "recipes") "tags"))
          (is (= "Sourdough" (:title (jdbc/execute-one! (db/get-conn ds)
                                       (sql/format {:select [:title] :from [:recipes]
                                                    :where [:= :id id]})
                                       db/jdbc-opts))))))
      (finally (clean!)))))

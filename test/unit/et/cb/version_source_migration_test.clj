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
  005 rolled back, which is the point. `source` is optional for the same reason it
  is in the 004 namespace: before 005 there is no such column, and from 010 it is
  `NOT NULL`, so which of the two a caller means depends on the schema it is writing
  into."
  ([ds title version] (insert-recipe! ds title version nil))
  ([ds title version source]
   (:id (jdbc/execute-one! (db/get-conn ds)
          (sql/format {:insert-into :recipes
                       :values [(cond-> {:title title :useful_when "" :description ""
                                         :version version}
                                  source (assoc :source source))]
                       :returning [:id]})
          db/jdbc-opts))))

(defn- insert-history!
  ([ds recipe-id version] (insert-history! ds recipe-id version nil))
  ([ds recipe-id version source]
   (jdbc/execute-one! (db/get-conn ds)
     (sql/format {:insert-into :recipe_history
                  :values [(cond-> {:recipe_id recipe-id :version version
                                    :title "older" :useful_when "" :description "older body"}
                             source (assoc :source source))]}))))

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

(deftest versions-that-predate-the-columns-are-the-owners-once-010-has-run
  ;; The shape of the owner's dev database: rows and history rows written before
  ;; anything recorded provenance. Made the way his database actually ran it — roll
  ;; 005 back, write into the old schema, migrate forward.
  ;;
  ;; **This test asserted the third bucket and now asserts its end**, which is the
  ;; one thing 005 could not have said. 005 refused to guess and left NULL as a
  ;; category, and that refusal is why the answer was still the owner's to give; when
  ;; he was asked, he said those versions were his. 010 wrote that down. So a Recipe
  ;; from before either column now reads `3(ui)` where it used to read `3(?)`, and
  ;; the reason the two tests differ is not that one of them was wrong.
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

        (testing "and every version reads `ui` — nothing is left unrecorded, because
                  the one person who could settle it did"
          (is (= "ui" (source-of ds older)))
          (is (every? #{"ui"} (map :source
                                   (jdbc/execute! (db/get-conn ds)
                                     (sql/format {:select [:source] :from [:recipe_history]
                                                  :where [:= :recipe_id older]})
                                     db/jdbc-opts)))))

        (testing "so the listing puts all three of its versions in the ui bucket and
                  none in the machine one: `3(ui)` is what this Recipe shows, and
                  there is no third bucket left for it to fall into"
          (let [row (first (db.recipe/list-recipes ds nil))]
            (is (= 3 (:version row)))
            (is (= 3 (:ui_versions row)))
            (is (= 0 (:machine_versions row)))
            (is (false? (contains? row :unrecorded_versions))
                "and the key is gone from the projection rather than sent as 0")))

        (testing "and the bit came up with it, so ?human=true finds this Recipe —
                  the half of 010 that keeps `db.recipe`'s stated invariant true"
          (is (= 1 (count (db.recipe/list-recipes ds nil {:human-only? true}))))))
      (finally (clean!)))))

(deftest migration-005-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-version-source-down")]
    (try
      ;; Written into the *current* schema, so both rows have to carry a label: 010
      ;; made the column NOT NULL on `recipes` and on `recipe_history` alike.
      (let [id (insert-recipe! ds "Sourdough" 2 "ui")]
        (insert-history! ds id 1 "ui")

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

        (testing "and 005 re-applies. The label does **not** come back at NULL any
                  more, and that is 010 rather than 005: the rollback drops the
                  record of who wrote this, 005 re-adds the column empty, and the
                  backfill then reads an unlabelled version as the owner's. Which is
                  also the honest summary of what a rollback of 010 costs — it
                  cannot restore which rows were NULL, and it does not pretend to."
          (migrations/migrate! (:conn ds))
          (is (contains? (columns ds "recipes") "source"))
          (is (= "ui" (source-of ds id)))))
      (finally (clean!)))))

(ns et.cb.read-attribution-migration-test
  "Migration 013: `human_reads` and `machine_reads` beside 008's `view_count`, and
  what *their* `0` means.

  `view-count-migration-test` is the sibling and it pins 008's epoch — every row
  starts at the same line, so no row is misattributed relative to another. This
  pins the thing that is new about 013's epoch: it arrives **later than the total
  it splits**, so unlike 008 the starting line is *visible*. A Recipe with 212 reads
  and 2 attributed is the ordinary case on his shelf, not an edge one, and the
  remainder has to stay computable rather than being folded into either bucket.

  The other thing pinned here is the naming, which is a decision and not a spelling:
  `*_reads` and not `*_views`, because the row already carries `machine_versions`
  and a `machine_views` beside it is two words apart at the moment somebody is
  scanning a `SELECT *` rather than reading one."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.migrations :as migrations]))

;; A file database of its own, like the other rollback tests: rolling back the
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

(defn- insert-recipe! [ds title]
  (:id (jdbc/execute-one! (db/get-conn ds)
         (sql/format {:insert-into :recipes
                      :values [{:title title :useful_when "" :description ""
                                :version 1 :source "ui"}]
                      :returning [:id]})
         db/jdbc-opts)))

(defn- counts-of [ds id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:view_count :human_reads :machine_reads]
                 :from [:recipes] :where [:= :id id]})
    db/jdbc-opts))

(deftest migration-013-adds-both-counters-to-the-row-and-not-to-the-history
  (let [[ds clean!] (temp-file-db "cb-reads-up")]
    (try
      (is (contains? (columns ds "recipes") "human_reads"))
      (is (contains? (columns ds "recipes") "machine_reads"))
      (testing "and not to `recipe_history`, for 008's reason exactly: a read is a
                fact about the Recipe and about today, not about the version that
                happened to be current when it happened"
        (is (not (contains? (columns ds "recipe_history") "human_reads")))
        (is (not (contains? (columns ds "recipe_history") "machine_reads"))))
      (testing "the total they split is still there and still its own column"
        (is (contains? (columns ds "recipes") "view_count")))
      (testing "and they are named so a `SELECT *` cannot confuse them with the
                *version* counts, which are aggregated per listing rather than
                stored — no `machine_views` beside `machine_versions`"
        (is (not (contains? (columns ds "recipes") "machine_views")))
        (is (not (contains? (columns ds "recipes") "human_views"))))
      (finally (clean!)))))

(deftest reads-counted-before-013-stay-in-the-total-and-in-neither-bucket
  ;; **The shape of every Recipe on his shelf**, made the way his database will
  ;; actually run it: roll 013 back, count reads into the old schema, migrate
  ;; forward. What comes out is the case the badge has to be honest about.
  (let [[ds clean!] (temp-file-db "cb-reads-existing")]
    (try
      (migrations/rollback! (:conn ds) "012-recipe-tombstones")
      (is (not (contains? (columns ds "recipes") "human_reads")) "013 is rolled back")

      (let [id (insert-recipe! ds "Read a hundred times, attributed zero")]
        ;; Counted the only way the old schema could: the total alone.
        (dotimes [_ 34]
          (jdbc/execute-one! (db/get-conn ds)
            (sql/format {:update :recipes
                         :set {:view_count [:+ :view_count [:inline 1]]}
                         :where [:= :id id]})))
        (migrations/migrate! (:conn ds))
        (testing "the columns are there and the old reads are in neither of them"
          (is (= {:view_count 34 :human_reads 0 :machine_reads 0} (counts-of ds id))))
        (testing "so the remainder is exactly what predates the split, and it is a
                  subtraction rather than a stored number — nothing has to remember
                  it, and nothing can get it wrong later"
          (let [{:keys [view_count human_reads machine_reads]} (counts-of ds id)]
            (is (= 34 (- view_count human_reads machine_reads)))))
        (testing "and reads from here on attribute themselves, leaving that
                  remainder where it is"
          (db.recipe/record-view! ds id false)
          (db.recipe/record-view! ds id true)
          (let [{:keys [view_count human_reads machine_reads]} (counts-of ds id)]
            (is (= 36 view_count))
            (is (= 1 human_reads))
            (is (= 1 machine_reads))
            (is (= 34 (- view_count human_reads machine_reads)))))
        (testing "both counters ride on the lean listing row, like the total"
          (let [row (first (db.recipe/list-recipes ds nil))]
            (is (= 36 (:view_count row)))
            (is (= 1 (:human_reads row)))
            (is (= 1 (:machine_reads row))))))
      (finally (clean!)))))

(deftest migration-013-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-reads-down")]
    (try
      (let [id (insert-recipe! ds "Sourdough")]
        (db.recipe/record-view! ds id false)
        (db.recipe/record-view! ds id true)
        (is (= {:view_count 2 :human_reads 1 :machine_reads 1} (counts-of ds id)))

        (migrations/rollback! (:conn ds) "012-recipe-tombstones")

        (testing "both columns are gone and they took nothing else with them —
                  `view_count` above all, which is the ranking's input"
          (is (not (contains? (columns ds "recipes") "human_reads")))
          (is (not (contains? (columns ds "recipes") "machine_reads")))
          (is (contains? (columns ds "recipes") "view_count"))
          (is (contains? (columns ds "recipes") "deleted_at"))
          (is (contains? (columns ds "recipes") "has_human_edit"))
          (is (contains? (columns ds "recipes") "source")))
        (testing "and the total survived the rollback with both reads still in it,
                  which is what says the two counters were a *split* of it and never
                  a replacement"
          (is (= 2 (:view_count (jdbc/execute-one! (db/get-conn ds)
                                  (sql/format {:select [:view_count] :from [:recipes]
                                               :where [:= :id id]})
                                  db/jdbc-opts)))))

        (testing "and 013 re-applies with both back at 0: a rollback drops the
                  columns, so re-migrating cannot restore an attribution nobody
                  stored — the epoch simply moves to the second migration, exactly
                  as 008's does"
          (migrations/migrate! (:conn ds))
          (is (= {:view_count 2 :human_reads 0 :machine_reads 0} (counts-of ds id)))))
      (finally (clean!)))))

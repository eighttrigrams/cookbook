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

(defn- insert-recipe!
  "Straight at the table, in whatever schema is current — which is why `source` is
  optional rather than always supplied. Before 005 the column does not exist, so a
  test writing into that schema must not name it; from 010 it is `NOT NULL`, so a
  test writing into *this* schema must. The two callers below are one of each, and
  that asymmetry is the point rather than an oversight."
  ([ds title] (insert-recipe! ds title nil))
  ([ds title source]
   (:id (jdbc/execute-one! (db/get-conn ds)
          (sql/format {:insert-into :recipes
                       :values [(cond-> {:title title :useful_when "" :description ""
                                         :version 1}
                                  source (assoc :source source))]
                       :returning [:id]})
          db/jdbc-opts))))

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

(deftest recipes-that-predate-the-column-are-human-edited-once-010-has-run
  ;; The shape of the owner's dev database: rows written before anything recorded
  ;; provenance. Made by rolling 004 back, writing a row into the old schema, and
  ;; migrating forward again — which is exactly the sequence his database ran.
  ;;
  ;; **This test used to assert the opposite, and the change is not a correction of
  ;; 004 but a later decision.** 004 left such a row at 0 and said why: nothing had
  ;; recorded that a human wrote it, and unknown provenance is not human provenance.
  ;; 010 came back with something 004 did not have — the owner's own word that those
  ;; versions were his — and brought the bit up. Both are right at their own moment.
  ;;
  ;; 004's 0 is deliberately **not** asserted here, because it is no longer a
  ;; reachable state: this wrapper migrates all the way or not at all, and 010's
  ;; `:down` leaves `has_human_edit` where it put it — nothing records which rows it
  ;; moved, so a rollback cannot un-say it. The original decision lives in 004's own
  ;; comment, which stands untouched. Asserting it here would mean faking a halfway
  ;; state no database can be in.
  (let [[ds clean!] (temp-file-db "cb-human-edit-existing")]
    (try
      (migrations/rollback! (:conn ds) "003-machine-user")
      (is (not (contains? (columns ds) "has_human_edit")) "004 is rolled back")

      (let [older (insert-recipe! ds "Written before anyone was counting")]
        (migrations/migrate! (:conn ds))
        (is (contains? (columns ds) "has_human_edit") "the column is back")

        (testing "the row that predates every one of these columns comes out
                  human-edited, with its version labelled `ui`: the backfill is what
                  puts the Recipes he typed by hand back into the filter whose whole
                  job is to find them"
          (is (= 1 (mark-of ds older)))
          (is (= "ui" (:source (jdbc/execute-one! (db/get-conn ds)
                                 (sql/format {:select [:source] :from [:recipes]
                                              :where [:= :id older]})
                                 db/jdbc-opts))))
          (is (= [{:id older}]
                 (jdbc/execute! (db/get-conn ds)
                   (sql/format {:select [:id] :from [:recipes]
                                :where [:= :has_human_edit 1]})
                   db/jdbc-opts))
              "so ?human=true finds it, which before 010 it did not")))
      (finally (clean!)))))

(deftest migration-004-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-human-edit-down")]
    (try
      ;; Written into the *current* schema, so it has to carry a source: 010 made
      ;; the column NOT NULL.
      (let [id (insert-recipe! ds "Sourdough" "ui")]
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

        (testing "and 004 re-applies cleanly. The mark does **not** come back at 0
                  any more, and that is 010 rather than 004: the rollback dropped the
                  record of provenance, 005 then finds the row unlabelled, and the
                  backfill reads an unlabelled version as the owner's — so it lands
                  at 1 with `source` `ui`. 004's own answer is asserted in
                  `recipes-that-predate-the-column-read-0-at-004-and-1-after-010`,
                  where the later migrations are rolled off to ask it."
          (migrations/migrate! (:conn ds))
          (is (contains? (columns ds) "has_human_edit"))
          (is (= 1 (mark-of ds id)))))
      (finally (clean!)))))

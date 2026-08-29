(ns et.cb.version-source-backfill-migration-test
  "Migration 010: the third bucket retired, and everything the rebuild had to not
  break.

  `source` was nullable and NULL was a category — 'nobody recorded where this
  version came from'. The owner asked for it to go: *migrates all 'unknown' source
  older versions to 'human/ui', such that we can put a constraint in place that its
  always either human or machine*. So the backfill writes his answer down and the
  column becomes `TEXT NOT NULL CHECK (source IN ('ui','machine'))`.

  Half of this file is about the backfill and half about the **rebuild**, which is
  the part with teeth: SQLite cannot add NOT NULL or a CHECK with ALTER TABLE, so
  both tables are recreated and copied. A rebuild that silently drops a column's
  contents, an index, a primary key or an AUTOINCREMENT counter looks exactly like
  one that worked. The tests are therefore written about the columns nobody thinks
  about."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.recipe :as db.recipe]
            [et.cb.migrations :as migrations]))

;; A file database of its own, like every other rollback test here: rolling back
;; the suite's shared in-memory schema would take every other test with it.

(defn- temp-file-db [label]
  (let [dir (java.nio.file.Files/createTempDirectory
              label (into-array java.nio.file.attribute.FileAttribute []))
        ds (db/init-conn {:type :sqlite-file :path (str dir "/" label ".db")})]
    [ds (fn []
          (when-let [pc (:persistent-conn ds)] (.close pc))
          (doseq [f (reverse (file-seq (io/file (str dir))))] (.delete f)))]))

(defn- one [ds q] (jdbc/execute-one! (db/get-conn ds) (sql/format q) db/jdbc-opts))
(defn- all [ds q] (jdbc/execute! (db/get-conn ds) (sql/format q) db/jdbc-opts))

(defn- ddl-of [ds table]
  (:sql (one ds {:select [:sql] :from [:sqlite_master] :where [:= :name table]})))

(defn- indexes-on [ds table]
  (set (map :name (all ds {:select [:name] :from [:sqlite_master]
                           :where [:and [:= :type "index"] [:= :tbl_name table]]}))))

(defn- seq-of
  "The AUTOINCREMENT high-water mark, which is the thing a rebuild loses quietly."
  [ds table]
  (:seq (one ds {:select [:seq] :from [:sqlite_sequence] :where [:= :name table]})))

(defn- back-to-009!
  "Roll 010 off, so a test can write the world it found: `source` nullable."
  [ds]
  (migrations/rollback! (:conn ds) "009-recipe-events"))

(defn- insert-unrecorded-recipe!
  "A Recipe as it existed before 005 — `source` NULL, `has_human_edit` 0 — written
  straight at the table in the pre-010 schema. No request can produce this and
  after 010 no statement can either, which is the point of writing it with the
  migration rolled back."
  [ds {:keys [title version has-human-edit user-id]
       :or {version 1 has-human-edit 0}}]
  (:id (one ds {:insert-into :recipes
                :values [{:title title :useful_when "" :description "body"
                          :version version :has_human_edit has-human-edit
                          :source nil :user_id user-id}]
                :returning [:id]})))

(defn- insert-history! [ds recipe-id version source]
  (one ds {:insert-into :recipe_history
           :values [{:recipe_id recipe-id :version version :title "old"
                     :useful_when "" :description "old body" :source source}]}))

;; ---------------------------------------------------------------------------
;; the backfill

(deftest unrecorded-versions-become-ui-in-both-tables
  (let [[ds clean!] (temp-file-db "cb-backfill-up")]
    (try
      (back-to-009! ds)
      (let [his (insert-unrecorded-recipe! ds {:title "Typed before anybody counted"
                                               :version 3})
            _ (insert-history! ds his 1 nil)
            _ (insert-history! ds his 2 nil)
            agents (insert-unrecorded-recipe! ds {:title "An agent's, already labelled"})
            _ (one ds {:update :recipes :set {:source "machine"} :where [:= :id agents]})
            mixed (insert-unrecorded-recipe! ds {:title "Labelled now, unrecorded then"
                                                 :version 2})
            _ (one ds {:update :recipes :set {:source "machine"} :where [:= :id mixed]})
            _ (insert-history! ds mixed 1 nil)]

        (migrations/migrate! (:conn ds))

        (testing "every unrecorded version now reads ui — his answer, written down"
          (is (= "ui" (:source (one ds {:select [:source] :from [:recipes]
                                        :where [:= :id his]}))))
          (is (= ["ui" "ui"] (mapv :source (all ds {:select [:source] :from [:recipe_history]
                                                    :where [:= :recipe_id his]
                                                    :order-by [[:version :asc]]})))))
        (testing "and a version that was already labelled keeps its own label — the
                  backfill is `WHERE source IS NULL` and touches nothing else"
          (is (= "machine" (:source (one ds {:select [:source] :from [:recipes]
                                             :where [:= :id agents]}))))
          (is (= "machine" (:source (one ds {:select [:source] :from [:recipes]
                                             :where [:= :id mixed]})))))
        (testing "so a Recipe can be `machine` now and `ui` two versions back, which
                  is exactly the history the approval gate has to read"
          (is (= ["ui"] (mapv :source (all ds {:select [:source] :from [:recipe_history]
                                               :where [:= :recipe_id mixed]}))))))
      (finally (clean!)))))

(deftest the-bit-comes-up-wherever-a-version-is-now-ui
  ;; `db.recipe` states as an invariant that `has_human_edit` is true exactly when
  ;; some version reads `ui`. Backfilling the label without the bit would break it
  ;; for every pre-004 row — and in the direction that matters, because those are
  ;; the Recipes he typed by hand and `?human=true` exists to find them.
  (let [[ds clean!] (temp-file-db "cb-backfill-bit")]
    (try
      (back-to-009! ds)
      (let [current-ui (insert-unrecorded-recipe! ds {:title "His, all of it"})
            only-old-ui (insert-unrecorded-recipe! ds {:title "His v1, an agent's v2"
                                                       :version 2})
            _ (one ds {:update :recipes :set {:source "machine"} :where [:= :id only-old-ui]})
            _ (insert-history! ds only-old-ui 1 nil)
            all-machine (insert-unrecorded-recipe! ds {:title "The agents', throughout"
                                                       :version 2})
            _ (one ds {:update :recipes :set {:source "machine"} :where [:= :id all-machine]})
            _ (insert-history! ds all-machine 1 "machine")
            bit-of (fn [id] (:has_human_edit (one ds {:select [:has_human_edit] :from [:recipes]
                                                      :where [:= :id id]})))]
        (is (= [0 0 0] [(bit-of current-ui) (bit-of only-old-ui) (bit-of all-machine)])
            "all three read 0 before the migration, which is the state 004 left")

        (migrations/migrate! (:conn ds))

        (is (= 1 (bit-of current-ui)) "its own version is ui now, so the bit is on")
        (is (= 1 (bit-of only-old-ui))
            "**and here is the half a naive backfill misses**: the ui version is a
             *superseded* one, so the row's own label says machine — the migration
             has to read the whole history, not the row")
        (is (= 0 (bit-of all-machine))
            "while a Recipe the agents wrote throughout keeps the bit off: the
             backfill labels unrecorded versions, it does not invent human ones")
        (testing "so ?human=true finds exactly the two he had a hand in"
          (is (= #{"His, all of it" "His v1, an agent's v2"}
                 (set (map :title (db.recipe/list-recipes ds nil {:human-only? true})))))))
      (finally (clean!)))))

;; ---------------------------------------------------------------------------
;; the constraint

(deftest the-database-now-refuses-a-third-bucket
  (let [[ds clean!] (temp-file-db "cb-backfill-check")]
    (try
      (testing "NULL is refused on both tables — by the database, not by a handler"
        (is (thrown? org.sqlite.SQLiteException
              (one ds {:insert-into :recipes
                       :values [{:title "no label" :source nil}]})))
        (is (thrown? org.sqlite.SQLiteException
              (one ds {:insert-into :recipe_history
                       :values [{:recipe_id 1 :version 1 :source nil}]}))))
      (testing "and so is an insert that simply does not mention `source`: there is
                deliberately no column default, so nothing can be written without
                saying where it came from"
        (is (thrown? org.sqlite.SQLiteException
              (one ds {:insert-into :recipes :values [{:title "silent"}]}))))
      (testing "and a third *value* is refused too, which is what makes this a pair
                and not merely a not-null column"
        (is (thrown? org.sqlite.SQLiteException
              (one ds {:insert-into :recipes
                       :values [{:title "invented" :source "agent"}]})))
        (is (thrown? org.sqlite.SQLiteException
              (one ds {:insert-into :recipes
                       :values [{:title "invented" :source "UI"}]})))
        (is (thrown? org.sqlite.SQLiteException
              (one ds {:insert-into :recipes
                       :values [{:title "invented" :source ""}]}))))
      (testing "an existing row cannot be pushed back into the third bucket either"
        (let [{:keys [id]} (db.recipe/create-recipe ds nil {:title "Labelled"} {:human? true})]
          (is (thrown? org.sqlite.SQLiteException
                (one ds {:update :recipes :set {:source nil} :where [:= :id id]})))))
      (testing "while both real values go in"
        (is (some? (one ds {:insert-into :recipes :values [{:title "his" :source "ui"}]})))
        (is (some? (one ds {:insert-into :recipes
                            :values [{:title "theirs" :source "machine"}]}))))
      (finally (clean!)))))

;; ---------------------------------------------------------------------------
;; the rebuild

(deftest the-rebuild-preserves-every-column-nobody-thinks-about
  ;; The point of this test is the columns a rebuild would drop without anything
  ;; looking wrong: `view_count`, `tags`, `published_at`, `published`, `version`,
  ;; `user_id`, the timestamps. They are asserted by value, read back after the
  ;; migration, against what was written before it.
  (let [[ds clean!] (temp-file-db "cb-backfill-preserve")]
    (try
      (back-to-009! ds)
      (let [id (insert-unrecorded-recipe! ds {:title "Everything on it" :version 7
                                              :has-human-edit 0})
            _ (one ds {:update :recipes
                       :set {:useful_when "when everything matters"
                             :description "the body"
                             :published 1
                             :published_at "2020-01-02 03:04:05"
                             :created_at "2019-01-01 00:00:00"
                             :modified_at "2021-06-06 06:06:06"
                             :tags "one two three"
                             :view_count 41}
                       :where [:= :id id]})
            _ (insert-history! ds id 1 nil)
            before (one ds {:select [:*] :from [:recipes] :where [:= :id id]})
            history-before (one ds {:select [:*] :from [:recipe_history]
                                    :where [:= :recipe_id id]})]

        (migrations/migrate! (:conn ds))

        (let [after (one ds {:select [:*] :from [:recipes] :where [:= :id id]})
              history-after (one ds {:select [:*] :from [:recipe_history]
                                     :where [:= :recipe_id id]})]
          (testing "only `source` and `has_human_edit` moved; everything else is
                    byte-identical, including the columns this migration has no
                    business touching"
            ;; Compared key by key rather than map to map, because migrating forward
            ;; from 009 runs every later migration too and one of them adds a column
            ;; (012's `deleted_at`). Whole-map equality would then fail for a reason
            ;; that has nothing to do with the rebuild under test — and, worse, would
            ;; have to be re-relaxed by hand for every column added after this. What
            ;; the rebuild has to promise is that nothing it carried over changed
            ;; value, which is exactly this.
            (let [carried (dissoc before :source :has_human_edit)]
              (is (= carried (select-keys after (keys carried)))))
            (is (= 41 (:view_count after)))
            (is (= "one two three" (:tags after)))
            (is (= 1 (:published after)))
            (is (= "2020-01-02 03:04:05" (:published_at after)))
            (is (= "2019-01-01 00:00:00" (:created_at after)))
            (is (= "2021-06-06 06:06:06" (:modified_at after)))
            (is (= 7 (:version after))))
          (testing "and the same for a history row"
            ;; Key by key here too, and for the reason the recipe half above already
            ;; gives — which had it right first and left this line to be found by the
            ;; next migration that added a column. That was 015 (`reason`,
            ;; `context`): whole-map equality failed because the *later* migration
            ;; had done its job, which is a test asserting the absence of work rather
            ;; than the correctness of the rebuild under test.
            (let [carried (dissoc history-before :source)]
              (is (= carried (select-keys history-after (keys carried)))))
            (is (= "old body" (:description history-after))))))
      (finally (clean!)))))

(deftest the-rebuild-keeps-the-index-the-primary-key-and-the-autoincrement-counter
  (let [[ds clean!] (temp-file-db "cb-backfill-structure")]
    (try
      (back-to-009! ds)
      (let [first-id (insert-unrecorded-recipe! ds {:title "The first"})
            doomed (insert-unrecorded-recipe! ds {:title "Deleted before the migration"})]
        (is (contains? (indexes-on ds "recipes") "idx_recipes_user"))
        (one ds {:delete-from :recipes :where [:= :id doomed]})
        (is (= doomed (seq-of ds "recipes"))
            "the high-water mark is above the surviving rows, which is the case that
             matters — a deleted id must never be handed out again")

        (migrations/migrate! (:conn ds))

        (testing "the index is recreated: a rebuild drops it with the table, and a
                  missing index fails nothing visibly — it just gets slow"
          (is (contains? (indexes-on ds "recipes") "idx_recipes_user")))
        (testing "`recipe_history` keeps its composite primary key, which is the only
                  thing stopping one version of one Recipe being archived twice"
          (is (re-find #"(?i)PRIMARY KEY \(recipe_id, version\)" (ddl-of ds "recipe_history")))
          (insert-history! ds first-id 1 "ui")
          (is (thrown? org.sqlite.SQLiteException (insert-history! ds first-id 1 "ui"))))
        (testing "**and the AUTOINCREMENT counter survives, which is not the same as
                  the keyword surviving.** The mark lives in `sqlite_sequence`, that
                  row goes away with the old table, and the copy resets it to the
                  largest id it inserted — so the next Recipe was handed the id of
                  the one deleted above. That is precisely what 009 leans on when it
                  leaves `recipe_id` unconstrained: an id is never reused, so an
                  orphaned event can never come to name a different Recipe."
          (is (= doomed (seq-of ds "recipes")) "the mark is still where it was")
          (let [{:keys [id]} (db.recipe/create-recipe ds nil {:title "After the migration"}
                                                     {:human? true})]
            (is (= (inc doomed) id) "so the new Recipe gets the *next* id, not the
                                     deleted one's")
            (is (not= doomed id)))))
      (finally (clean!)))))

(deftest migration-010-down-really-reverses-and-re-applies
  (let [[ds clean!] (temp-file-db "cb-backfill-down")]
    (try
      (let [{:keys [id]} (db.recipe/create-recipe ds nil {:title "Written after 010"
                                                          :description "body"}
                                                 {:human? true})
            _ (db.recipe/update-recipe ds nil id {:description "body v2"} nil {:human? false})
            doomed (:id (db.recipe/create-recipe ds nil {:title "Deleted"} {:human? true}))
            _ (db.recipe/delete-recipe ds nil doomed {:human? true})
            before (one ds {:select [:*] :from [:recipes] :where [:= :id id]})]

        (migrations/rollback! (:conn ds) "009-recipe-events")

        (testing "the constraint is gone and the column is nullable again"
          (is (not (re-find #"source\s+TEXT NOT NULL" (ddl-of ds "recipes"))))
          (is (not (re-find #"source\s+TEXT NOT NULL" (ddl-of ds "recipe_history"))))
          (is (some? (one ds {:insert-into :recipes
                              :values [{:title "unrecorded again" :source nil}]}))))
        (testing "and it took nothing with it: the rows, their columns, the index and
                  the counter all survive the way down too"
          ;; Key by key for the reason the forward test compares that way, and here
          ;; the missing column is the point rather than an inconvenience: rolling
          ;; back past 012 drops `deleted_at`, which is 012's own `:down` doing its
          ;; job. What must survive is every column that is still there.
          (let [after-down (one ds {:select [:*] :from [:recipes] :where [:= :id id]})]
            (is (= (select-keys before (keys after-down)) after-down)))
          (is (= 2 (:version (one ds {:select [:version] :from [:recipes]
                                      :where [:= :id id]}))))
          (is (= 1 (count (all ds {:select [:*] :from [:recipe_history]
                                   :where [:= :recipe_id id]}))))
          (is (contains? (indexes-on ds "recipes") "idx_recipes_user"))
          ;; `>=` and not `=`: the row inserted a few lines up to prove the column
          ;; is nullable again took the next id, so the mark has legitimately moved
          ;; on. What must never happen is that it moves *backwards* — that is the
          ;; failure that hands a deleted id out again.
          (is (<= doomed (seq-of ds "recipes"))
              "a rollback must not wind the mark back past a deleted id"))
        (testing "no scratch table is left behind by either direction"
          (is (empty? (all ds {:select [:name] :from [:sqlite_master]
                               :where [:or [:like :name "%_rebuilt"]
                                       [:like :name "%_seq_keep"]]}))))

        (migrations/migrate! (:conn ds))

        (testing "and 010 re-applies, backfilling the row the rollback let in — the
                  `:down` cannot record which rows were NULL, so going up again
                  treats them the way it treats any unrecorded version"
          (is (re-find #"source\s+TEXT NOT NULL" (ddl-of ds "recipes")))
          (is (zero? (:n (one ds {:select [[[:count :*] :n]] :from [:recipes]
                                  :where [:is :source nil]}))))
          (is (= "ui" (:source (one ds {:select [:source] :from [:recipes]
                                        :where [:= :title "unrecorded again"]}))))
          (is (<= doomed (seq-of ds "recipes")))
          (testing "and the id a Recipe gets after all that is still a fresh one"
            (let [{:keys [id]} (db.recipe/create-recipe ds nil {:title "Last"}
                                                       {:human? true})]
              (is (< doomed id))))))
      (finally (clean!)))))

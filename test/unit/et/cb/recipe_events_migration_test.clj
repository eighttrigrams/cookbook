(ns et.cb.recipe-events-migration-test
  "Migration 009: the `recipe_events` table, and the four decisions in its DDL
  that a reader would otherwise be free to undo.

  The table is declared for the whole design it belongs to rather than for half of
  it — `'proposed'` and `proposal_id` are accepted here although nothing writes
  either until 010, because widening a SQLite `CHECK` means rebuilding the table.
  There is **no `source` column**, because every event is a machine's and a column
  with one possible value answers nothing. `recipe_id` is deliberately **not** a
  foreign key: events outlive their Recipe. `seen` is a flag and the `CHECK` is what
  keeps it one. And the queue's ordering column is `id`, which is asserted where the
  ordering is served (`inbox-integration-test`) as well as here, where the column
  is."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
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

(defn- tables [ds]
  (set (map :name (jdbc/execute! (db/get-conn ds)
                                 ["SELECT name FROM sqlite_master WHERE type = 'table'"]
                                 db/jdbc-opts))))

(defn- indexes [ds]
  (set (map :name (jdbc/execute! (db/get-conn ds)
                                 ["SELECT name FROM sqlite_master WHERE type = 'index'"]
                                 db/jdbc-opts))))

(defn- columns [ds table]
  (set (map :name (jdbc/execute! (db/get-conn ds) [(str "PRAGMA table_info(" table ")")]
                                 db/jdbc-opts))))

(defn- insert-event!
  "Straight at the table, so a test can write a row no code path produces — a
  fifth `kind`, a `seen` of 2, a `proposed` before anything proposes."
  [ds fields]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:insert-into :recipe_events
                 :values [(merge {:recipe_id 1 :recipe_title "Sourdough" :kind "created"}
                                 fields)]
                 :returning [:id]})
    db/jdbc-opts))

(deftest migration-009-adds-the-events-table-and-its-one-index
  (let [[ds clean!] (temp-file-db "cb-events-up")]
    (try
      (is (contains? (tables ds) "recipe_events"))
      (is (= #{"id" "user_id" "recipe_id" "recipe_title" "kind" "version"
               "proposal_id" "created_at" "seen"}
             (columns ds "recipe_events"))
          "every column the design needs, including the two 010 will use")
      (testing "and no `source` column: the inbox is the record of what the agents
                did, so every event's source is `machine` and a column for it could
                only ever hold one answer — 007's argument about `category_type`"
        (is (not (contains? (columns ds "recipe_events") "source"))))
      (is (contains? (indexes ds) "idx_recipe_events_user_seen")
          "the index is the one query this table exists for: one owner's unseen
           events in append order")
      (testing "and it touched nothing else"
        (is (contains? (tables ds) "recipes"))
        (is (contains? (tables ds) "recipe_history"))
        (is (contains? (tables ds) "scopes"))
        (is (contains? (tables ds) "recipe_scopes"))
        (is (contains? (columns ds "recipes") "view_count")))
      (finally (clean!)))))

(deftest the-kind-check-accepts-the-four-and-refuses-a-fifth
  (let [[ds clean!] (temp-file-db "cb-events-kind")]
    (try
      (doseq [kind ["created" "modified" "deleted" "proposed"]]
        (is (some? (insert-event! ds {:kind kind}))
            (str "'" kind "' is one of the four this table records")))
      (testing "'proposed' among them although nothing writes one until 010 —
                widening a CHECK means rebuilding the table, so it is declared for
                the whole design rather than for half of it"
        (is (some? (insert-event! ds {:kind "proposed" :proposal_id 7}))))
      (testing "and a fifth word is refused by the database rather than by a caller"
        (is (thrown? org.sqlite.SQLiteException (insert-event! ds {:kind "published"})))
        (is (thrown? org.sqlite.SQLiteException (insert-event! ds {:kind "viewed"})))
        (is (thrown? org.sqlite.SQLiteException (insert-event! ds {:kind ""}))))
      (finally (clean!)))))

(deftest seen-is-a-flag-and-starts-unseen
  (let [[ds clean!] (temp-file-db "cb-events-seen")]
    (try
      (let [{:keys [id]} (insert-event! ds {})]
        (is (= 0 (:seen (jdbc/execute-one! (db/get-conn ds)
                          (sql/format {:select [:seen] :from [:recipe_events]
                                       :where [:= :id id]})
                          db/jdbc-opts)))
            "an event arrives unseen — the queue is what he has not been through"))
      (is (some? (insert-event! ds {:seen 1})))
      (testing "and 2 is not a bigger 1: the CHECK is what keeps this a flag rather
                than a small integer somebody starts counting with"
        (is (thrown? org.sqlite.SQLiteException (insert-event! ds {:seen 2})))
        (is (thrown? org.sqlite.SQLiteException (insert-event! ds {:seen -1}))))
      (finally (clean!)))))

(deftest recipe-id-is-not-a-foreign-key-and-user-id-is
  ;; The structural half of 'events outlive their Recipe'. The behavioural half is
  ;; `deleting-a-recipe-makes-a-deleted-event-and-keeps-the-earlier-ones`; this is
  ;; the declaration, which is what a future `ON DELETE CASCADE` would change.
  ;; Nothing enforces foreign keys on this connection either way, so what is being
  ;; pinned is the schema's stated intent — the thing a reader copies.
  (let [[ds clean!] (temp-file-db "cb-events-fk")]
    (try
      (let [fks (jdbc/execute! (db/get-conn ds) ["PRAGMA foreign_key_list(recipe_events)"]
                               db/jdbc-opts)]
        (is (= ["user_id"] (mapv :from fks))
            "user_id points at users, and recipe_id points at nothing on purpose")
        (is (= ["users"] (mapv :table fks))))
      (testing "so an event naming a Recipe that does not exist is a row this
                schema accepts — which is what an orphaned event is"
        (is (some? (insert-event! ds {:recipe_id 99999 :kind "deleted" :version 3}))))
      (finally (clean!)))))

(deftest migration-009-down-really-reverses
  (let [[ds clean!] (temp-file-db "cb-events-down")]
    (try
      (insert-event! ds {})
      (migrations/rollback! (:conn ds) "008-recipe-views")

      (testing "the table and its index are gone, and they took nothing with them"
        (is (not (contains? (tables ds) "recipe_events")))
        (is (not (contains? (indexes ds) "idx_recipe_events_user_seen")))
        (is (contains? (tables ds) "recipes"))
        (is (contains? (tables ds) "recipe_history"))
        (is (contains? (tables ds) "scopes"))
        (is (contains? (tables ds) "recipe_scopes"))
        (is (contains? (columns ds "recipes") "view_count")))

      (testing "and 009 re-applies onto an empty queue: a rollback drops the table,
                so re-migrating cannot restore events nobody stored"
        (migrations/migrate! (:conn ds))
        (is (contains? (tables ds) "recipe_events"))
        (is (zero? (:n (jdbc/execute-one! (db/get-conn ds)
                         (sql/format {:select [[[:count :*] :n]] :from [:recipe_events]})
                         db/jdbc-opts)))))
      (finally (clean!)))))

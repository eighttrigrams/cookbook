(ns et.cb.machine-user-db-test
  "Migration 003 and the db layer under it: the columns, the SQL that keeps the
  machine user single, and a `:down` that genuinely reverses."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.cb.db :as db]
            [et.cb.db.user :as db.user]
            [et.cb.migrations :as migrations]
            [et.cb.test-helpers :as h]))

(use-fixtures :each h/with-in-memory-db)

(defn- columns [ds]
  (set (map :name (jdbc/execute! (db/get-conn ds) ["PRAGMA table_info(users)"] db/jdbc-opts))))

(defn- indexes [ds]
  (set (map :name (jdbc/execute! (db/get-conn ds) ["PRAGMA index_list(users)"] db/jdbc-opts))))

(deftest migration-003-adds-the-identity-columns-and-the-uniqueness-index
  (is (contains? (columns h/*ds*) "is_machine_user"))
  (is (contains? (columns h/*ds*) "for_user_id"))
  (testing "and the column the UI needs to show when the password was last set"
    (is (contains? (columns h/*ds*) "password_set_at")))
  (testing "uniqueness is an index in the schema, not a check in a handler"
    (is (contains? (indexes h/*ds*) "idx_users_single_machine_user"))))

(deftest the-machine-user-is-one-row-and-sql-enforces-it
  (db.user/set-machine-user-password! h/*ds* h/*user-id* "machine-secret")
  (testing "a second machine row is rejected by the database"
    (let [ex (try (jdbc/execute-one! (db/get-conn h/*ds*)
                    (sql/format {:insert-into :users
                                 :values [{:username "another-machine"
                                           :password_hash "irrelevant"
                                           :is_machine_user 1
                                           :for_user_id h/*user-id*}]}))
                  nil
                  (catch Exception e e))]
      (is (some? ex))
      (is (str/includes? (str (ex-message ex)) "UNIQUE"))))
  (testing "even one that names itself something else and points at nobody"
    (is (thrown? Exception
          (jdbc/execute-one! (db/get-conn h/*ds*)
            (sql/format {:insert-into :users
                         :values [{:username "third-machine" :password_hash "x"
                                   :is_machine_user 1 :for_user_id nil}]})))))
  (testing "while ordinary human rows are of course unconstrained"
    (is (some? (db.user/create-user h/*ds* "someone-else" "pw")))))

(deftest setting-the-password-creates-then-updates
  (let [created (db.user/set-machine-user-password! h/*ds* h/*user-id* "first")]
    (testing "the create returns the row, flagged, pointing at the owner"
      (is (= "machine-user" (:username created)))
      (is (= 1 (:is_machine_user created)))
      (is (= h/*user-id* (:for_user_id created)))
      (is (some? (:password_set_at created))))
    (testing "and never the hash — the column is not even selected"
      (is (not (contains? created :password_hash)))
      (is (not (contains? (db.user/get-machine-user h/*ds*) :password_hash))))
    (testing "a reset keeps the same row and re-asserts the owner"
      (let [reset (db.user/set-machine-user-password! h/*ds* h/*user-id* "second")]
        (is (= (:id created) (:id reset)))
        (is (= h/*user-id* (:for_user_id reset)))))
    (testing "and only the new password verifies"
      (is (nil? (db.user/verify-user h/*ds* "machine-user" "first")))
      (is (some? (db.user/verify-user h/*ds* "machine-user" "second"))))
    (testing "verify-user hands the caller the flags login needs, and no hash"
      (let [verified (db.user/verify-user h/*ds* "machine-user" "second")]
        (is (= 1 (:is_machine_user verified)))
        (is (= h/*user-id* (:for_user_id verified)))
        (is (not (contains? verified :password_hash)))))))

(deftest the-dev-owner-is-the-first-human-not-the-first-row
  (testing "with only a machine row besides the owner, the owner is still found"
    (db.user/set-machine-user-password! h/*ds* h/*user-id* "machine-secret")
    (is (= h/*user-id* (:id (db.user/first-human-user h/*ds*)))))
  (testing "and a machine row alone means no human at all — the nil owner of dev"
    (jdbc/execute-one! (db/get-conn h/*ds*)
      (sql/format {:delete-from :users :where [:= :is_machine_user 0]}))
    (is (nil? (db.user/first-human-user h/*ds*)))))

;; ---------------------------------------------------------------------------
;; the :down
;;
;; Its own temp file database: rolling back the suite's shared in-memory schema
;; would take every other test with it.
;;
;; Both tests below roll back **to 002**, not one step. `repl/rollback` with no
;; argument reverses whatever migration happens to be last, which was 003 when
;; these were written and stopped being 003 the moment 004 was added — the tests
;; then asserted about 003's columns after rolling back somebody else's
;; migration. Naming the floor says what they mean and keeps saying it: ragtime's
;; id form is exclusive, so "to 002" is "003 and everything above it, gone".

(defn- temp-file-db
  "A migrated database of its own in a temp directory, and a thunk that deletes it.
  Its own, because rolling back or half-applying the suite's shared in-memory
  schema would take every other test with it."
  [label]
  (let [dir (java.nio.file.Files/createTempDirectory
              label (into-array java.nio.file.attribute.FileAttribute []))
        ds (db/init-conn {:type :sqlite-file :path (str dir "/" label ".db")})]
    [ds (fn []
          (when-let [pc (:persistent-conn ds)] (.close pc))
          (doseq [f (reverse (file-seq (clojure.java.io/file (str dir))))] (.delete f)))]))

(deftest migration-003-is-transactional-so-a-partial-up-leaves-nothing-behind
  ;; This is what dropping `:transactions false` bought, and the reason not to copy
  ;; the flag back in. Only the fourth `:up` statement is idempotent — SQLite has no
  ;; `ADD COLUMN IF NOT EXISTS` — so if a partial `:up` could persist, ragtime would
  ;; not have recorded the migration and the next startup would re-run statement one
  ;; against a column that already exists. That throws out of `db/init-conn`, which
  ;; is the first thing `-main` and `build-app` do: the app would simply not boot,
  ;; and would not boot again.
  ;;
  ;; Provoked rather than asserted about the file: roll 003 back, then add one of
  ;; the columns it wants by hand, so its third statement fails while its first two
  ;; would have succeeded.
  (let [[ds clean!] (temp-file-db "cb-migration-partial")]
    (try
      (migrations/rollback! (:conn ds) "002-recipes")
      (is (not (contains? (columns ds) "is_machine_user")) "003 is rolled back")

      (jdbc/execute-one! (db/get-conn ds)
        ["ALTER TABLE users ADD COLUMN password_set_at DATETIME"])

      (testing "the re-migrate fails on the third statement, as set up"
        (let [ex (try (migrations/migrate! (:conn ds)) nil (catch Exception e e))]
          (is (some? ex))
          (is (str/includes? (str (ex-message ex)) "duplicate column name"))))

      (testing "and the two statements before it did not persist — the whole :up
                rolled back, so the migration is retryable rather than wedged"
        (is (not (contains? (columns ds) "is_machine_user")))
        (is (not (contains? (columns ds) "for_user_id")))
        (is (not (contains? (indexes ds) "idx_users_single_machine_user"))))

      (testing "so once the conflict is out of the way it applies, which is what
                'recoverable' means here"
        (jdbc/execute-one! (db/get-conn ds)
          ["ALTER TABLE users DROP COLUMN password_set_at"])
        (migrations/migrate! (:conn ds))
        (is (contains? (columns ds) "is_machine_user"))
        (is (contains? (columns ds) "for_user_id"))
        (is (contains? (columns ds) "password_set_at"))
        (is (contains? (indexes ds) "idx_users_single_machine_user")))
      (finally (clean!)))))

(deftest migration-003-down-really-reverses
  (let [dir (java.nio.file.Files/createTempDirectory
              "cb-migration-down" (into-array java.nio.file.attribute.FileAttribute []))
        path (str dir "/rollback-test.db")
        ds (db/init-conn {:type :sqlite-file :path path})
        owner (db.user/create-user ds "owner" "pw")]
    (try
      (db.user/set-machine-user-password! ds (:id owner) "machine-secret")
      (is (contains? (columns ds) "is_machine_user"))

      (migrations/rollback! (:conn ds) "002-recipes")

      (testing "all three columns and the index are gone"
        (is (not (contains? (columns ds) "is_machine_user")))
        (is (not (contains? (columns ds) "for_user_id")))
        (is (not (contains? (columns ds) "password_set_at")))
        (is (not (contains? (indexes ds) "idx_users_single_machine_user"))))

      (testing "the machine row goes with them, or it would come back as a human
                user still holding its password — and mint an admin token"
        (is (empty? (jdbc/execute! (db/get-conn ds)
                      (sql/format {:select [:id] :from [:users]
                                   :where [:= :username "machine-user"]})
                      db/jdbc-opts))))

      (testing "the owner and the recipes tables are untouched by the rollback"
        (is (= 1 (count (jdbc/execute! (db/get-conn ds)
                          (sql/format {:select [:id] :from [:users]}) db/jdbc-opts))))
        (is (some? (jdbc/execute-one! (db/get-conn ds)
                     ["SELECT name FROM sqlite_master WHERE type='table' AND name='recipes'"]
                     db/jdbc-opts))))

      (testing "and 003 re-applies cleanly afterwards"
        (migrations/migrate! (:conn ds))
        (is (contains? (columns ds) "is_machine_user"))
        (is (contains? (indexes ds) "idx_users_single_machine_user"))
        (is (nil? (db.user/get-machine-user ds))))
      (finally
        (when-let [pc (:persistent-conn ds)] (.close pc))
        (doseq [f (reverse (file-seq (clojure.java.io/file (str dir))))] (.delete f))))))

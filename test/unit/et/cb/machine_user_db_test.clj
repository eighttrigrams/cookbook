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

(deftest migration-003-down-really-reverses
  (let [dir (java.nio.file.Files/createTempDirectory
              "cb-migration-down" (into-array java.nio.file.attribute.FileAttribute []))
        path (str dir "/rollback-test.db")
        ds (db/init-conn {:type :sqlite-file :path path})
        owner (db.user/create-user ds "owner" "pw")]
    (try
      (db.user/set-machine-user-password! ds (:id owner) "machine-secret")
      (is (contains? (columns ds) "is_machine_user"))

      (migrations/rollback! (:conn ds))

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

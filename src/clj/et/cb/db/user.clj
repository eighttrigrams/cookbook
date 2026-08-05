(ns et.cb.db.user
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]))

(def machine-username
  "The one machine user's name. A literal, deliberately: not configurable, not a
  UI field, not an env var. Cookbook has a single owner and a single machine
  acting for him, so there is nothing to name."
  "machine-user")

(defn create-user [ds username password]
  (let [hash (hashers/derive password)
        result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:insert-into :users
                              :values [{:username username :password_hash hash}]
                              :returning [:id :username :created_at]})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:user-id (:id result) :username username}} "User created")
    result))

(defn get-user-by-username [ds username]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :username :password_hash :created_at
                          :is_machine_user :for_user_id]
                 :from [:users]
                 :where [:= :username username]})
    db/jdbc-opts))

(defn get-user-by-id [ds user-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :username :created_at :is_machine_user :for_user_id]
                 :from [:users]
                 :where [:= :id user-id]})
    db/jdbc-opts))

(defn verify-user [ds username password]
  (when-let [user (get-user-by-username ds username)]
    (when (hashers/check password (:password_hash user))
      (dissoc user :password_hash))))

(defn first-human-user
  "The owner's row, or nil. Excludes the machine user by the flag rather than by
  name: dev's skip-logins resolves the acting user by taking the first row in the
  table, and once a machine row exists it would otherwise be picked as the owner
  — which would silently point the owner's own UI at the machine's user-id and
  empty his shelf."
  [ds]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id] :from [:users]
                 :where [:= :is_machine_user 0]
                 :order-by [[:id :asc]] :limit 1})
    db/jdbc-opts))

;; ---------------------------------------------------------------------------
;; the one machine user

(def ^:private machine-user-columns
  "What may be read back about the machine user. `password_hash` is deliberately
  absent: nothing outside `verify-user` needs it, and a column that is never
  selected cannot be leaked by a handler that forgot to strip it."
  [:id :username :is_machine_user :for_user_id :created_at :password_set_at])

(defn get-machine-user
  "The single machine-user row, or nil when it has not been created yet. Found by
  the `is_machine_user` flag rather than by name, so it is the same row the SQL
  uniqueness index constrains."
  [ds]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select machine-user-columns
                 :from [:users]
                 :where [:= :is_machine_user 1]})
    db/jdbc-opts))

(defn set-machine-user-password!
  "Create the machine user, or reset its password — one operation, because on a
  fixed username those are the same thing. Returns the row, which never carries
  the hash.

  `for-user-id` is the **owner's** id, and it is what lets the machine's token be
  minted in the owner's audience (see `login-handler`). It is re-asserted on a reset,
  so resetting the password repoints a row at whoever the owner is by then.

  In dev it is legitimately **nil**: the owner has no `users` row there, so there
  is no id to store. That does *not* fix itself. Nothing here rewrites the row when
  a human user later appears, and nothing prompts the reset that would — so a nil
  is not read as 'nobody' at the far end either. `login-handler` falls back to the
  first human user when it mints the token, which is what makes a row stored with
  nil follow the owner rather than freeze.

  At most one such row can exist, and that is enforced by a partial unique index
  in migration 003 rather than only here: a uniqueness rule that lives in a
  handler is one concurrent request away from being false."
  [ds for-user-id password]
  (let [conn (db/get-conn ds)
        hash (hashers/derive password)
        existing (get-machine-user ds)
        result (if existing
                 (jdbc/execute-one! conn
                   (sql/format {:update :users
                                :set {:password_hash hash
                                      :for_user_id for-user-id
                                      :password_set_at [:raw "datetime('now')"]}
                                :where [:= :id (:id existing)]
                                :returning machine-user-columns})
                   db/jdbc-opts)
                 (jdbc/execute-one! conn
                   (sql/format {:insert-into :users
                                :values [{:username machine-username
                                          :password_hash hash
                                          :is_machine_user 1
                                          :for_user_id for-user-id
                                          :password_set_at [:raw "datetime('now')"]}]
                                :returning machine-user-columns})
                   db/jdbc-opts))]
    (tel/log! {:level :info :data {:user-id (:id result) :for-user-id for-user-id
                                   :created (nil? existing)}}
              "Machine user password set")
    result))

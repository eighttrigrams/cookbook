(ns et.cb.db.user
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]))

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
    (sql/format {:select [:id :username :password_hash :created_at]
                 :from [:users]
                 :where [:= :username username]})
    db/jdbc-opts))

(defn get-user-by-id [ds user-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :username :created_at]
                 :from [:users]
                 :where [:= :id user-id]})
    db/jdbc-opts))

(defn verify-user [ds username password]
  (when-let [user (get-user-by-username ds username)]
    (when (hashers/check password (:password_hash user))
      (dissoc user :password_hash))))

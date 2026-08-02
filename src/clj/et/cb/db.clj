(ns et.cb.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [et.cb.migrations :as migrations]
            [clojure.string :as str]
            [honey.sql :as sql]
            [buddy.hashers :as hashers]
            [taoensso.telemere :as tel]))

(def jdbc-opts {:builder-fn rs/as-unqualified-maps})

(defn- ensure-admin-user!
  "In production the app requires an ADMIN_PASSWORD-backed admin user to log in
  and to own data. Seed it (or re-sync its password) from the env var so the
  login password always matches the deploy secret. No-op in dev."
  [conn]
  (when-let [admin-pw (System/getenv "ADMIN_PASSWORD")]
    (if-let [admin (jdbc/execute-one! conn
                     (sql/format {:select [:id :password_hash] :from [:users]
                                  :where [:= :username "admin"]})
                     jdbc-opts)]
      (when-not (hashers/check admin-pw (:password_hash admin))
        (jdbc/execute-one! conn
          (sql/format {:update :users :set {:password_hash (hashers/derive admin-pw)}
                       :where [:= :id (:id admin)]}))
        (tel/log! :info "Synced admin password with ADMIN_PASSWORD"))
      (do
        (jdbc/execute-one! conn
          (sql/format {:insert-into :users
                       :values [{:username "admin" :password_hash (hashers/derive admin-pw)}]})
          jdbc-opts)
        (tel/log! :info "Seeded admin user")))))

(defn init-conn [{:keys [type path]}]
  (let [db-spec (case type
                  :sqlite-memory {:dbtype "sqlite" :dbname "file::memory:?cache=shared&busy_timeout=5000&read_uncommitted=true"}
                  ;; busy_timeout so a connection waits for a writer to finish
                  ;; instead of failing the request outright: the annotation
                  ;; writes are transactions, and concurrent PUTs would
                  ;; otherwise meet SQLITE_BUSY.
                  :sqlite-file {:dbtype "sqlite" :dbname (str path "?busy_timeout=5000")})
        ds (jdbc/get-datasource db-spec)
        ;; A shared-cache in-memory DB is dropped the instant its last
        ;; connection closes, so hold one open for the process lifetime purely
        ;; to keep it alive. Request traffic still uses fresh per-op connections.
        persistent-conn (when (= type :sqlite-memory) (jdbc/get-connection ds))]
    (migrations/migrate! ds)
    (ensure-admin-user! ds)
    {:conn ds
     :persistent-conn persistent-conn
     :type type}))

(defn get-conn [ds]
  (if (map? ds) (:conn ds) ds))

(defn user-id-where-clause [user-id]
  (if user-id
    [:= :user_id user-id]
    [:is :user_id nil]))

(defn build-search-clause
  "Case-insensitive AND-of-terms substring match across `columns`."
  ([search-term] (build-search-clause search-term [:name]))
  ([search-term columns]
   (when (and search-term (not (str/blank? search-term)))
     (let [terms (->> (str/split (str/trim search-term) #"\s+")
                      (map str/lower-case)
                      (filter (complement str/blank?)))]
       (when (seq terms)
         (into [:and]
               (map (fn [term]
                      (into [:or]
                            (map (fn [col] [:like [:lower col] (str "%" term "%")]) columns)))
                    terms)))))))

(defn reset-all-data!
  "Dev-only: wipe user data (keeps schema). Child rows first, so nothing is left
  pointing at a row that is already gone."
  [ds]
  (let [conn (get-conn ds)]
    (doseq [table [:recipe_history :recipes]]
      (jdbc/execute-one! conn (sql/format {:delete-from table})))))

(ns et.cb.migrations
  (:require [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.repl :as repl]
            [next.jdbc :as jdbc]))

(defn- wrap-connectable [conn-or-ds]
  (if (instance? java.sql.Connection conn-or-ds)
    (jdbc/with-options conn-or-ds {})
    conn-or-ds))

(defn- silent-reporter [& _])

(defn- migration-config [connectable]
  {:datastore (ragtime-jdbc/sql-database (wrap-connectable connectable))
   :migrations (ragtime-jdbc/load-resources "migrations/net/et/cb")
   :reporter silent-reporter})

(defn migrate!
  [connectable]
  (let [config (migration-config connectable)]
    (repl/migrate config)))

(defn rollback!
  [connectable]
  (let [config (migration-config connectable)]
    (repl/rollback config)))

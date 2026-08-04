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
  "Roll the datastore back one migration, or as far as `amount-or-id` says —
  ragtime's own two readings of that argument: a count, or the id to roll back
  *to*, which is exclusive of the named migration itself.

  The id form is what a test naming one particular migration wants. The count
  form silently means something different every time a migration is added, since
  what 'one back' reverses is whatever happens to be last."
  ([connectable] (rollback! connectable 1))
  ([connectable amount-or-id]
   (repl/rollback (migration-config connectable) amount-or-id)))

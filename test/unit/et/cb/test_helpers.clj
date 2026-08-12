(ns et.cb.test-helpers
  (:require [et.cb.db :as db]
            [et.cb.db.user :as db.user]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]))

(tel/remove-handler! :default/console)
(let [log-dir (io/file "logs")
      log-file (io/file "logs/cookbook.tests.log")]
  (.mkdirs log-dir)
  (when (.exists log-file) (.delete log-file))
  (tel/add-handler! :test-file (tel/handler:file {:path "logs/cookbook.tests.log"})))

(def ^:dynamic *ds* nil)
(def ^:dynamic *user-id* nil)

(defn with-in-memory-db [f]
  (let [conn (db/init-conn {:type :sqlite-memory})]
    (try
      (let [user (db.user/create-user conn "default-test-user" "testpass")]
        (binding [*ds* conn
                  *user-id* (:id user)]
          (f)))
      (finally
        (when-let [pc (:persistent-conn conn)]
          (.close pc))))))

(defn backdate-published-at!
  "Put a distinguishable timestamp on a published row. `datetime('now')` is
  second-resolution, so a second publish in the same second would leave the
  stamp looking untouched even if it had rewritten it — this is what gives
  'published_at does not move' something to bite on."
  [recipe-id value]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :recipes
                 :set {:published_at value}
                 :where [:= :id recipe-id]})))

(defn backdate-modified-at!
  "Put a distinguishable timestamp on a row's `modified_at`, for the same reason
  `backdate-published-at!` exists: `datetime('now')` is second-resolution, so a
  save in the same second as the write before it would leave the stamp looking
  untouched whether or not it was rewritten. This is what gives 'a tags-only save
  does move modified_at' something to bite on."
  [recipe-id value]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :recipes
                 :set {:modified_at value}
                 :where [:= :id recipe-id]})))

(defn backdate-deleted-at!
  "Put a distinguishable tombstone stamp on a row, for the same reason
  `backdate-published-at!` exists: `datetime('now')` is second-resolution, so two
  Recipes deleted inside one second cannot show an ordering to assert on."
  [recipe-id value]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :recipes
                 :set {:deleted_at value}
                 :where [:= :id recipe-id]})))

(defn history-row-count
  "Straight at the table, so a test can tell 'the API stopped showing them' from
  'the rows are gone'."
  [recipe-id]
  (-> (jdbc/execute-one! (db/get-conn *ds*)
        (sql/format {:select [[[:count :*] :n]]
                     :from [:recipe_history]
                     :where [:= :recipe_id recipe-id]})
        db/jdbc-opts)
      :n))

(defn event-rows
  "Every row of `recipe_events` in append order, or only the ones naming one
  Recipe. Straight at the table for the reason `history-row-count` is: an event
  outlives its Recipe, so 'the inbox stopped showing it' and 'the row is gone' are
  two different facts and only a read of the table can tell them apart.

  `SELECT *`, deliberately, unlike every projection in `src`: a test asserting that
  the inbox is empty after one of the owner's own writes must not be able to pass
  because it named the wrong columns.

  Ordered by `id` here as everywhere: the stamp is second-resolution, so a test
  that ordered on it would be asserting against a tie."
  ([] (event-rows nil))
  ([recipe-id]
   (jdbc/execute! (db/get-conn *ds*)
     (sql/format {:select [:*]
                  :from [:recipe_events]
                  :where (if recipe-id [:= :recipe_id recipe-id] [:inline true])
                  :order-by [[:id :asc]]})
     db/jdbc-opts)))

(defn clear-human-edit-bit!
  "Put `has_human_edit` back to 0 on a Recipe that has a `ui` version — the shape
  every Recipe he typed by hand had before migration 004, and one no write can produce
  any more: the bit is only ever set, and 010 brought it up wherever a version reads
  `ui`.

  Reaches past the writers for the reason `clear-source!` did, and it is the only way
  to ask the one question that matters here: **does the approval gate read the
  versions or the bit?** Those two agree on every row a running app can make, so a
  test that did not manufacture this state could not tell a gate reading
  `machine_versions = version` from one reading `has_human_edit = 0` — which is
  precisely the mistake the order warned against, and precisely what a mutation run
  found this file unable to catch."
  [recipe-id]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:update :recipes :set {:has_human_edit 0} :where [:= :id recipe-id]})))

(defn in-transaction
  "Run `f` with a transaction on the fixture's datasource, for the handful of db
  functions that take a `tx` rather than a `ds` — the ones that are deliberately only
  ever part of a larger write, like `db.proposal/resolve!`. A test calling those
  directly has to open the transaction the real caller would."
  [f]
  (jdbc/with-transaction [tx (db/get-conn *ds*)] (f tx)))

(defn insert-scope-row!
  "An association written straight at the join table, bypassing
  `db.scope/set-recipe-scopes!` and the ownership intersection it does.

  Reaches past the writer for the same reason `clear-source!` does: no request can
  produce this row — a caller's Scope ids are intersected with their own — and a
  test about what the *reader* narrows on has nothing to read otherwise."
  [recipe-id scope-id]
  (jdbc/execute-one! (db/get-conn *ds*)
    (sql/format {:insert-into :recipe_scopes
                 :values [{:recipe_id recipe-id :scope_id scope-id}]})))

(defn scope-row-count
  "Rows in `recipe_scopes`, optionally only those naming one recipe or one Scope.
  Straight at the join table for the same reason `history-row-count` is: nothing
  enforces the foreign keys here, so 'the parent row is gone' and 'the join rows
  are gone' are two different facts and only this one can check the second. A
  delete that left orphans behind would pass every assertion made through a
  handler."
  ([] (scope-row-count nil nil))
  ([recipe-id scope-id]
   (-> (jdbc/execute-one! (db/get-conn *ds*)
         (sql/format {:select [[[:count :*] :n]]
                      :from [:recipe_scopes]
                      :where (cond-> [:and]
                               recipe-id (conj [:= :recipe_id recipe-id])
                               scope-id (conj [:= :scope_id scope-id])
                               (and (nil? recipe-id) (nil? scope-id)) (conj [:inline true]))})
         db/jdbc-opts)
       :n)))

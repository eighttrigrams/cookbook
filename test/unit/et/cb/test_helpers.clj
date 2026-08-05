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

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

;; Numbers the in-memory databases apart — see `memory-db-name`. `defonce` and
;; not `def`, so reloading this namespace in a running REPL cannot hand out a
;; name that is already open.
(defonce ^:private memory-db-counter (atom 0))

(defn- memory-db-name
  "A **private** in-memory database, one per call.

  `file::memory:?cache=shared` — what this used to be — is not one database per
  call, it is *the* one shared-cache in-memory database of the whole JVM. Every
  test fixture opened that same database and only looked isolated because SQLite
  destroys such a database when its last connection closes, which is what a
  fixture's `(.close (:persistent-conn …))` happened to do. Leave one extra
  connection open to it — the `persistent-conn` of a second datasource, an
  `ensure-ds` nobody reset, an interrupted run whose `finally` did not fire, an
  nREPL session in the same JVM — and the next fixture meets the previous one's
  rows: `[SQLITE_CONSTRAINT_UNIQUE] users.username`, a JDBC error rather than a
  failed assertion, blamed on whichever test happened to run there.

  A **name** in front of `?mode=memory` gives a database of its own per name, so
  two callers cannot share one however many connections are open. `cache=shared`
  stays: it is what lets the *same* caller's per-op connections see each other's
  writes, which is the whole reason this type exists."
  []
  (str "file:cookbook-mem-" (swap! memory-db-counter inc)
       "?mode=memory&cache=shared&busy_timeout=5000&read_uncommitted=true"))

(defn init-conn [{:keys [type path]}]
  (let [db-spec (case type
                  :sqlite-memory {:dbtype "sqlite" :dbname (memory-db-name)}
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

(defn user-id-where-clause
  "Whose rows a caller may see, as a `:where` clause — and **the one place the NULL
  case is handled**. A nil user-id is a real owner in this schema (dev's owner has no
  `users` row), so it needs `IS NULL` rather than `= NULL`, which matches nothing.
  Nothing in this codebase writes `= user-id` by hand for that reason.

  The two-argument form names the column, for a query where `user_id` is ambiguous —
  a join whose both sides have one — and it exists so that such a query can still
  ask this question here instead of inlining its own comparison. Same rule, same
  implementation; only the column moves."
  ([user-id] (user-id-where-clause :user_id user-id))
  ([column user-id]
   (if user-id
     [:= column user-id]
     [:is column nil])))

(def word-separator-chars
  "What ends a word for `build-word-prefix-search-clause`.

  **The call:** a word is a run of letters and digits, and *every* other ASCII
  character ends one — whitespace, but punctuation too. One rule with no
  exceptions to remember, and it is the one that makes the real titles behave:
  `re-heating` is two words, so `heating` finds it, and `make/start` is two, so
  `start` does. A curated list would have to answer why `.` separates and `+`
  does not.

  Anything **outside** ASCII is a word character, deliberately: `Käse` stays one
  word, so `se` does not find it. Treating every non-alphanumeric byte as a
  separator would have made every accented letter a word boundary."
  (str " \t\n\r"
       "!\"#$%&'()*+,-./"
       ":;<=>?@"
       "[\\]^_`"
       "{|}~"))

(defn word-prefix-term-clause
  "**One** term's condition over `columns`: true when `term` is the prefix of some
  word in some one of them. An `[:or ...]` of `instr` tests, one per column per
  separator.

  `term` is expected lower-cased already, which is what
  `build-word-prefix-search-clause` hands it — the `lower` in here is on the
  column, and a term with an upper-case letter would simply never match.

  **Split out so that a term can be asked of columns in another table.** The
  caller that wants that is `db.scope/search-clause`, which puts this same
  condition on `scopes.title` and `scopes.tags` inside an `EXISTS` — so a Scope's
  words are word-prefix-matched by the identical rule as a Recipe's, rather than by
  a second implementation that could come to disagree about what a word is. There
  is one definition of the match and two places it is applied."
  [term columns]
  (into [:or]
        (mapcat (fn [column]
                  (let [value [:|| [:inline " "] [:lower column]]]
                    (map (fn [separator]
                           [:> [:instr value (str separator term)] [:inline 0]])
                         word-separator-chars)))
                columns)))

(defn build-word-prefix-search-clause
  "Case-insensitive AND-of-terms **word-prefix** match over `columns`, a vector
  of them.

  The search string splits on whitespace, and a row matches when *every* term is
  a prefix of *some* word in *some* column: `ab cd` matches `abc cde` — `ab`
  prefixes `abc`, `cd` prefixes `cde` — but not `ad cd`, since `ab` prefixes
  neither word. A prefix is not a substring, so `cd` does not match `abcd`. What
  counts as a word is `word-separator-chars`.

  **The terms are ANDed and each one may land in a different column.** With
  `[:title :tags]`, a recipe titled `Sourdough starter` tagged `bread baking`
  matches `sour bak` — `sour` prefixes a word of the title and `bak` a word of
  the tags — and it matches `star sour` and `bread bak` just as well. What it
  does *not* mean is that one column has to carry them all: a search is a set of
  conditions on the row, not on any one field of it. The vector-of-columns shape
  is tracker's `build-search-clause`; the word semantics above are cookbook's own
  and are what stays.

  Matching is **literal**: it goes through SQLite's `instr` rather than `LIKE`,
  so `%` and `_` in a term are the characters they look like rather than
  wildcards — searching `%` looks for a word starting with `%` instead of
  matching every row — and there is no escaping to get right. Prepending a space
  to each value turns 'at the start of the value' into 'after a separator', so
  the first word of each column needs no case of its own.

  **`extra-disjunct-fn` gives each term's `[:or ...]` one more branch**, and it is
  how a term may be satisfied somewhere a vector of columns on this row cannot
  name. It is called with the term — lower-cased, as the columns' own tests get it
  — and its clause is ORed in beside them. `db.recipe/list-recipes` passes one that
  reaches through `recipe_scopes` into the Scopes a Recipe is filed under, so
  `ab utw` can take `ab` from the title and `utw` from a Scope's, and neither term
  has to know where the other landed.

  **Per term and not per search**, which is this function's own shape applied once
  more: every term must be satisfied *somewhere*, and where each one lands is its
  own business. A single extra clause ANDed onto the whole search would mean
  something else entirely — *and it is also filed under something matching* — and
  would make `sour bak` on a tagged Recipe stop matching. nil for no extra branch,
  and then this is exactly the two-argument function it has always been.

  nil for a blank search, which every caller reads as 'no narrowing'."
  ([search-term columns] (build-word-prefix-search-clause search-term columns nil))
  ([search-term columns extra-disjunct-fn]
   (when-not (str/blank? search-term)
     (let [terms (->> (str/split (str/trim search-term) #"\s+")
                      (remove str/blank?)
                      (map str/lower-case))]
       (when (seq terms)
         (into [:and]
               (map (fn [term]
                      (cond-> (word-prefix-term-clause term columns)
                        extra-disjunct-fn (conj (extra-disjunct-fn term))))
                    terms)))))))

(defn reset-all-data!
  "Dev-only: wipe user data (keeps schema). Child rows first, so nothing is left
  pointing at a row that is already gone — and that ordering is the whole reason
  this is a list and not a `DELETE FROM` per table wherever it was convenient:
  nothing enforces a foreign key on this connection, so the order here *is* the
  referential integrity.

  `recipe_scopes` and `scopes` are in it because a Scope is user data too. Leaving
  the join rows would strand them against deleted Recipes, and leaving the Scopes
  would make 'reset' mean 'reset except the filing' — a fixture that half-resets
  is one a test can pass because of the half that stayed.

  **`recipe_events` is in it although `db.recipe/delete-recipe` deliberately leaves
  a Recipe's events behind**, and the two are not in tension: there, an event is the
  record that something happened to a Recipe that really did happen, so it outlives
  the Recipe; here, the whole store is being wiped and an inbox full of entries
  pointing at Recipes that never existed is not a record of anything. A reset that
  spared them would also be exactly the half-reset this docstring warns about — the
  next test's queue would open on the last test's events.

  `recipe_proposals` goes before `recipes` for the reason the ordering exists at all:
  it points at one, and nothing enforces the foreign key. It is also the table whose
  survival would be least visible — a resolved proposal is invisible to every read,
  and a *pending* one left behind would go on blocking an agent from writing a Recipe
  that no longer exists."
  [ds]
  (let [conn (get-conn ds)]
    (doseq [table [:recipe_history :recipe_scopes :recipe_events :recipe_proposals
                   :recipes :scopes]]
      (jdbc/execute-one! conn (sql/format {:delete-from table})))))

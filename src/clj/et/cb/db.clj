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

  nil for a blank search, which every caller reads as 'no narrowing'."
  [search-term columns]
  (when-not (str/blank? search-term)
    (let [terms (->> (str/split (str/trim search-term) #"\s+")
                     (remove str/blank?)
                     (map str/lower-case))]
      (when (seq terms)
        (into [:and]
              (map (fn [term]
                     (into [:or]
                           (mapcat (fn [column]
                                     (let [value [:|| [:inline " "] [:lower column]]]
                                       (map (fn [separator]
                                              [:> [:instr value (str separator term)] [:inline 0]])
                                            word-separator-chars)))
                                   columns)))
                   terms))))))

(defn reset-all-data!
  "Dev-only: wipe user data (keeps schema). Child rows first, so nothing is left
  pointing at a row that is already gone — and that ordering is the whole reason
  this is a list and not a `DELETE FROM` per table wherever it was convenient:
  nothing enforces a foreign key on this connection, so the order here *is* the
  referential integrity.

  `recipe_scopes` and `scopes` are in it because a Scope is user data too. Leaving
  the join rows would strand them against deleted Recipes, and leaving the Scopes
  would make 'reset' mean 'reset except the filing' — a fixture that half-resets
  is one a test can pass because of the half that stayed."
  [ds]
  (let [conn (get-conn ds)]
    (doseq [table [:recipe_history :recipe_scopes :recipes :scopes]]
      (jdbc/execute-one! conn (sql/format {:delete-from table})))))

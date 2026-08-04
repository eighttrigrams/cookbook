(ns et.cb.db.recipe
  "The one entity: a Recipe — a `title`, a `useful_when` line, and a
  `description` body.

  **Lean by default.** The reader here is an agent: it scans title and
  useful-when to decide whether a Recipe is relevant, then fetches exactly one
  description. So the default projection is a cheap retrieval index, and it is
  built as a *select-column* choice rather than a post-hoc dissoc — a lean read
  never loads the description, so there is no key for a caller to leak.

  **History model** follows treina's `et.trn.db.program`, which follows
  rhizome's: `recipes` always holds the *current* state and `recipe_history`
  holds the superseded ones, keyed (recipe_id, version). A save pushes the
  outgoing state into history first, so the newest history row is the state just
  before the current one. Version numbers grow with each save and never change
  afterwards, which is what makes 'how did this read back then' answerable.

  One deliberate change from treina: **the version lives on the row** instead of
  being derived as `(inc (max history.version))`. Treina can derive it because
  its `program` is a singleton per user; recipes are a collection, so deriving
  would mean a correlated subquery per row in every listing. A new recipe is
  version 1 with no history rows; a save archives the outgoing state *at its own
  version number* and moves the row to the next one. History therefore holds
  1..N-1 and the row is N.

  `published` is deliberately **not** in the history table, and publishing does
  not create a version: versions are about content, the latch is a separate fact
  about the row. A table that half-answered both questions would answer neither.

  **Provenance** is one bit on the row too: `has_human_edit`, set by a write from
  a caller that is not a machine and never cleared. It is denormalised for the
  same reason the version is — deriving it would mean a correlated subquery per
  row in every listing — and unlike most denormalisation it cannot go stale,
  because the fact is monotonic: once a human has edited a Recipe, that never
  stops being true. It is a fact about the Recipe and not about a version; who
  wrote v3 is a question this schema does not answer. The bit only exists going
  forward from the migration that added it, so a row that predates it reads 0
  until it is next saved from the UI."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]))

(def lean-select-columns
  "Everything but the body. This *is* the default API shape."
  [:id :title :useful_when :version :published :published_at :created_at :modified_at
   :has_human_edit])

(defn- select-columns [lean?]
  (if lean? lean-select-columns (conj lean-select-columns :description)))

(def visitor-scope
  "What an anonymous caller may read: the published recipes, whoever owns them.

  A visitor has no user-id, and `db/user-id-where-clause` reads a missing one as
  `user_id IS NULL` — a real category in this schema, not an empty one — so a
  visitor described by a nil user-id would quietly be served the nil-owner's
  rows. This marker keeps a visitor's query from ever naming an owner, and it
  narrows on `published` in the query itself, so an unpublished row is outside
  the result set rather than filtered out of it afterwards."
  ::visitor)

(defn- scope-clause [scope]
  (if (= scope visitor-scope)
    [:= :published 1]
    (db/user-id-where-clause scope)))

(defn- published? [recipe]
  (= 1 (:published recipe)))

(defn list-recipes
  "The recipes visible in `scope` — a user-id for their owner, `visitor-scope`
  for an anonymous caller — most recently touched first, optionally narrowed by
  a **word-prefix search over the title**. `lean?` (the default) leaves the
  description out of the projection entirely.

  Every whitespace-separated term of the search has to be the prefix of some
  word in the title, case-insensitively — see
  `db/build-word-prefix-search-clause` for what a word is. Neither useful-when
  nor the description is searched: the title is the name of the thing, and a
  match in a line of prose was never what made a recipe the one you meant.

  `human-only?` narrows to the Recipes that carry a human edit — the
  `has_human_edit` bit described above. It composes with the search rather than
  competing with it: both are clauses on the same query.

  Both narrowings are `:where` clauses and not filters over the rows, so they
  narrow *inside* the scope they are given. A visitor's search runs against the
  published recipes rather than against everything followed by a hiding step, and
  so does a visitor's human filter — it can only ever take rows away from what
  that caller could already see."
  ([ds scope] (list-recipes ds scope {}))
  ([ds scope {:keys [search-term human-only? lean?] :or {lean? true}}]
   (let [search-clause (db/build-word-prefix-search-clause search-term :title)
         where (cond-> [:and (scope-clause scope)]
                 search-clause (conj search-clause)
                 human-only? (conj [:= :has_human_edit 1]))]
     (jdbc/execute! (db/get-conn ds)
       (sql/format {:select (select-columns lean?)
                    :from [:recipes]
                    :where where
                    :order-by [[:modified_at :desc] [:id :desc]]})
       db/jdbc-opts))))

(defn get-recipe
  "One recipe visible in `scope` — see `list-recipes` — or nil. Lean like the
  listing unless asked otherwise."
  ([ds scope id] (get-recipe ds scope id {}))
  ([ds scope id {:keys [lean?] :or {lean? true}}]
   (jdbc/execute-one! (db/get-conn ds)
     (sql/format {:select (select-columns lean?)
                  :from [:recipes]
                  :where [:and [:= :id id] (scope-clause scope)]})
     db/jdbc-opts)))

(defn create-recipe
  "A new recipe: version 1, no history rows, and private — `published` is left
  at its column default, because publishing is its own deliberate act.

  `:human?` records that this came from a caller that is not a machine — see
  `update-recipe` for what that means and why it is the fact worth recording. It
  defaults to false, which is the conservative reading: a caller that says nothing
  about itself leaves the row of unknown provenance rather than claiming the
  owner's hand for it."
  ([ds user-id fields] (create-recipe ds user-id fields {}))
  ([ds user-id {:keys [title useful_when description]} {:keys [human?]}]
   (let [result (jdbc/execute-one! (db/get-conn ds)
                  (sql/format {:insert-into :recipes
                               :values [{:title (str/trim title)
                                         :useful_when (or useful_when "")
                                         :description (or description "")
                                         :version 1
                                         :has_human_edit (if human? 1 0)
                                         :user_id user-id}]
                               :returning (select-columns false)})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:id (:id result) :user-id user-id :human? (boolean human?)}}
               "Recipe created")
     result)))

(defn- content-of [recipe]
  (select-keys recipe [:title :useful_when :description]))

(defn- merge-content
  "A field the caller left out keeps its current value, so an edit meant for one
  field cannot silently clear the other two."
  [current {:keys [title useful_when description]}]
  {:title (if (some? title) (str/trim title) (:title current))
   :useful_when (if (some? useful_when) useful_when (:useful_when current))
   :description (if (some? description) description (:description current))})

(defn- archive! [tx current]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :recipe_history
                 :values [{:recipe_id (:id current)
                           :version (:version current)
                           :title (:title current)
                           :useful_when (:useful_when current)
                           :description (:description current)}]})))

(defn update-recipe
  "Save the given fields as the new current state and archive the outgoing one.

  Returns nil when `expected-modified-at` no longer matches (someone else saved
  meanwhile) — the house's optimistic-concurrency shape. A save that changes
  nothing is returned unchanged: it neither bumps the version nor writes a
  history row, since identical versions would only add empty steps to walk
  through.

  Callers must have established that the recipe exists; nil here means the
  version guard failed, not that the id was wrong.

  `:human?` — this save came from a caller carrying no *machine* token — sets
  `has_human_edit` on the row, on the same statement that bumps the version. Three
  things follow from where that assignment sits. The flag is only ever set and
  never written back to 0, so a machine saving afterwards cannot take back what a
  human recorded. The no-op branch above returns before it, so a save that changes
  nothing does not earn the mark. And publishing is a different function
  altogether, which is right: the latch is not a content change and a published
  Recipe is not thereby a human-written one.

  The recorded fact is deliberately 'not a machine token' rather than 'came from
  the browser'. Today the web UI is the only client that authenticates as the
  human — `cookbook-tui` logs in as `machine-user`, so its writes count as a
  machine's — and a token is checkable where a claim about a browser is not."
  ([ds user-id id fields expected-modified-at]
   (update-recipe ds user-id id fields expected-modified-at {}))
  ([ds user-id id fields expected-modified-at {:keys [human?]}]
   (jdbc/with-transaction [tx (db/get-conn ds)]
     (let [current (get-recipe tx user-id id {:lean? false})
           incoming (merge-content current fields)]
       (cond
         (and expected-modified-at (not= expected-modified-at (:modified_at current)))
         nil

         (= incoming (content-of current))
         current

         :else
         (do
           (archive! tx current)
           (let [result (jdbc/execute-one! tx
                          (sql/format {:update :recipes
                                       :set (cond-> (assoc incoming
                                                           :version (inc (:version current))
                                                           :modified_at [:raw "datetime('now')"])
                                              human? (assoc :has_human_edit 1))
                                       :where [:= :id id]
                                       :returning (select-columns false)})
                          db/jdbc-opts)]
             (tel/log! {:level :info :data {:id id :user-id user-id
                                            :version (:version result)
                                            :human? (boolean human?)}}
                       "Recipe saved")
             result)))))))

(defn publish-recipe
  "Set the latch on a recipe the user owns: `published` on, `published_at`
  stamped. One way — there is no unpublish, because un-latching would hand a
  machine back the right to rewrite something the owner had signed.

  Publishing an already-published recipe returns it untouched: the first publish
  is the fact being recorded, so `published_at` never moves. It is not a content
  change either — no version bump and no history row — and it deliberately
  leaves `modified_at` alone, so an edit the owner already has in flight is not
  turned into a 409 by it.

  It does not set `has_human_edit` either, for the same reason it writes no
  version: that bit says a human wrote the text, and putting your name to text an
  agent wrote is not writing it.

  nil when the id matches nothing the user owns."
  [ds user-id id]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (when-let [current (get-recipe tx user-id id {:lean? false})]
      (if (published? current)
        current
        (let [result (jdbc/execute-one! tx
                       (sql/format {:update :recipes
                                    :set {:published 1
                                          :published_at [:raw "datetime('now')"]}
                                    :where [:= :id id]
                                    :returning (select-columns false)})
                       db/jdbc-opts)]
          (tel/log! {:level :info :data {:id id :user-id user-id}} "Recipe published")
          result)))))

(defn delete-recipe
  "Remove a recipe the user owns together with its whole version history.
  History rows first, like every other delete path in the suite — foreign keys
  are not enforced on this connection, so ON DELETE CASCADE would be a promise
  nothing keeps."
  [ds user-id id]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (let [own [:and [:= :id id] (db/user-id-where-clause user-id)]]
      (when (jdbc/execute-one! tx
              (sql/format {:select [:id] :from [:recipes] :where own})
              db/jdbc-opts)
        (jdbc/execute-one! tx (sql/format {:delete-from :recipe_history
                                           :where [:= :recipe_id id]}))
        (jdbc/execute-one! tx (sql/format {:delete-from :recipes :where own}))
        (tel/log! {:level :info :data {:id id :user-id user-id}} "Recipe deleted")
        {:success true}))))

(defn list-versions
  "Every state of a recipe, newest first. The current row is included as version
  N and flagged `:current true`, so a reader can step from today's text back
  through the history in one uniform list. nil when the id matches nothing the
  user owns."
  [ds user-id id]
  (when-let [current (get-recipe ds user-id id {:lean? false})]
    (let [history (jdbc/execute! (db/get-conn ds)
                    (sql/format {:select [:version :title :useful_when :description :created_at]
                                 :from [:recipe_history]
                                 :where [:= :recipe_id id]
                                 :order-by [[:version :desc]]})
                    db/jdbc-opts)]
      {:versions (into [(assoc (content-of current)
                               :version (:version current)
                               :created_at (:modified_at current)
                               :current true)]
                       history)
       :total (inc (count history))})))

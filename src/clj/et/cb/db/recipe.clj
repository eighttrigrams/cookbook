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
  stops being true. It is a fact about the Recipe and not about a version. The bit
  only exists going forward from the migration that added it, so a row that
  predates it reads 0 until it is next saved from the UI.

  **Per-version provenance** is `source`, and it is the question the bit
  deliberately did not answer: who wrote v3. It follows rhizome, which keeps the
  same column on `items` and on `history`, so it sits where the version it
  describes sits — on the row for the current version, on each history row for the
  superseded ones. Its values are `'ui'` and `'machine'`, plus NULL for a version
  written before the column existed, which is a third category and a synonym for
  neither (see migration 005).

  The bit stays, and the two cannot disagree: `has_human_edit` is true exactly
  when some version reads `'ui'`, and the same write sets both. Keeping the bit is
  what keeps `?human=true` a plain `:where` on the row instead of an aggregate
  over history on every listing read — the thing this namespace avoided when it
  put the version number on the row.

  The one ordering that matters is in `archive!`: a save pushes the outgoing
  version into history together with **its own** source, and only the statement
  after that stamps the row with the new save's."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]))

(def lean-select-columns
  "Everything but the body. This *is* the default API shape."
  [:id :title :useful_when :version :published :published_at :created_at :modified_at
   :has_human_edit :source])

(defn- select-columns [lean?]
  (if lean? lean-select-columns (conj lean-select-columns :description)))

(defn- qualify
  "The same columns, `recipes.`-prefixed. Only the listing needs this, and only
  because the provenance join below puts a second table in scope that has a column
  of the same name for most of them — `title`, `useful_when`, `description`,
  `version`, `created_at` and `source` are all on `recipe_history` too. Read back
  through `db/jdbc-opts`, which builds unqualified maps, so the shape a caller
  sees is exactly what it was."
  [columns]
  (mapv #(keyword (str "recipes." (name %))) columns))

(defn- versions-with-source
  "One bucket of the provenance split, as a SQL expression over the joined
  `recipe_history`: how many of a recipe's versions carry `label` — the history
  rows, plus the current row itself when it matches. `nil` asks for the unrecorded
  bucket, which needs `IS NULL` rather than a comparison with NULL.

  The `recipe_id IS NOT NULL` guard is what keeps a recipe with no history at all
  from counting the LEFT JOIN's single all-NULL phantom row as an unrecorded
  version — without it every brand-new Recipe would read one version too many."
  [label]
  (let [carries (fn [column] (if label [:= column [:inline label]] [:is column nil]))]
    [:+
     [:sum [:case [:and [:is-not :recipe_history.recipe_id nil]
                   (carries :recipe_history.source)]
            [:inline 1]
            :else [:inline 0]]]
     [:case (carries :recipes.source) [:inline 1] :else [:inline 0]]]))

(def ^:private source-split-columns
  "The card's `3(machine)/17(ui)` split, computed from the `source` columns
  themselves. **One source of truth**: no counter column that a write would have
  to keep in step, because a count that could drift from the labels the version
  list shows is worse than no count at all.

  The three always sum to `version` — history holds 1..N-1 and the row is N — so
  that is an invariant and not just an expectation."
  [[(versions-with-source "machine") :machine_versions]
   [(versions-with-source "ui") :ui_versions]
   [(versions-with-source nil) :unrecorded_versions]])

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

  Every row also carries the **provenance split** — `machine_versions`,
  `ui_versions` and `unrecorded_versions` — because the badge that shows it sits on
  a collapsed card, which is to say on a lean listing row. It is aggregated in the
  query from a LEFT JOIN on `recipe_history`, and the join is deliberately
  invisible in the projection: every selected column is `recipes.`-qualified, so a
  lean read still cannot reach a `description` — not the row's, and not a history
  row's either.

  Both narrowings are `:where` clauses and not filters over the rows, so they
  narrow *inside* the scope they are given. A visitor's search runs against the
  published recipes rather than against everything followed by a hiding step, and
  so does a visitor's human filter — it can only ever take rows away from what
  that caller could already see."
  ([ds scope] (list-recipes ds scope {}))
  ([ds scope {:keys [search-term human-only? lean?] :or {lean? true}}]
   ;; The search clause names `recipes.title` for the same reason the projection
   ;; is qualified: `recipe_history` has a `title` too, and an unqualified one
   ;; would have SQLite refuse the query as ambiguous. The scope and `human-only?`
   ;; clauses need no prefix — `user_id`, `published` and `has_human_edit` exist
   ;; on `recipes` alone — and an ambiguity introduced later would be an error
   ;; SQLite raises, not a filter that quietly reads the wrong column.
   (let [search-clause (db/build-word-prefix-search-clause search-term :recipes.title)
         where (cond-> [:and (scope-clause scope)]
                 search-clause (conj search-clause)
                 human-only? (conj [:= :has_human_edit 1]))]
     (jdbc/execute! (db/get-conn ds)
       (sql/format {:select (into (qualify (select-columns lean?)) source-split-columns)
                    :from [:recipes]
                    :left-join [:recipe_history [:= :recipe_history.recipe_id :recipes.id]]
                    :where where
                    :group-by [:recipes.id]
                    :order-by [[:recipes.modified_at :desc] [:recipes.id :desc]]})
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

(defn- source-of
  "Which source to attribute a write to: `'ui'` when the caller says it is not a
  machine, `'machine'` when it says it is, and nil when it said nothing at all.

  Nil is the third bucket, and leaving it reachable is deliberate. Every write
  through a handler passes `:human?` — it comes from the token's `:machine?`
  claim, so there is always an answer — which leaves the absent case to callers
  that made no claim about themselves at all. `create-recipe` already describes
  those as leaving the row of *unknown* provenance, and stamping `'machine'` on
  them would turn 'nobody said' into a positive claim about an agent, which is the
  same mistake migration 005 refuses to make with a column default.

  There is deliberately no second way of deciding who the caller is: this reads
  the flag the handlers already pass down for `has_human_edit`."
  [opts]
  (when (contains? opts :human?)
    (if (:human? opts) "ui" "machine")))

(defn create-recipe
  "A new recipe: version 1, no history rows, and private — `published` is left
  at its column default, because publishing is its own deliberate act.

  `:human?` records that this came from a caller that is not a machine — see
  `update-recipe` for what that means and why it is the fact worth recording. It
  sets both halves of the record on the one insert: `has_human_edit` for the row,
  and `source` for the version being created, which is v1. It defaults to false,
  which is the conservative reading: a caller that says nothing about itself leaves
  the row of unknown provenance rather than claiming the owner's hand for it —
  `has_human_edit` 0 and `source` NULL, the two ways this schema has of not
  asserting something."
  ([ds user-id fields] (create-recipe ds user-id fields {}))
  ([ds user-id {:keys [title useful_when description]} opts]
   (let [human? (:human? opts)
         result (jdbc/execute-one! (db/get-conn ds)
                  (sql/format {:insert-into :recipes
                               :values [{:title (str/trim title)
                                         :useful_when (or useful_when "")
                                         :description (or description "")
                                         :version 1
                                         :has_human_edit (if human? 1 0)
                                         :source (source-of opts)
                                         :user_id user-id}]
                               :returning (select-columns false)})
                  db/jdbc-opts)]
     (tel/log! {:level :info :data {:id (:id result) :user-id user-id :human? (boolean human?)
                                    :source (source-of opts)}}
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

(defn- archive!
  "Push the outgoing state into history — with **its own** `source`, taken off the
  row alongside its own text and its own version number, and never the source of
  the save that is displacing it. Only the statement after this one stamps the row
  with the new save's source.

  Backwards, every version would be attributed to whoever wrote the *next* one: an
  agent's edit would retroactively relabel the owner's previous version as machine
  work. That reads as plausible in the UI and is wrong everywhere, which is why
  `archive-order-is-the-whole-design` in the db tests pins it."
  [tx current]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :recipe_history
                 :values [{:recipe_id (:id current)
                           :version (:version current)
                           :title (:title current)
                           :useful_when (:useful_when current)
                           :description (:description current)
                           :source (:source current)}]})))

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
  machine's — and a token is checkable where a claim about a browser is not.

  The same flag also labels the **version**: the row's `source` becomes `'ui'` or
  `'machine'` on that same statement, which is what keeps the bit and the labels
  from ever disagreeing. Unlike the bit, `source` is per-version and so it is
  written rather than latched — the outgoing version keeps the label it was saved
  under, because `archive!` carried it into history one statement earlier."
  ([ds user-id id fields expected-modified-at]
   (update-recipe ds user-id id fields expected-modified-at {}))
  ([ds user-id id fields expected-modified-at {:keys [human?] :as opts}]
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
                                                           :source (source-of opts)
                                                           :modified_at [:raw "datetime('now')"])
                                              human? (assoc :has_human_edit 1))
                                       :where [:= :id id]
                                       :returning (select-columns false)})
                          db/jdbc-opts)]
             (tel/log! {:level :info :data {:id id :user-id user-id
                                            :version (:version result)
                                            :human? (boolean human?)
                                            :source (source-of opts)}}
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
  agent wrote is not writing it. It leaves `source` alone for a stricter reason
  still — publishing is not a version at all, so there is no version of it whose
  provenance could be recorded, and touching the row's label would be relabelling
  somebody else's work.

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
  user owns.

  Every entry carries `:source` — where that one version came from — and it comes
  from two places, mirroring rhizome's `get-description-history`: the current
  entry's off the row, the older ones off their own history rows. The key is always
  present and its value may be nil, which is a version written before anything
  recorded this rather than a version whose author was withheld."
  [ds user-id id]
  (when-let [current (get-recipe ds user-id id {:lean? false})]
    (let [history (jdbc/execute! (db/get-conn ds)
                    (sql/format {:select [:version :title :useful_when :description :created_at
                                          :source]
                                 :from [:recipe_history]
                                 :where [:= :recipe_id id]
                                 :order-by [[:version :desc]]})
                    db/jdbc-opts)]
      {:versions (into [(assoc (content-of current)
                               :version (:version current)
                               :created_at (:modified_at current)
                               :source (:source current)
                               :current true)]
                       history)
       :total (inc (count history))})))

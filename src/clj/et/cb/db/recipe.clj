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

  **Tags** are one TEXT column on the row — extra words to find a Recipe by,
  which its title does not contain — following tracker's `tasks.tags` and
  rhizome's `items.tags` rather than any table of its own. Two things about them,
  and they pull in opposite directions on purpose.

  They are **not versioned**, for exactly the reason `published` is not: a tag is
  a retrieval aid and not the Recipe, so versioning one would put filing
  bookkeeping into the history a reader steps through, and would make a tag tweak
  count as a whole `ui` version on the card's provenance split. `update-recipe`
  therefore has a branch for a save that changes only tags: it writes them,
  archives nothing and leaves the version where it is.

  And they are **searched for everybody but sent only to the owner**. The search
  covers `[:title :tags]` whatever the audience — one search behaves one way, so a
  term returns the same recipes no matter who is asking — while a visitor's
  projection simply does not name the column, the way a lean read does not name
  `description`. So an anonymous caller can *test* whether a published Recipe
  carries a word without ever *reading* its tags, and that is the owner's own
  decision rather than an oversight: the hiding is about display. A machine token
  is on the owner's side of that line by design (it reads in the owner's audience),
  which is right for an agentic memory store — a curated retrieval index is most
  of what an agent gets out of one.

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

  The bit stays, and going forward the two cannot disagree: `has_human_edit` is
  true exactly when some version reads `'ui'`, and the same write sets both.
  Keeping the bit is what keeps `?human=true` a plain `:where` on the row instead
  of an aggregate over history on every listing read — the thing this namespace
  avoided when it put the version number on the row. A row that straddles the two
  migrations is the one place they read differently and neither is wrong: a UI save
  made after 004 and before 005 set the bit at a time when no version could carry a
  label, so the Recipe reads human-edited with nothing but unrecorded versions in
  it. Deriving the bit instead of keeping it would not have helped — it would have
  lost that Recipe from the filter it already appears in.

  The one ordering that matters is in `archive!`: a save pushes the outgoing
  version into history together with **its own** source, and only the statement
  after that stamps the row with the new save's.

  **Scopes** are the other half of the filing, and the half that is a relation
  rather than a column: `et.cb.db.scope` owns them, this namespace only ever asks
  it two things. On a read, whether to attach them — no, for a visitor, and the
  join is not run at all rather than run and hidden. On a write, to replace them,
  which is a touch and not a version: `modified_at` moves, nothing is archived.
  Everything else about them, including why the boundary is the missing key rather
  than a client that declines to draw it, is documented over there."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]
            [et.cb.db.scope :as db.scope]))

(def visitor-audience
  "What an anonymous caller may read: the published recipes, whoever owns them.

  A visitor has no user-id, and `db/user-id-where-clause` reads a missing one as
  `user_id IS NULL` — a real category in this schema, not an empty one — so a
  visitor described by a nil user-id would quietly be served the nil-owner's
  rows. This marker keeps a visitor's query from ever naming an owner, and it
  narrows on `published` in the query itself, so an unpublished row is outside
  the result set rather than filtered out of it afterwards.

  It sits up here because the **projection** consults it too now, not only the
  `:where` clause: tags are the owner's, so which columns a read selects depends
  on who is asking. Everything that is not this marker is somebody's user-id, the
  nil owner included, and a nil owner is an owner."
  ::visitor)

(defn- visitor? [audience]
  (= audience visitor-audience))

(defn- audience-clause
  "Whose rows this caller may see, as a `:where` clause.

  **`audience` is this app's word for that question, and only for that question.**
  It used to be called `scope`, which is now reserved for the Scope entity — a
  title and a description a Recipe can be filed under. An audience is not a
  category: it is either a user-id or `visitor-audience`, and it decides which
  rows exist for the caller at all."
  [audience]
  (if (visitor? audience)
    [:= :published 1]
    (db/user-id-where-clause audience)))

(def lean-select-columns
  "Everything but the body and the tags. This *is* the default API shape for a
  visitor, and the owner's default is this plus `tags`."
  [:id :title :useful_when :version :published :published_at :created_at :modified_at
   :has_human_edit :source])

(defn- select-columns
  "Which columns a read selects, and it is a *select-column* choice for both of
  the things it varies on — never a dissoc afterwards. A key that was never
  selected is a key no caller can leak.

  `lean?` is the description: the retrieval index does not load a body.

  `audience` is the tags: **a visitor's projection does not name the column**, so
  the response carries no `tags` key at all rather than an empty one. Absent and
  empty are different answers — an empty string would say 'this Recipe is
  untagged', which is a claim about the owner's filing that a visitor is not being
  told. The client's own hiding is cosmetic on top of this; this is the boundary.

  Note what the two do *not* compose into: `?detail=full` widens the description
  for anybody, visitor included, and it never widens the tags. Verbosity and
  privacy are different axes, which is the change tags made to this app — until
  now the publish latch was the whole privacy boundary."
  [lean? audience]
  (cond-> lean-select-columns
    (not lean?) (conj :description)
    (not (visitor? audience)) (conj :tags)))

(defn- qualify
  "The same columns, `recipes.`-prefixed. Only the listing needs this, and only
  because the provenance join below brings a second table into the query, and it
  has a column of the same name for most of them — `title`, `useful_when`,
  `description`, `version`, `created_at` and `source` are all on `recipe_history`
  too. `tags` is not, since tags are not versioned, and it is prefixed anyway: the
  rule is 'the listing qualifies what it selects', which cannot rot the way a list
  of the columns that currently happen to be ambiguous would. Read back through
  `db/jdbc-opts`, which builds unqualified maps, so the shape a caller sees is
  exactly what it was."
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

(defn- published? [recipe]
  (= 1 (:published recipe)))

(defn- with-scopes
  "Attach `:scopes` to rows the caller may see them on, and **do nothing at all
  for a visitor** — not attach an empty vector, and not run the join.

  This is `select-columns`' rule for tags in the shape a join has to take it: the
  privacy of the owner's filing is a query that does not happen, so there is no
  key for a caller to leak and no `scopes: []` making a claim about how the owner
  files a published Recipe. It is the one function that decides this, and it takes
  the audience precisely so that no caller can decide it instead.

  A published Recipe is no exception. Publishing says who may read the Recipe;
  this says who may see where the owner filed it, and the owner's answer to the
  second was *to logged-in users only, no matter what*."
  [ds audience rows]
  (if (visitor? audience)
    rows
    (db.scope/attach ds audience rows)))

(defn list-recipes
  "The recipes visible in `audience` — a user-id for their owner, `visitor-audience`
  for an anonymous caller — most recently touched first, optionally narrowed by
  a **word-prefix search over the title and the tags**. `lean?` (the default)
  leaves the description out of the projection entirely, and a visitor's
  projection leaves out the tags — see `select-columns`.

  Every whitespace-separated term of the search has to be the prefix of some
  word in *one of the two searched columns*, case-insensitively, and different
  terms may land in different ones: a recipe titled `Sourdough starter` tagged
  `bread baking` matches `sour bak`. See `db/build-word-prefix-search-clause` for
  what a word is. Neither useful-when nor the description is searched: the title
  is the name of the thing and a tag is a word the owner chose to find it by,
  while a match in a line of prose was never what made a recipe the one you meant.
  A tag does not weaken that argument — it is curated where prose is not.

  **The searched columns do not depend on the audience, and that is the owner's own
  decision.** An anonymous caller's search covers tags too, so one search behaves
  one way and a term returns the same recipes whoever asks; columns that shifted
  with the caller would make the same query mean two things and nobody reading the
  docs could predict which. What follows from it, stated rather than discovered
  later: a visitor can learn that a published Recipe carries some word by probing
  search terms, even though the values are never sent. Presence is testable, the
  tags are not readable, and the hiding was only ever about display.

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
  narrow *inside* the audience they are given. A visitor's search runs against the
  published recipes rather than against everything followed by a hiding step, and
  so does a visitor's human filter — it can only ever take rows away from what
  that caller could already see.

  Every row also carries its **Scopes** — `[{:id :title :description}]`, empty for
  an unfiled Recipe — for a caller who may see them, and **no `scopes` key at all**
  for a visitor. That is one extra statement for the whole listing rather than one
  per row (`db.scope/attach`), which is what lets a collapsed card wear its badges
  without going and fetching anything. Nothing filters the shelf by them: the
  Scopes are on the rows this query already chose."
  ([ds audience] (list-recipes ds audience {}))
  ([ds audience {:keys [search-term human-only? lean?] :or {lean? true}}]
   ;; The search clause names `recipes.title` for the same reason the projection
   ;; is qualified: `recipe_history` has a `title` too, and an unqualified one
   ;; would have SQLite refuse the query as ambiguous. `recipes.tags` is
   ;; unambiguous today and is qualified beside it anyway, so the pair cannot drift
   ;; apart. The audience and `human-only?` clauses need no prefix — `user_id`,
   ;; `published` and `has_human_edit` exist on `recipes` alone — and an ambiguity
   ;; introduced later would be an error SQLite raises, not a filter that quietly
   ;; reads the wrong column.
   ;;
   ;; The columns here are the same two for every audience, deliberately: see the
   ;; docstring. What the audience decides is the projection, one line down.
   (let [search-clause (db/build-word-prefix-search-clause search-term
                                                           [:recipes.title :recipes.tags])
         where (cond-> [:and (audience-clause audience)]
                 search-clause (conj search-clause)
                 human-only? (conj [:= :has_human_edit 1]))]
     (->> (jdbc/execute! (db/get-conn ds)
            (sql/format {:select (into (qualify (select-columns lean? audience))
                                      source-split-columns)
                         :from [:recipes]
                         :left-join [:recipe_history [:= :recipe_history.recipe_id :recipes.id]]
                         :where where
                         :group-by [:recipes.id]
                         :order-by [[:recipes.modified_at :desc] [:recipes.id :desc]]})
            db/jdbc-opts)
          (with-scopes ds audience)))))

(defn get-recipe
  "One recipe visible in `audience` — see `list-recipes` — or nil. Lean like the
  listing unless asked otherwise, and tagless like the listing for a visitor
  however it is asked: `lean?` widens the description and nothing widens the
  tags.

  `scopes?` asks for the Recipe's Scopes, and unlike the tags it is **off by
  default**, because it is a second statement rather than a column: the callers
  that want them are the read handlers, feeding a client that has to show which
  Scopes a Recipe is already filed under. The guards and the write paths call this
  to find out whether a row exists and what its text is, and none of them has any
  use for the filing.

  Asking for them is not the same as getting them. A visitor never does, at any
  `lean?` and at any `scopes?` — `with-scopes` refuses, so the flag is a request
  and the audience is the answer."
  ([ds audience id] (get-recipe ds audience id {}))
  ([ds audience id {:keys [lean? scopes?] :or {lean? true}}]
   ;; The nil check is not defensive noise: `with-scopes` on a one-element vector
   ;; holding nil would attach an empty `:scopes` to it and hand back a truthy
   ;; `{:scopes []}` for a recipe that does not exist, turning every 404 in this
   ;; app into a 200.
   (let [recipe (jdbc/execute-one! (db/get-conn ds)
                  (sql/format {:select (select-columns lean? audience)
                               :from [:recipes]
                               :where [:and [:= :id id] (audience-clause audience)]})
                  db/jdbc-opts)]
     (if (and recipe scopes?)
       (first (with-scopes ds audience [recipe]))
       recipe))))

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

  `tags` may be set here like any other field, and unlike the two provenance
  facts it is the caller's to write — a machine's included, which is the point of
  a curated retrieval index in an agentic memory store. It defaults to the empty
  string, the column's own default: a Recipe nobody has tagged is untagged.

  `:human?` records that this came from a caller that is not a machine — see
  `update-recipe` for what that means and why it is the fact worth recording. It
  sets both halves of the record on the one insert: `has_human_edit` for the row,
  and `source` for the version being created, which is v1. It defaults to false,
  which is the conservative reading: a caller that says nothing about itself leaves
  the row of unknown provenance rather than claiming the owner's hand for it —
  `has_human_edit` 0 and `source` NULL, the two ways this schema has of not
  asserting something.

  `scope_ids` files the new Recipe under the caller's own Scopes, in the same
  transaction as the insert — so a Recipe is never briefly visible unfiled, and a
  failed association takes the Recipe with it rather than leaving a half-filed
  row. An absent key means no Scopes, which is the only thing it can mean for a
  row that did not exist a statement ago. The returned Recipe carries `:scopes`
  either way: a write is never anonymous, so there is nobody here to withhold it
  from."
  ([ds user-id fields] (create-recipe ds user-id fields {}))
  ([ds user-id {:keys [title useful_when description tags scope_ids]} opts]
   (jdbc/with-transaction [tx (db/get-conn ds)]
     (let [human? (:human? opts)
           result (jdbc/execute-one! tx
                    (sql/format {:insert-into :recipes
                                 :values [{:title (str/trim title)
                                           :useful_when (or useful_when "")
                                           :description (or description "")
                                           :tags (or tags "")
                                           :version 1
                                           :has_human_edit (if human? 1 0)
                                           :source (source-of opts)
                                           :user_id user-id}]
                                 :returning (select-columns false user-id)})
                    db/jdbc-opts)]
       (when (seq scope_ids)
         (db.scope/set-recipe-scopes! tx user-id (:id result) scope_ids))
       (tel/log! {:level :info :data {:id (:id result) :user-id user-id :human? (boolean human?)
                                      :source (source-of opts)}}
                 "Recipe created")
       (db.scope/attach-one tx user-id result)))))

(defn- content-of [recipe]
  (select-keys recipe [:title :useful_when :description]))

(defn- merge-content
  "A field the caller left out keeps its current value, so an edit meant for one
  field cannot silently clear the other two."
  [current {:keys [title useful_when description]}]
  {:title (if (some? title) (str/trim title) (:title current))
   :useful_when (if (some? useful_when) useful_when (:useful_when current))
   :description (if (some? description) description (:description current))})

(defn- merge-tags
  "Same rule as `merge-content` — absent keeps, present replaces — kept apart
  from it because the three content fields and this one are on different sides of
  every question `update-recipe` asks: whether to archive, whether to bump the
  version, whether to label it. Not trimmed, like `useful_when` and unlike the
  title: the title is an identifier and this is a line the owner typed."
  [current {:keys [tags]}]
  (if (some? tags) tags (:tags current)))

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

  **A save that changes only the filing is a third case, between those two** —
  the tags, the `scope_ids`, or both. Neither is versioned — see the namespace
  docstring — so this writes them and stops there: no history row, no version
  bump, and `source` untouched, because there is no new version for a label to be
  about. `has_human_edit` is untouched for the same reason `publish-recipe` leaves
  it alone: the bit says a human wrote the *text*, and filing a Recipe under a
  word or under a Scope is not writing it.

  It does move `modified_at`, and that is the one thing here that had to be
  decided rather than followed. Publishing is the precedent for leaving it alone,
  but publishing changes nothing an editor edits, and tags are edited in the same
  modal as the three content fields — so a tag write that left the stamp where it
  was would leave a client that had read the row before it passing the
  `expected-modified-at` guard, and its next save would carry the old tags back
  over the new ones with no 409 to stop it. Moving the stamp is what keeps one
  guard covering everything the modal can send. It also reads true: the shelf is
  ordered by `modified_at`, and curating a tag is touching a Recipe.

  **`scope_ids` inherits that whole argument**, including the hazard: the Scope
  picker is in the same modal, and the associations are the one thing a save sends
  that is not on the row at all, so a stale client's `scope_ids` would silently
  unfile what somebody else had just filed. Absent leaves the associations alone,
  present replaces them, and present-but-empty clears them — the same
  absent-keeps/present-replaces rule as every other field, which is why an empty
  array had to mean something rather than being read as 'no opinion'. Ids the
  caller does not own are dropped; `db.scope/set-recipe-scopes!` says why, and the
  returned Recipe's `:scopes` is the receipt.

  The association write happens **after** the `expected-modified-at` guard and
  inside the same transaction as the row write. Before the guard it would be a
  write that a 409 then claimed had not happened.

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
           incoming (merge-content current fields)
           incoming-tags (merge-tags current fields)
           content-changed? (not= incoming (content-of current))
           tags-changed? (not= incoming-tags (:tags current))]
       (if (and expected-modified-at (not= expected-modified-at (:modified_at current)))
         nil
         ;; Past the guard, so a write here is one the caller is allowed to make.
         ;; The associations go first because the row write below is what stamps
         ;; `modified_at`, and a change to the filing has to move it.
         (let [scopes-changed? (when (contains? fields :scope_ids)
                                 (db.scope/set-recipe-scopes! tx user-id id
                                                              (:scope_ids fields)))
               result
               (cond
                 (not (or content-changed? tags-changed? scopes-changed?))
                 current

                 (not content-changed?)
                 (let [result (jdbc/execute-one! tx
                                (sql/format {:update :recipes
                                             :set {:tags incoming-tags
                                                   :modified_at [:raw "datetime('now')"]}
                                             :where [:= :id id]
                                             :returning (select-columns false user-id)})
                                db/jdbc-opts)]
                   (tel/log! {:level :info :data {:id id :user-id user-id
                                                  :version (:version result)}}
                             "Recipe filing saved")
                   result)

                 :else
                 (do
                   (archive! tx current)
                   (let [result (jdbc/execute-one! tx
                                  (sql/format {:update :recipes
                                               :set (cond-> (assoc incoming
                                                                   :tags incoming-tags
                                                                   :version (inc (:version current))
                                                                   :source (source-of opts)
                                                                   :modified_at [:raw "datetime('now')"])
                                                      human? (assoc :has_human_edit 1))
                                               :where [:= :id id]
                                               :returning (select-columns false user-id)})
                                  db/jdbc-opts)]
                     (tel/log! {:level :info :data {:id id :user-id user-id
                                                    :version (:version result)
                                                    :human? (boolean human?)
                                                    :source (source-of opts)}}
                               "Recipe saved")
                     result)))]
           (db.scope/attach-one tx user-id result)))))))

(defn publish-recipe
  "Set the latch on a recipe the user owns: `published` on, `published_at`
  stamped. One way — there is no unpublish, because un-latching would hand a
  machine back the right to rewrite something the owner had signed.

  Publishing an already-published recipe returns it untouched: the first publish
  is the fact being recorded, so `published_at` never moves. It is not a content
  change either — no version bump and no history row — and it deliberately
  leaves `modified_at` alone, so an edit the owner already has in flight is not
  turned into a 409 by it.

  It does not touch the tags or the Scopes, and publishing makes neither public:
  the latch decides who may *see the Recipe*, and the projection decides who may
  see how it is filed. A published Recipe's tags and Scopes stay the owner's —
  that is where those two questions come apart, and the owner said so in as many
  words about the Scopes: *to logged in users only, no matter what*.

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
      ;; `:scopes` on the way out of both branches, not just the one that wrote:
      ;; the client caches this response as the Recipe it holds, so a no-op
      ;; publish that answered without the key would blank the badges on a card
      ;; the server never unfiled.
      (db.scope/attach-one
        tx user-id
        (if (published? current)
          current
          (let [result (jdbc/execute-one! tx
                         (sql/format {:update :recipes
                                      :set {:published 1
                                            :published_at [:raw "datetime('now')"]}
                                      :where [:= :id id]
                                      :returning (select-columns false user-id)})
                         db/jdbc-opts)]
            (tel/log! {:level :info :data {:id id :user-id user-id}} "Recipe published")
            result))))))

(defn delete-recipe
  "Remove a recipe the user owns together with its whole version history **and
  every association to a Scope**. Child rows first, like every other delete path
  in the suite — foreign keys are not enforced on this connection, so ON DELETE
  CASCADE would be a promise nothing keeps.

  The `recipe_scopes` rows are the newer half of that and the easier one to
  forget, because nothing breaks visibly when they are left behind: the Recipe is
  gone from every listing and the orphans are only reachable by joining a table
  that no longer has the row. They would come back as somebody else's badge the
  day AUTOINCREMENT reuses the id. `deleting-a-recipe-takes-its-associations-with-it`
  reads the join table after the delete rather than trusting the parent's absence."
  [ds user-id id]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (let [own [:and [:= :id id] (db/user-id-where-clause user-id)]]
      (when (jdbc/execute-one! tx
              (sql/format {:select [:id] :from [:recipes] :where own})
              db/jdbc-opts)
        (jdbc/execute-one! tx (sql/format {:delete-from :recipe_history
                                           :where [:= :recipe_id id]}))
        (db.scope/delete-recipe-scopes! tx id)
        (jdbc/execute-one! tx (sql/format {:delete-from :recipes :where own}))
        (tel/log! {:level :info :data {:id id :user-id user-id}} "Recipe deleted")
        {:success true}))))

(defn list-versions
  "Every state of a recipe, newest first. The current row is included as version
  N and flagged `:current true`, so a reader can step from today's text back
  through the history in one uniform list. nil when the id matches nothing the
  user owns.

  A version is the three content fields and nothing else: no `tags` key on any
  entry, including the current one, because tags are not versioned and there is
  therefore no answer to 'what were its tags at v2'. `content-of` is what makes
  that true in one place for both ends of the list.

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

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
  covers `[:recipes.title :recipes.tags]` whatever the audience — one search behaves
  one way, so a term aimed at the row returns the same recipes no matter who is
  asking — while a visitor's projection simply does not name the column, the way a
  lean read does not name `description`. So an anonymous caller can *test* whether a
  published Recipe carries a word without ever *reading* its tags, and that is the
  owner's own decision rather than an oversight: the hiding is about display. A
  machine token is on the owner's side of that line by design (it reads in the
  owner's audience), which is right for an agentic memory store — a curated
  retrieval index is most of what an agent gets out of one.

  **A Scope has tags of its own now, and they are a third source of the same
  words**: a term also matches through the filing, against the title or the tags of
  any Scope the Recipe is filed under. That one *is* the owner's alone — a visitor's
  search never reaches through `recipe_scopes` at all — which is the single point
  where the two kinds of tag behave differently, and it is the Scopes' stronger
  boundary rather than a change of mind about search. `list-recipes`' audience
  paragraph is where both halves are argued, and `db.scope/search-clause` is the
  clause.

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
  superseded ones. **Its values are `'ui'` and `'machine'`, and nothing else**:
  since migration 010 the column is `NOT NULL` with a `CHECK`, so every version of
  every Recipe carries one of the two.

  It was three until then. 005 made the column nullable and kept NULL as a real
  third category — 'nobody recorded where this version came from' — because a
  schema is in no position to guess who wrote v3. The owner is, and when he was
  asked he said those versions were his: 010 wrote that answer down, brought
  `has_human_edit` up to match it, and put the constraint in place so the
  distinction cannot come back. What is left is a two-valued question, which is why
  nothing in this namespace branches three ways any more.

  The bit stays, and the two cannot disagree: `has_human_edit` is true exactly when
  some version reads `'ui'`, and the same write sets both. Keeping the bit is what
  keeps `?human=true` a plain `:where` on the row instead of an aggregate over
  history on every listing read — the thing this namespace avoided when it put the
  version number on the row. That it is now fully derivable does not make it
  redundant; deriving it is precisely the cost being avoided.

  The one ordering that matters is in `archive!`: a save pushes the outgoing
  version into history together with **its own** source, and only the statement
  after that stamps the row with the new save's.

  **Scopes** are the other half of the filing, and the half that is a relation
  rather than a column: `et.cb.db.scope` owns them, this namespace only ever asks
  it three things. On a read, whether to attach them — no, for a visitor, and the
  join is not run at all rather than run and hidden. On a read again, for a clause
  hiding the Recipes filed under given ones, which is the listing's third
  narrowing and is refused to a visitor for a sharper version of the same reason.
  On a write, to replace them, which is a touch and not a version: `modified_at`
  moves, nothing is archived. Everything else about them, including why the
  boundary is the missing key rather than a client that declines to draw it, is
  documented over there.

  **Consumption** is `view_count`, and it is the one column here that a *read*
  writes: how often somebody asked for this Recipe's description and got it. Not
  a listing — the retrieval index carries no body at any `?detail`, so scanning
  the shelf is not using anything. It is one column on the row like every other
  denormalised count here, it is not versioned for the reason `published` is not,
  and it is deliberately written from the handler rather than from `get-recipe` —
  see `record-view!`, which argues both, and migration 008, which says what the
  0 on an existing row means. Together with `version` it is what orders the
  shelf: `list-recipes` ranks by a weighted sum of the two.

  **Events** are the owner's inbox, and they are the one thing here that is
  neither a column on the row nor a version of it: `et.cb.db.event` owns them and
  this namespace only ever appends to them. One event per version **an agent
  writes** — `created` for v1, `modified` for each new one, `deleted` for the
  version a Recipe died on — written **inside the same transaction** as the write
  it records, because an event for a save that then rolled back would be worse than
  no inbox.

  Two things decide whether there is an event, and both are facts this namespace
  already holds. **Is the write an agent's** — `machine-write?`, which is
  `source-of` asked again rather than a second reading of `:human?`, so an event
  exists exactly when the version it is about is stamped `machine`. And **is there
  a new version** — so the no-op branch returns before writing one, the filing
  branch writes none because a tag is not content, and `publish-recipe` writes none
  because it makes no version. The argument for each of those is over there."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]
            [et.cb.db.event :as db.event]
            [et.cb.db.proposal :as db.proposal]
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
  rows exist for the caller at all.

  **A tombstone is nobody's row, and that is why the exclusion is here.** Since 012
  a deleted Recipe keeps its row, its history and its filing (`delete-recipe`), so
  every read that used to stop finding it because it was gone would find it again —
  the shelf, the search, one Recipe by id, the provenance split, the machine-write
  gate, the Scope counts. Each of them already asked this one question about whose
  rows these are, so `deleted_at IS NULL` rides along on the clause they all share
  rather than being remembered at a dozen call sites. Adding a read is then safe by
  default, which is the property worth having: the mistake this shape makes
  impossible is a new query that forgets.

  The two reads that *want* a tombstone do not come through here — they ask for one
  by name (`get-deleted-recipe`, `list-deleted`), so wanting one is a decision at
  the call site and never an omission."
  [audience]
  [:and
   (if (visitor? audience)
     [:= :published 1]
     (db/user-id-where-clause audience))
   [:= :deleted_at nil]])

(def lean-select-columns
  "Everything but the body and the tags. This *is* the default API shape for a
  visitor, and the owner's default is this plus `tags`.

  `view_count` is in here rather than behind `?detail=full`, and it is in the
  visitor's projection as a consequence: the card that shows the number is a
  collapsed card, which is to say a lean row, and the badge sits next to
  `version` — which has the same audience rule for the same reason. Keeping the
  first sentence true is the test to apply to anything added here: everything but
  the body and the tags."
  [:id :title :useful_when :version :published :published_at :created_at :modified_at
   :has_human_edit :source :view_count])

(def ^:private read-split-columns
  "`human_reads` and `machine_reads` — the 013 split of `view_count`, and the one
  pair of counters that is **lean but not a visitor's**.

  Lean for `view_count`'s reason, unchanged: the badge that draws them sits on a
  collapsed card, which is a lean row, and a card cannot go and fetch anything.

  **Withheld from a visitor, which is where they part from the total beside them.**
  `view_count` is in a visitor's projection deliberately — it explains the order of
  the shelf they are looking at, which is the same argument that puts `version`
  there. The split explains nothing about that order: the ranking is on the total
  (migration 013 says why it stays that way), so a visitor loses no way of reading
  the shelf by not having it. What it would tell them instead is **how much of the
  owner's traffic is his own agents**, which is a fact about how he works rather
  than about the Recipe — the side of the line `tags`, `scopes` and `pending` are
  already on.

  A select-column choice and never a `dissoc` afterwards, like every other thing on
  that side of the line: a key that was never selected is a key no caller can leak."
  [:human_reads :machine_reads])

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
  now the publish latch was the whole privacy boundary.

  **The read split rides on the audience axis with the tags**, and not on the
  verbosity one — `read-split-columns` argues why the pair is withheld where the
  total beside it is not. So the audience branch now adds three things rather than
  one, which is the shape to keep: one branch, one question, everything that
  answers to it in the same place.

  **`reason` and `context` ride on *both* axes**, which no other pair here does, and
  each half is a different argument. They are the owner's — an agent's `context` is
  the session it was in when it wrote, which is a fact about how he works rather
  than about the Recipe, the side of the line `tags` and `scopes` are already on. And
  they are **not lean**, unlike the tags: nothing on a collapsed card shows them, so
  putting them on every row of a listing would be paying for a paragraph per card
  that no surface reads. The two places that do read them — the review page and the
  version page — both ask for one Recipe, and `update-recipe`'s own read is
  `{:lean? false}`, which is what lets `archive!` carry the outgoing version's pair
  into history."
  [lean? audience]
  (cond-> lean-select-columns
    (not lean?) (conj :description)
    (not (visitor? audience)) (into (conj read-split-columns :tags))
    (and (not lean?) (not (visitor? audience))) (into [:reason :context])))

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
  rows, plus the current row itself when it matches.

  There are **two** labels and no third: `'ui'` and `'machine'`, which migration
  010 made the only two things the column can hold. This used to take `nil` as well,
  for the unrecorded bucket, and that branch needed `IS NULL` rather than a
  comparison — it is gone with the category.

  The `recipe_id IS NOT NULL` guard **stays**, and it is worth saying why rather
  than leaving it to look like a leftover of that branch. It is what keeps a recipe
  with no history at all from counting the LEFT JOIN's single all-NULL phantom row;
  without it, and with the `IS NULL` branch, every brand-new Recipe read one version
  too many. A `source = 'ui'` comparison against that phantom row is NULL rather
  than true, so today the guard is belt and braces — but the phantom row is a
  property of the join and not of the column, and the guard is the one thing here
  that says so out loud."
  [label]
  [:+
   [:sum [:case [:and [:is-not :recipe_history.recipe_id nil]
                 [:= :recipe_history.source [:inline label]]]
          [:inline 1]
          :else [:inline 0]]]
   [:case [:= :recipes.source [:inline label]] [:inline 1] :else [:inline 0]]])

(def ^:private source-split-columns
  "The card's `3(machine)/17(ui)` split, computed from the `source` columns
  themselves. **One source of truth**: no counter column that a write would have
  to keep in step, because a count that could drift from the labels the version
  list shows is worse than no count at all.

  **The two sum to `version`** — history holds 1..N-1 and the row is N, and since
  migration 010 every one of those rows carries one of the two labels — so that is
  an invariant and not just an expectation. It was three until 010 retired the
  unrecorded bucket; `machine_versions + ui_versions = version` is the arithmetic
  to hold on to now, and `the-two-counts-sum-to-the-version` pins it.

  It is also what the approval gate reads: a Recipe is the agents' to write freely
  exactly while `machine_versions = version`, which is now the same as saying no
  version of it reads `'ui'`."
  [[(versions-with-source "machine") :machine_versions]
   [(versions-with-source "ui") :ui_versions]])

(def ^:private ranking-score
  "How the shelf is ranked: **0.7 × `view_count` + 0.3 × `version`**, the owner's
  own weights for 'how often it was consumed' and 'how often it was edited'.

  Both terms are plain columns on `recipes` — `version` *is* the total number of
  versions, which `source-split-columns` states as an invariant — so this needs
  no aggregate and no second join, and it costs the listing nothing.

  **The weighted sum is of the raw counts and not of normalised ones**, which is
  what he asked for, and the consequence is worth writing down rather than
  rediscovering: the two terms only share a scale while the counts are of similar
  size. `0.3 × version` can move a Recipe past another by at most a fraction of a
  read, so once anything is read fifty times the version term is a rounding term
  and this is a consumption ranking with a tiebreaker on top. That is a coherent
  thing to want — a Cookbook is ranked by use — but if it should become 'reads
  and edits weigh comparably' the fix is to normalise each term against the
  shelf's maximum before weighting, not to nudge the constants.

  Kept as one named value rather than spelled out in the `:order-by`, so the
  weights are in one place, and so a test can put different ones in and watch the
  order change (`the-weights-are-the-owners-and-not-just-any-weights`)."
  [:+ [:* [:inline 0.7] :recipes.view_count]
      [:* [:inline 0.3] :recipes.version]])

(def ^:private ranking-order-by
  "The score, then the two tiebreakers that make the order **total**.

  Without them SQLite may return equal-scoring rows in any order it likes, and
  ties are the normal case here rather than a corner: every Recipe starts at
  `0.3 × 1`, so a fresh shelf is entirely ties. The shelf would then shuffle
  between two reloads for no reason a reader could see — and `modified_at` desc
  is what it used to be ordered by outright, so a tie falls back to the old
  behaviour rather than to nothing.

  `id` desc under that, because `modified_at` is second-resolution: two Recipes
  saved in the same second are still a tie one level down."
  [[ranking-score :desc] [:recipes.modified_at :desc] [:recipes.id :desc]])

(def ^:private newest-order-by
  "The other order: **most recently added first** — *i also need a switcher on the
  main page between the ranked order we have now, and one order which is most
  recently added first.*

  **`created_at` desc then `id` desc, and the second is what makes it total**, which
  is `ranking-order-by`'s rule applied rather than a new one: `created_at` takes
  sqlite's `datetime('now')` and is **second-resolution**, so two Recipes added in
  the same second tie — and a tie here is not a corner case at all, since a seeding
  script or an agent writing a handful of Recipes does it inside one second. Without
  the `id` the shelf would shuffle between two reloads for no reason a reader could
  see, which is the bug that docstring exists to prevent.

  `id` alone would have been *exactly* insertion order — `INTEGER PRIMARY KEY
  AUTOINCREMENT` — and naming `created_at` first is the deliberate choice: the column
  says what the order means, and the id says which of two Recipes that share a second
  came second. Ordering by the id alone would have been the same answer arrived at
  through a coincidence of the schema.

  **Most recently *added* is not the order this shelf used to have.** Before the
  ranking it was most-recently-*touched* first, which is `modified_at` — still the
  ranking's first tiebreaker — and that is a different question: a Recipe he edited
  this morning is the most recently touched and one of the oldest added. The two must
  not be conflated, in a docstring or in the words on the control."
  [[:recipes.created_at :desc] [:recipes.id :desc]])

(def orders
  "The two orders a caller may ask for, by name. `:ranked` is the default and
  `:newest` is most-recently-added-first.

  A map rather than a `case` in the query, so that the set of orders is a value:
  `recipe-handler` reads a query parameter against its keys and the client's control
  offers exactly these two, which means a third one would be one entry here and not a
  branch in three files. Public for that reason — it is the vocabulary, not an
  implementation detail."
  {:ranked ranking-order-by
   :newest newest-order-by})

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
  for an anonymous caller — **ranked by use**, optionally narrowed by
  a **word-prefix search over the title, the tags and the words of the Scopes it is
  filed under** — that last one for a caller who may see the filing. `lean?` (the default)
  leaves the description out of the projection entirely, and a visitor's
  projection leaves out the tags — see `select-columns`.

  **The default order is `0.7 × view_count + 0.3 × version` descending**, then
  `modified_at` descending, then `id` descending. `ranking-score` says what the
  weights mean and what follows from summing raw counts; `ranking-order-by` says why
  the tiebreakers are not optional.

  **There are two orders now, and `order` chooses** — *i also need a switcher on the
  main page between the ranked order we have now, and one order which is most recently
  added first.* `:ranked` is that sum and the default; `:newest` is `created_at` desc
  then `id` desc. Both are in `orders`, both are total, and **either is available to
  any caller** — the UI's switcher and a machine listing ask the same way, which is
  what this docstring used to say in a sentence that is now false: it read *one order
  for everybody, the UI and the machine listing alike, because both come through here*.
  What survives of it is the part that mattered — there is one function, so an order
  cannot be something the UI has and an agent does not.

  It also used to note that the shelf *used to be most-recently-touched-first
  outright, which is now the first tiebreaker*. That is still true and it is **not**
  the new order: most recently *touched* is `modified_at` and most recently *added* is
  `created_at`, and a Recipe edited this morning is the first by one and among the last
  by the other. `newest-order-by` keeps that distinction where a reader will meet it.

  Every whitespace-separated term of the search has to be the prefix of some
  word in *one of the searched places*, case-insensitively, and different
  terms may land in different ones: a recipe titled `Sourdough starter` tagged
  `bread baking` matches `sour bak`. See `db/build-word-prefix-search-clause` for
  what a word is. Neither useful-when nor the description is searched: the title
  is the name of the thing and a tag is a word the owner chose to find it by,
  while a match in a line of prose was never what made a recipe the one you meant.
  A tag does not weaken that argument — it is curated where prose is not.

  **There are three searched places and two of them are on this row.** The third is
  the filing: a term also matches when the Recipe is filed under a Scope whose own
  title or tags carries a word starting with it — *i need that we can apply tags,
  i.e. additional search terms for scopes, too* — so a Recipe titled `abc def` filed
  under `utwig`, and `utwig` tagged `backend tag2 tag3`, answers to `utw`, to
  `backend`, and to `ab utw` with one term from each. `db.scope/search-clause` is
  that clause and argues its own shape; it arrives here as
  `build-word-prefix-search-clause`' per-term extra disjunct, which is what keeps
  the rule *one term, anywhere* rather than *all the terms, somewhere*. The Scope's
  description is deliberately not in it, for the reason useful-when is not: names and
  curated words, never prose.

  It follows that a Scope's words are **inherited and not copied**. Tagging the
  Scope `utwig` with `backend` makes every Recipe filed there findable by `backend`
  in one write, and unfiling one takes the word away again — where a Recipe's own
  tags have to be typed onto each of them. Two Recipes in the same Scope are found
  by the same Scope words whatever their own tags say.

  **The two columns on the row do not depend on the audience, and that is the
  owner's own decision.** An anonymous caller's search covers `tags` too, so a term
  aimed at the row behaves one way whoever asks; columns that shifted with the
  caller would make the same query mean two things and nobody reading the docs could
  predict which. What follows from it, stated rather than discovered later: a visitor
  can learn that a published Recipe carries some word by probing search terms, even
  though the values are never sent. Presence is testable, the tags are not readable,
  and the hiding was only ever about display.

  **The Scope words are the exception, and they are the owner's search alone.** For a
  visitor the third disjunct is not built at all, so their search is exactly the
  two-column one described above. This is the *only* place the two kinds of tag part
  company, and it is the Scopes' stronger boundary asserting itself rather than a
  second opinion about search: a visitor is refused the filing outright — no `scopes`
  key (`with-scopes`), and **neither** Scope filter honoured, three lines below — and
  a search that matched a Scope's title would hand back the very inference those
  refusals exist to prevent, one probe at a time and without needing a filter. The
  uniformity argument above is about which of *this Recipe's* fields a term may
  land in; it was never a licence to answer questions about the owner's shelf. So
  the same query does still mean one thing per caller, and the sentence a reader has
  to carry is short: the row's words are everybody's, the filing's words are his.

  `human-only?` narrows to the Recipes that carry a human edit — the
  `has_human_edit` bit described above. It composes with the search rather than
  competing with it: both are clauses on the same query.

  `excluded-scope-ids` is the third narrowing and the **negative** one: the
  Recipes filed under any of those Scopes drop out, and one filed under none of
  them stays whatever else it carries. `db.scope/exclusion-clause` is the clause
  and argues its own shape — including that a Recipe with no Scopes at all is
  never excluded, and that an id the caller does not own excludes nothing rather
  than erroring. It composes with the other two the same way, being a clause like
  them.

  `included-scope-ids` is the fourth and its mirror: the shelf keeps only the
  Recipes carrying **at least one** of those Scopes — *an OR filter for scopes, i.e.
  it filters when one or more are selected for all recipes which match one or more
  selectd scopes*. `db.scope/inclusion-clause` is that clause and says which of its
  sibling's arguments invert: a Recipe filed under nothing now falls *out*, and an
  id the caller does not own narrows to nothing rather than to everything.

  **They are two clauses and this function does not stop you passing both.** The
  client's rule is that they never operate at once — see `views/recipes`'
  `badge-gesture` — but that is a rule about gestures, and it is deliberately not
  enforced here: both are `AND`ed like every other clause, so passing both means
  *in these Scopes and not in those*, which is a coherent question with a coherent
  answer (and an empty one when the sets overlap on a Recipe's whole filing). An
  endpoint that refused the combination would be an endpoint with an opinion about a
  UI, and the machine listing shares this query.

  **A visitor's `excluded-scope-ids` and `included-scope-ids` are ignored outright,
  and that is a refusal rather than a narrowing applied to less.** Not 'narrowed
  within the published rows' — not run at all. `with-scopes` already refuses a
  visitor the Scopes by not running the join, and `get-recipe-handler` states the
  consequence that their presence is not testable either, unlike the tags'.
  Honouring either for a visitor would make it testable: with the exclusion an
  anonymous caller could binary-search which published Recipes carry Scope 4 by
  watching rows disappear, and **the inclusion hands the same answer over without
  the search** — the rows that come back *are* the ones carrying it. So the positive
  one is the more leaky of the two and is refused by the same line, in the same
  place, rather than by a second decision that could come to differ. The decision is
  made here, off the audience, for the reason `with-scopes` gives — one function
  decides, and it takes the audience precisely so that no caller can decide instead.
  A machine token reads in the owner's audience and so is honoured, like every other
  Scope read.

  Every row also carries the **provenance split** — `machine_versions` and
  `ui_versions`, which sum to `version` — because the badge that shows it sits on
  a collapsed card, which is to say on a lean listing row. It is aggregated in the
  query from a LEFT JOIN on `recipe_history`, and the join is deliberately
  invisible in the projection: every selected column is `recipes.`-qualified, so a
  lean read still cannot reach a `description` — not the row's, and not a history
  row's either.

  All four narrowings are `:where` clauses and not filters over the rows, so they
  narrow *inside* the audience they are given. A visitor's search runs against the
  published recipes rather than against everything followed by a hiding step, and
  so does a visitor's human filter — it can only ever take rows away from what
  that caller could already see.

  Every row also carries its **Scopes** — `[{:id :title :description}]`, empty for
  an unfiled Recipe — for a caller who may see them, and **no `scopes` key at all**
  for a visitor. That is one extra statement for the whole listing rather than one
  per row (`db.scope/attach`), which is what lets a collapsed card wear its badges
  without going and fetching anything. The badges are attached to the rows this
  query chose; `excluded-scope-ids` is what decides which rows it chose."
  ([ds audience] (list-recipes ds audience {}))
  ([ds audience {:keys [search-term human-only? excluded-scope-ids included-scope-ids
                        order lean?]
                 :or {lean? true order :ranked}}]
   ;; The search clause names `recipes.title` for the same reason the projection
   ;; is qualified: `recipe_history` has a `title` too, and an unqualified one
   ;; would have SQLite refuse the query as ambiguous. `recipes.tags` is
   ;; unambiguous today and is qualified beside it anyway, so the pair cannot drift
   ;; apart. The audience and `human-only?` clauses need no prefix — `user_id`,
   ;; `published` and `has_human_edit` exist on `recipes` alone — and an ambiguity
   ;; introduced later would be an error SQLite raises, not a filter that quietly
   ;; reads the wrong column.
   ;;
   ;; The two columns here are the same for every audience, deliberately: see the
   ;; docstring. What the audience decides is the projection, one line down — and
   ;; the third source of words, one line up from that.
   (let [;; **The Scope words, and the one narrowing an audience decides the
         ;; *shape* of rather than the arguments to.** A per-term disjunct, so a
         ;; term may land in the title, the tags, or the title or tags of any Scope
         ;; the Recipe is filed under; nil for a visitor, and then this is the
         ;; two-column search it always was. `when-not (visitor? ...)` is the same
         ;; guard the two Scope filters below use, three of three now, for the
         ;; reason the docstring's audience paragraph gives: `audience` is a user-id
         ;; below the guard, which is exactly what `search-clause` requires and
         ;; cannot check for itself.
         scope-search (when-not (visitor? audience)
                        (fn [term] (db.scope/search-clause audience :recipes.id term)))
         search-clause (db/build-word-prefix-search-clause search-term
                                                           [:recipes.title :recipes.tags]
                                                           scope-search)
         ;; The visitor refusal, and it is *this* line rather than a check in the
         ;; handler: the audience is the answer, and a caller that could pass one
         ;; meaning 'a visitor' is the shape this avoids. `audience` is a user-id
         ;; below the guard, which is what `with-scopes` relies on too.
         ;;
         ;; `:recipes.id` is qualified for the reason `qualify` gives: the listing
         ;; left-joins `recipe_history` and groups by `recipes.id`, and an
         ;; unqualified `id` inside the subquery would resolve against
         ;; `recipe_scopes` — a correlation to itself, which is always true, so the
         ;; clause would silently exclude every filed Recipe rather than the named
         ;; ones.
         scope-exclusion (when-not (visitor? audience)
                           (db.scope/exclusion-clause audience :recipes.id
                                                      excluded-scope-ids))
         ;; **The same guard and not a second one.** Both Scope narrowings are
         ;; refused a visitor, and the positive one is the more leaky of the two —
         ;; its answer *is* the list of published Recipes carrying the Scope, where
         ;; the negative one only lets a caller infer it by diffing two listings. So
         ;; it is refused on the same condition, three lines apart, rather than by a
         ;; check somewhere else that could come to disagree.
         scope-inclusion (when-not (visitor? audience)
                           (db.scope/inclusion-clause audience :recipes.id
                                                      included-scope-ids))
         where (cond-> [:and (audience-clause audience)]
                 search-clause (conj search-clause)
                 human-only? (conj [:= :has_human_edit 1])
                 scope-exclusion (conj scope-exclusion)
                 scope-inclusion (conj scope-inclusion))]
     (->> (jdbc/execute! (db/get-conn ds)
            (sql/format {:select (cond-> (into (qualify (select-columns lean? audience))
                                              source-split-columns)
                                   ;; `pending` is the owner's business, so it is a
                                   ;; select-column choice like the tags and not a
                                   ;; dissoc afterwards: a visitor's row does not name
                                   ;; the column rather than carrying a false.
                                   (not (visitor? audience))
                                   (conj (db.proposal/pending-exists-clause :recipes.id)))
                         :from [:recipes]
                         :left-join [:recipe_history [:= :recipe_history.recipe_id :recipes.id]]
                         :where where
                         :group-by [:recipes.id]
                         ;; **The order is looked up and never branched on**, and an
                         ;; unknown name falls back to the ranking rather than to no
                         ;; order at all: a `nil` here would leave the shelf in
                         ;; whatever order SQLite felt like, which is the untotal
                         ;; ordering both of these are written to avoid. The handler
                         ;; already refuses to pass a name that is not one of the two,
                         ;; so this is the second of two agreements rather than the
                         ;; only one — and it is the one that also covers an internal
                         ;; caller passing a typo.
                         :order-by (get orders order ranking-order-by)})
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
  and the audience is the answer.

  **`tombstones?` is the one way past `audience-clause`'s tombstone exclusion**, and
  it is a request like `scopes?` rather than a mode: it widens what may be *found*
  to include a Recipe that has been deleted and kept (012). Every write path in this
  namespace reaches its row through this function, so leaving the flag off is what
  makes a deleted Recipe unwritable — no save, no publish, no approving a proposal
  against it — without any of them having to know that tombstones exist. The callers
  that pass it are the reads that are *about* the deleted: the history behind a
  queue's `deleted` entry, and the page that lists and purges them.

  **It is refused to a visitor outright**, not because a visitor path passes it —
  none does — but because the flag would otherwise be one careless argument away
  from handing an anonymous caller a deleted Recipe, and this is the cheapest place
  to make that impossible rather than unlikely."
  ([ds audience id] (get-recipe ds audience id {}))
  ([ds audience id {:keys [lean? scopes? tombstones?] :or {lean? true}}]
   ;; The nil check is not defensive noise: `with-scopes` on a one-element vector
   ;; holding nil would attach an empty `:scopes` to it and hand back a truthy
   ;; `{:scopes []}` for a recipe that does not exist, turning every 404 in this
   ;; app into a 200.
   (let [recipe (jdbc/execute-one! (db/get-conn ds)
                  (sql/format {:select (cond-> (select-columns lean? audience)
                                         (not (visitor? audience))
                                         (conj (db.proposal/pending-exists-clause :recipes.id))
                                         ;; **Only the read that may find a tombstone
                                         ;; selects the stamp.** Putting it in
                                         ;; `lean-select-columns` would name a column
                                         ;; that is null in every other read here (they
                                         ;; all exclude tombstones) and would put it in
                                         ;; a visitor's projection, which is a key
                                         ;; saying something about the owner's shelf.
                                         tombstones? (conj :deleted_at))
                               :from [:recipes]
                               :where [:and [:= :id id]
                                       (if (and tombstones? (not (visitor? audience)))
                                         (db/user-id-where-clause audience)
                                         (audience-clause audience))]})
                  db/jdbc-opts)]
     (if (and recipe scopes?)
       (first (with-scopes ds audience [recipe]))
       recipe))))

(defn version-split
  "One Recipe's provenance split — `{:version :machine_versions :ui_versions}` — or
  nil when the id matches nothing in this audience.

  **The same expression the listing serves the card**, run for one row: it selects
  `source-split-columns` over the same `LEFT JOIN` and `GROUP BY`. That is the point
  of the function existing rather than a second count written for the gate — a
  predicate that could disagree with the number on the card would be the worst of
  both, since he would be looking at `3(machine)` while an agent was told to ask
  permission."
  [ds audience id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select (into [:recipes.version] source-split-columns)
                 :from [:recipes]
                 :left-join [:recipe_history [:= :recipe_history.recipe_id :recipes.id]]
                 :where [:and [:= :recipes.id id] (audience-clause audience)]
                 :group-by [:recipes.id]})
    db/jdbc-opts))

(defn machine-only?
  "Whether **every** version of this Recipe was written by an agent — one of the two
  things that decide whether a machine may write straight through or has to propose.

  **It is not the whole answer, and it does not know the other half.** A published
  Recipe is never the agents' to write however this reads, so
  `recipe-handler/update-recipe-handler` asks about the latch as a *peer* of this
  question rather than reaching for this one first. Answering `true` here means 'no
  version of it is his', which is exactly what the name says and no more.

  `machine_versions = version` and nothing else. Three things that are *not* this
  question, each of which someone will be tempted by:

  - **Not `has_human_edit = 0`.** That bit read 0 for every Recipe he typed by hand
    before migration 004, so keying on it would have let an agent overwrite exactly
    the text this exists to protect. 010 has since brought the bit up on those rows,
    which makes the two agree today — and that is a reason to leave this alone
    rather than to switch: the bit is one fact about the row, the gate is a claim
    about every version, and they answer different questions.
  - **Not the row's own `source`.** A Recipe whose current version is an agent's can
    have his save two versions back; that is the common case after he corrects
    something, and it is precisely when approval is wanted.
  - **Not `ui_versions = 0`.** True today, because the two counts sum to `version`,
    but it says the same thing one indirection further from the invariant.

  nil for an id the caller cannot see, which callers read as 'no' — a Recipe you
  cannot see is not one you may write."
  [ds audience id]
  (when-let [{:keys [version machine_versions]} (version-split ds audience id)]
    (= machine_versions version)))

(defn record-view!
  "Count one **consumption** of a Recipe: somebody asked for this one's
  description and got it.

  **Called from `recipe-handler/get-recipe-handler` and deliberately not from
  `get-recipe`.** Everything in this namespace calls `get-recipe` — the write
  paths to find out whether a row exists and what its text is, `update-recipe`
  for the state it is about to archive, `publish-recipe` for the latch — so a
  counter in there would count the app's own bookkeeping as reading, invisibly,
  and every save would inflate the number that decides the shelf's order. The
  handler is the only place that knows a request asked for a body and was given
  one, which is the fact being recorded.

  **It must not move `modified_at`, and this is the whole statement**: one
  column, `WHERE id`, nothing else. Two things break the moment a read touches
  that stamp. `update-recipe` guards on the `modified_at` its caller last read,
  so opening a card and then saving it would 409 against yourself. And
  `modified_at` is the shelf's tiebreaker under the ranking, so every read would
  reshuffle the shelf. `recipe-views-do-not-touch-modified-at` pins it.

  No audience clause: the handler has already read the row *in* the caller's
  audience, so an id that reaches here is one that caller may see — a 404 never
  gets this far, whether it is a missing id or an unpublished Recipe a visitor
  asked for.

  **Every audience counts, the visitor's included.** The number answers 'was this
  actually used', and a published Recipe read by a stranger was used. The
  consequence, stated rather than discovered: a published Recipe's count is
  inflatable by anyone who can reach the endpoint, and since the count ranks the
  shelf, so is its position. Unpublished Recipes are unreachable to anybody but
  the owner and his agents, so this is bounded by what he has published. It is
  the one decision here he may want to revisit — the alternative is to count only
  authenticated reads, which would stop counting exactly the audience publishing
  exists for.

  **`machine?` says which bucket this read goes in as well** — *and break the reads
  down by human/machine as well* — and it is still **one statement**: `view_count`
  and one of the two counters, in the same `:set`, `WHERE id`, nothing near
  `modified_at`. The total is bumped alongside rather than derived from the pair,
  because the pair cannot account for the reads counted before migration 013 and
  the ranking is on the total.

  **`machine?` is the token's claim and is *not* `source-of`'s flag**, which is the
  one thing here a later reader is likeliest to 'unify' and must not. That function
  reads `(if (:human? opts) \"ui\" \"machine\")` — **silence means machine** — and it
  argues that at length, because `has_human_edit` has read the same flag the same
  way since 004. For a read, silence means **a visitor**: nobody signed in, no token
  at all, and 008 counts that read on purpose. So the same absence means opposite
  things on the two paths, and folding them together would file every anonymous read
  under the agents — quietly, and in the number the badge is about.

  Which is why this takes a boolean that is true only for a *machine token* rather
  than one that is true for the owner. There are three kinds of reader and two
  buckets: the owner and the stranger are both the human one, and only a machine
  token is the other. `recipe-handler/get-recipe-handler` passes
  `common/machine-caller?` straight down, which is the same rule
  `source-of`'s docstring states about not having a second way of deciding who the
  caller is — one place decides, everywhere else is handed the answer."
  [ds id machine?]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:update :recipes
                 :set {:view_count [:+ :view_count [:inline 1]]
                       (if machine? :machine_reads :human_reads)
                       [:+ (if machine? :machine_reads :human_reads) [:inline 1]]}
                 :where [:= :id id]})))

(defn- source-of
  "Which source to attribute a write to: `'ui'` when the caller is not a machine,
  `'machine'` when it is. **Two values, and it always answers** — since migration
  010 the column is `NOT NULL CHECK (source IN ('ui','machine'))`, so a write that
  declined to say where it came from is a write the database refuses.

  It used to have a third answer, nil, for a caller that passed no `:human?` at
  all: 005 kept 'unrecorded' as a category and stamping `'machine'` on silence
  would have turned 'nobody said' into a claim about an agent. 010 retired that
  category on the owner's own instruction, so the question now is which of the two
  a silent caller gets, and the answer is `'machine'` — for one reason and not out
  of convenience. **`has_human_edit` has read this same flag as `(if human? 1 0)`
  since 004**, so silence has always meant 'not the owner acting for himself' on
  the row; making it mean something else in the column would break the invariant
  that the bit is true exactly when some version reads `'ui'`, on the very next
  write, and 010's whole point was to make those two agree.

  Nothing reaches the silent case from outside anyway: every write through a
  handler passes `:human?`, taken from the token's `:machine?` claim, so an HTTP
  caller is always attributed. What is left is internal callers and tests, and a
  caller that wants the owner's label has to say so.

  There is deliberately no second way of deciding who the caller is: this reads
  the flag the handlers already pass down for `has_human_edit`."
  [opts]
  (if (:human? opts) "ui" "machine"))

(defn- machine-write?
  "Whether this write is an agent's, which is the one thing that puts an event in
  the owner's inbox — he was asked and said *no my own ui edits should not land in
  the inbox*, so the queue is the record of what the agents did and not a change
  log.

  **Asked of `source-of` rather than of `(:human? opts)` directly**, so the fact an
  event is written on is the *same expression* the version's label is written from:
  an event exists exactly when the version it is about is stamped `'machine'`. A
  second reading of the flag could come to disagree with the first, and this is a
  question with one answer.

  Which means a caller that says nothing about itself **does** write an event, because
  since migration 010 `source-of` answers `'machine'` for it. There is no third case
  left to settle here: `source-of` argues why silence is the agent's label rather than
  the owner's, and this function inherits that answer instead of taking a second view
  of it. Nothing reaches it over HTTP anyway — every handler passes `:human?` from the
  token — so what this covers is internal callers and tests, and a caller that wants
  the owner's label has to say so, in the inbox exactly as in the column."
  [opts]
  (= "machine" (source-of opts)))

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
  and `source` for the version being created, which is v1. It **defaults to
  false**, which since migration 010 means `has_human_edit` 0 and `source`
  `'machine'` rather than the 0-and-NULL this used to leave: the column can no
  longer decline to answer, and the two halves are read off the one flag precisely
  so they cannot say different things. `source-of` argues that choice; the short
  version is that silence has meant 'not the owner acting for himself' on this row
  since 004, and the label now says the same.

  `scope_ids` files the new Recipe under the caller's own Scopes, in the same
  transaction as the insert — so a Recipe is never briefly visible unfiled, and a
  failed association takes the Recipe with it rather than leaving a half-filed
  row. An absent key means no Scopes, which is the only thing it can mean for a
  row that did not exist a statement ago. The returned Recipe carries `:scopes`
  either way: a write is never anonymous, so there is nobody here to withhold it
  from.

  **A machine's create appends a `created` event** to the owner's inbox, in this
  same transaction — the create is the version, so there is one event and it is
  this one. His own create appends nothing: the inbox is what the agents did, and he
  does not need to be told about the Recipe he is looking at having written."
  ([ds user-id fields] (create-recipe ds user-id fields {}))
  ([ds user-id {:keys [title useful_when description tags scope_ids reason context]} opts]
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
                                           ;; Whatever the caller said, including
                                           ;; nothing: NULL is 'not recorded' and is
                                           ;; the owner's normal case (see 015).
                                           ;; That a *machine* create must say
                                           ;; something is the handler's rule, made
                                           ;; where a caller can be told why.
                                           :reason reason
                                           :context context
                                           :user_id user-id}]
                                 :returning (select-columns false user-id)})
                    db/jdbc-opts)]
       (when (seq scope_ids)
         (db.scope/set-recipe-scopes! tx user-id (:id result) scope_ids))
       (when (machine-write? opts)
         (db.event/record! tx user-id "created" result))
       (tel/log! {:level :info :data {:id (:id result) :user-id user-id :human? (boolean human?)
                                      :source (source-of opts)}}
                 "Recipe created")
       (db.scope/attach-one tx user-id result)))))

(defn- content-of [recipe]
  (select-keys recipe [:title :useful_when :description]))

(defn merge-content
  "A field the caller left out keeps its current value, so an edit meant for one
  field cannot silently clear the other two. **The one implementation of that rule**,
  and the reason this is public rather than private to the save path.

  Two callers outside `update-recipe` now, and both had to be given this rather than
  their own version of it. `content-would-change?` asks whether the merge would differ
  from the row, which is what makes a no-op a no-op. And
  `recipe-handler/update-recipe-handler` builds the **proposal payload** from it: a
  proposal is a proposed *version*, so it is all three fields, and the two a partial
  `PUT` did not send come off the Recipe by this rule and not by another one.

  That second caller used to merge by hand, over a row it had read with the **lean**
  projection — and a lean read is defined by not carrying a `description`. So absent
  meant 'keep' for the title and the useful-when, which are on a lean row, and 'clear'
  for the body, which is not: a machine renaming a Recipe proposed deleting its text,
  and approving that wrote the deletion. `current` must therefore be a row read with
  `{:lean? false}`, which is the whole of what this function needs said about it — a
  merge is only as complete as the row it merges into.

  `content-of` is the other half: this builds the incoming version, that reads the
  outgoing one, and every question about whether a save is a change compares the two."
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

(defn content-would-change?
  "Whether saving `fields` over `current` would actually write a new version.

  **It reuses `merge-content` and `content-of`**, which is the whole reason it lives
  here rather than in the handler that needs it: absent-keeps and present-replaces is
  one rule with one implementation, and a caller that re-derived it would eventually
  disagree with `update-recipe` about whether a save is a no-op. The write path asks
  this before proposing, so that a machine `PUT` sending the same title back stays
  the no-op it has always been instead of becoming a pending proposal of nothing.

  **It takes the row and not an id, so that the answer and the payload are about the
  same read.** It used to run its own `SELECT`, which meant the handler held one copy
  of the Recipe and this held another — two answers to 'what does this row say' inside
  one request, and the two were not even read the same way: the handler's was lean.
  The caller reads once, with `{:lean? false}`, and passes that row here and to
  `merge-content`. `current` must be a full row for the reason `merge-content` gives.

  It is placed here, immediately under the two functions it is made of, rather than
  beside the other predicate the gate uses: the point is that there is one merge rule
  and this reads it.

  Callers must have established that the row exists — the write path 404s first."
  [current fields]
  (not= (merge-content current fields) (content-of current)))

(defn- archive!
  "Push the outgoing state into history — with **its own** `source`, taken off the
  row alongside its own text and its own version number, and never the source of
  the save that is displacing it. Only the statement after this one stamps the row
  with the new save's source.

  Backwards, every version would be attributed to whoever wrote the *next* one: an
  agent's edit would retroactively relabel the owner's previous version as machine
  work. That reads as plausible in the UI and is wrong everywhere, which is why
  `archive-order-is-the-whole-design` in the db tests pins it.

  **`reason` and `context` travel with `source` and for the identical reason.** They
  are the outgoing version's own words about itself, so they go into history off the
  row that is leaving, never off the save displacing it — otherwise an agent's
  explanation of *this* edit would be filed as the explanation of the one before it,
  which is the same backwards attribution one field along. They are NULL for every
  version written before 015, and stay NULL: nothing can recover them."
  [tx current]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :recipe_history
                 :values [{:recipe_id (:id current)
                           :version (:version current)
                           :title (:title current)
                           :useful_when (:useful_when current)
                           :description (:description current)
                           :source (:source current)
                           :reason (:reason current)
                           :context (:context current)}]})))

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
  under, because `archive!` carried it into history one statement earlier.

  **A `modified` event goes to the inbox from the content branch and from nowhere
  else**, in this same transaction, carrying the new version's number — and only
  when the save is an agent's, which is `machine-write?`. So the inbox needs no
  decision of its own here: the no-op branch returns before the event, the filing
  branch writes none — a tag or a Scope is not the text he wrote — and his own
  saves write none because they are not what the queue is for."
  ([ds user-id id fields expected-modified-at]
   (update-recipe ds user-id id fields expected-modified-at {}))
  ([ds user-id id fields expected-modified-at {:keys [human?] :as opts}]
   (jdbc/with-transaction [tx (db/get-conn ds)]
     ;; **nil for a row this caller cannot write, before anything else happens** —
     ;; `when-let`, the way `publish-recipe`, `delete-recipe`, `purge-recipe!` and
     ;; `list-versions` all answer that question. This one used to walk on with a nil
     ;; `current`: every content field then read as changed, and `archive!` was handed
     ;; the nil, which came back as `NOT NULL constraint failed: recipe_history
     ;; .recipe_id` — a constraint violation where every sibling function returns
     ;; nothing. It was unreachable through the API, because the handler answers 404
     ;; from its own read first, and it became reachable in a new way with 012: a
     ;; tombstoned Recipe is now a row that exists and is not writable. Found by the
     ;; test that asserts a deleted Recipe cannot be saved.
     (when-let [current (get-recipe tx user-id id {:lean? false})]
       (let [incoming (merge-content current fields)
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
                                                                     ;; **Not merged the way the
                                                                     ;; content is.** An omitted
                                                                     ;; title keeps the old one;
                                                                     ;; an omitted reason must
                                                                     ;; *not* keep the old one,
                                                                     ;; because it would then read
                                                                     ;; as this version's own
                                                                     ;; explanation while
                                                                     ;; describing the last one.
                                                                     ;; The new version says why
                                                                     ;; it exists or says nothing.
                                                                     :reason (:reason fields)
                                                                     :context (:context fields)
                                                                     :modified_at [:raw "datetime('now')"])
                                                        human? (assoc :has_human_edit 1))
                                                 :where [:= :id id]
                                                 :returning (select-columns false user-id)})
                                    db/jdbc-opts)]
                       (when (machine-write? opts)
                         (db.event/record! tx user-id "modified" result))
                       (tel/log! {:level :info :data {:id id :user-id user-id
                                                      :version (:version result)
                                                      :human? (boolean human?)
                                                      :source (source-of opts)}}
                                 "Recipe saved")
                       result)))]
             (db.scope/attach-one tx user-id result))))))))

(defn approve-proposal!
  "Apply a proposal as the Recipe's next version, in **one transaction**: archive the
  outgoing version, write the proposal's three fields, resolve the proposal
  `approved`, and mark its inbox entry seen.

  **It does not answer 'the Recipe is gone', because an unresolved proposal always has
  one.** `delete-recipe` resolves the pending proposal and marks its entry seen in the
  same transaction as the delete, and `inbox-handler/resolving` has already established
  that this proposal is unresolved — so there is no request that arrives here with a
  missing Recipe. It used to return nil for that, and the route documented a 404 for
  it, which was a state this design does not have described as one of its answers. A
  missing row is now thrown: it is a broken invariant and not a case, and the only way
  to reach it would be a delete landing between `resolving`'s read and this
  transaction, which this database does not currently let two writers do.

  Five decisions, all of which read oddly unless they are said out loud:

  - **The new version's `source` is `machine`.** The agent wrote this text. Approving
    is the owner letting it in, not authoring it.
  - **It does not set `has_human_edit`.** `publish-recipe` already makes exactly this
    argument in these words: putting your name to text an agent wrote is not writing
    it. Which also means the Recipe still needs approval next time — the `ui` version
    that closed the gate is still in its history — and that is intended rather than a
    side effect.
  - **It writes no `modified` event.** The proposal's own entry is the record of what
    the agent did, and it has just been resolved. A second entry would ask him to
    acknowledge a change he had personally approved a statement earlier. The rule
    that events follow the `machine` label agrees: this write is his act, not an
    agent's, however the version is labelled.
  - **`base_version` is not a guard.** If he saved in between, the proposal is
    against older text and this replaces his newer text with the agent's. That is his
    call to make with his eyes open, so the UI says so on the item and this does not
    refuse it. Refusing would strand the agent's work with nothing to do about it.
  - **The Recipe may be published, and then this writes an agent's wording into text he
    has put his name to.** A machine may propose against a published Recipe — *its up
    to the human to approve or not* — and that is the whole reason the inbox item says
    in words that the Recipe is published before he clicks. Nothing here refuses it,
    for the same reason nothing here reads `base_version`: this function applies a
    decision, it does not second-guess one.
  - **`archive!` is called before the write**, like every other save here, so the
    outgoing version goes into history with its *own* source rather than with
    `machine`. Approving must not relabel what he wrote — the
    `archive-order-is-the-whole-design` property, met by a second write path.
  - **The proposal's `reason` and `context` are copied onto the version**, which is
    what makes the pair worth having at all: the sentences he read while deciding
    become the sentences the version page shows afterwards, so the answer to *why
    does v4 say this* is the same text on both surfaces rather than something the
    review page knew and the history lost. They travel with `source`, and for the
    same reason — this text is the agent's account of the write, and approving does
    not make it the owner's. A proposal filed before migration 015 carries NULL and
    hands NULL on, which is the honest answer for a version nobody explained."
  [ds user-id proposal]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (let [current (get-recipe tx user-id (:recipe_id proposal) {:lean? false})]
      (when (nil? current)
        (throw (ex-info "An unresolved proposal names a Recipe that is gone"
                        {:proposal-id (:id proposal)
                         :recipe-id (:recipe_id proposal)})))
      (archive! tx current)
      (let [result (jdbc/execute-one! tx
                     (sql/format {:update :recipes
                                  :set {:title (str/trim (str (:title proposal)))
                                        :useful_when (or (:useful_when proposal) "")
                                        :description (or (:description proposal) "")
                                        :version (inc (:version current))
                                        :source [:inline "machine"]
                                        :reason (:reason proposal)
                                        :context (:context proposal)
                                        :modified_at [:raw "datetime('now')"]}
                                  :where [:= :id (:recipe_id proposal)]
                                  :returning (select-columns false user-id)})
                     db/jdbc-opts)]
        (db.proposal/resolve! tx user-id proposal "approved")
        (tel/log! {:level :info :data {:id (:recipe_id proposal) :user-id user-id
                                       :version (:version result)
                                       :proposal-id (:id proposal)}}
                  "Recipe proposal approved")
        (db.scope/attach-one tx user-id result)))))

(defn publish-recipe
  "Set the latch on a recipe the user owns: `published` on, `published_at`
  stamped. One way — there is no unpublish, because un-latching would hand a
  machine back the right to rewrite something the owner had signed.

  **It publishes the row, and the row is the last approved version — so what a visitor
  is shown is the last approved version, always.** Publishing is deliberately allowed
  while an agent's proposal is waiting, because the two facts do not touch: a proposal
  is not a version, it lives in `recipe_proposals`, and nothing in any read of the
  Recipe consults it. The owner's own case, in his words: *if say the last version v3
  was from a machine and the human approved, and then the machine sends another request,
  on publish, what an anon user sees is v3.* That is not a happy accident of this
  design, it is the load-bearing part of it — with publishing open while a proposal
  pends, the invisibility of that proposal to every read is the only thing between an
  unapproved wording and an anonymous reader.

  **Two tests hold it, because it has two halves and one of them is this function's.**
  `what-a-visitor-sees-is-the-last-approved-version` is the reads: nothing serves the
  pending text, at any `?detail`, to anybody.
  `publishing-while-a-proposal-pends-publishes-the-approved-version` is this write: the
  publish is allowed, it resolves nothing, and what goes public is the row. The second
  had to be written because the first cannot fail for it — a publish that refused while
  something waited, or that helpfully applied the proposal on the way out, is a bug in
  here rather than in a read, and it reddened one incidental assertion about an
  anonymous listing's title inside a test about something else.

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

  It writes **no inbox event**, and twice over: it makes no version, and a machine
  cannot publish at all (`wrap-machine-recipe-rules`), so there is no reachable
  caller here whose act the inbox is for.

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
  "Delete a recipe the user owns — by **stamping it** rather than by removing it.
  Its row, its whole version history and every association to a Scope stay exactly
  where they are, and `deleted_at` is what takes it off the shelf: since 012 that is
  one clause in `audience-clause`, so it leaves every read at once.

  *lets tombstone instead of actually hard deleting data* — and the reason he wanted
  it is the sentence before that one: *for deleted, i should also be able to visit.
  curerntly i cant click.* The queue's `deleted` entry had nothing behind it to open,
  because this function used to mean it. What it deleted was the row, every
  `recipe_history` row under it and every `recipe_scopes` association, and the
  `recipe_title` snapshot on the event was all that was left to name it by. Now the
  text is still there to be read, and `purge-recipe!` is where the old behaviour
  went — the same three deletes, asked for deliberately, on the page that lists what
  is waiting to be purged.

  **What a tombstone still does at once, because it is not a lesser kind of
  deleted:**

  - A pending **proposal is resolved**, exactly as before and for the same reason. An
    event records that something happened and it did; a proposal is a question
    waiting for an answer, and a question about a Recipe that has been deleted cannot
    be answered — left pending it would sit at the top of his queue unanswerable and
    go on blocking the agent that filed it. The row is kept with `resolved_at` set and
    no resolution word, because he decided nothing. `db.proposal/resolve-for-recipe!`
    argues it at length. **Note what does not follow from tombstoning: a deleted
    Recipe is not writable, so its proposal is no more answerable than before.**
    Every write path finds its row through `get-recipe` without `tombstones?`, which
    is what makes that true without any of them testing for it.
  - The **filing stays and stops counting**. The associations are kept — a tombstone
    that came back would come back filed, and the page that lists them can say what
    it was about — while the per-Scope counts on the Scopes page drop the moment it is
    stamped. That second half is **not** `audience-clause`'s doing, which is worth
    saying because it is the one place the exclusion had to be written twice:
    `db.scope/list-scopes` counts from the other side of the join, so it joins
    `recipes` and skips the tombstones itself. A test found that, not a reading.
  - **An agent's delete writes one last event, `deleted`**, carrying the version the
    Recipe was on; the owner's own delete writes none, like every other write of his.
    Which is why this takes `opts` at all — `:human?`, the same flag the create and
    the save take.

  `recipe_events` was never in the deleted list and this changes nothing about that:
  an event is the record that something happened to a Recipe, not a part of it.

  **Deleting one twice is not a second delete.** The row is looked up among the
  living, so a tombstone is nil here and answers the caller the same way a missing id
  does — one `deleted` event per delete, and no way for an agent to fill the queue by
  repeating itself.

  The row is read for its title and version rather than only its id, which is what
  the event is made of — the same read that decides whether there was anything to
  delete."
  ([ds user-id id] (delete-recipe ds user-id id {}))
  ([ds user-id id opts]
   (jdbc/with-transaction [tx (db/get-conn ds)]
     (let [own [:and [:= :id id] (db/user-id-where-clause user-id)]]
       (when-let [current (jdbc/execute-one! tx
                            (sql/format {:select [:id :title :version]
                                         :from [:recipes]
                                         :where [:and own [:= :deleted_at nil]]})
                            db/jdbc-opts)]
         (db.proposal/resolve-for-recipe! tx user-id id)
         (jdbc/execute-one! tx (sql/format {:update :recipes
                                            :set {:deleted_at [:datetime "now"]}
                                            :where own}))
         (when (machine-write? opts)
           (db.event/record! tx user-id "deleted" current))
         (tel/log! {:level :info :data {:id id :user-id user-id}} "Recipe tombstoned")
         {:success true})))))

(defn list-deleted
  "The owner's tombstones, **most recently deleted first** — what the page that
  revisits and purges them is a list of.

  Lean columns plus `deleted_at`, because that is the one fact this list is ordered
  and read by, and the Scopes because what a deleted Recipe was filed under is how
  he will recognise it. No description: this is a listing, and the text is one click
  away on the surface that opens it.

  **The owner's alone and not by an audience.** There is no reading of `published`
  that would make a visitor's answer here anything but empty, and an endpoint that
  *could* answer them at all is a question they could ask. So this takes a user-id,
  like `list-versions`, and the handler is guarded rather than projected."
  [ds user-id]
  (->> (jdbc/execute! (db/get-conn ds)
         ;; Unqualified, unlike the listing's: there is no join here to make a column
         ;; name ambiguous, and `qualify` exists for that and says so.
         (sql/format {:select (conj (select-columns true user-id) :deleted_at)
                      :from [:recipes]
                      :where [:and (db/user-id-where-clause user-id)
                              [:not= :deleted_at nil]]
                      ;; The id breaks a tie, because two Recipes deleted in the same
                      ;; second is the ordinary case when a page offers 'purge' twice
                      ;; and the stamp is second-resolution.
                      :order-by [[:deleted_at :desc] [:id :desc]]})
         db/jdbc-opts)
       (with-scopes ds user-id)))

(defn purge-recipe!
  "Destroy a tombstone: the row, its whole version history and every association to
  a Scope. **This is what `delete-recipe` used to do**, and it now happens only when
  it is asked for by name, on a Recipe that has already been deleted once.

  Child rows first, like every other delete path in the suite — foreign keys are not
  enforced on this connection, so ON DELETE CASCADE would be a promise nothing keeps.

  The `recipe_scopes` rows are the half that is easy to forget, because nothing
  breaks visibly when they are left behind: the Recipe is gone from every listing and
  the orphans are only reachable by joining a table that no longer has the row. They
  would come back as somebody else's badge the day AUTOINCREMENT reuses the id.
  `purging-a-tombstone-takes-its-associations-with-it` reads the join table
  afterwards rather than trusting the parent's absence.

  **`recipe_events` is deliberately not in that list**, as it never was. A history
  row and a Scope association are parts of a Recipe and go with it; an event is the
  record that something happened to it, and the something did happen. Without that,
  an agent could create a Recipe and delete it again and the inbox — whose one
  promise is that changes show up there — would record the create and then erase it.
  What keeps an orphaned event readable is `recipe_title`, the snapshot migration 009
  takes for exactly this. So a purged Recipe's queue entries go on naming it, and go
  back to being un-openable, which is the honest thing: after this there really is
  nothing left to open.

  **Only a tombstone can be purged**, and that is the whole of the guard: a live
  Recipe is nil here, so this can never be the delete a caller did not mean to make.
  Nothing is written to the queue either — purging is not a change to the shelf,
  which is what the queue is about, and the `deleted` entry that brought him here is
  already in it."
  [ds user-id id]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (let [own [:and [:= :id id] (db/user-id-where-clause user-id)]]
      (when (jdbc/execute-one! tx
              (sql/format {:select [:id] :from [:recipes]
                           :where [:and own [:not= :deleted_at nil]]})
              db/jdbc-opts)
        (jdbc/execute-one! tx (sql/format {:delete-from :recipe_history
                                           :where [:= :recipe_id id]}))
        (db.scope/delete-recipe-scopes! tx id)
        (jdbc/execute-one! tx (sql/format {:delete-from :recipes :where own}))
        (tel/log! {:level :info :data {:id id :user-id user-id}} "Tombstone purged")
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
  entry's off the row, the older ones off their own history rows. It is always one
  of `'ui'` and `'machine'`; a nil was possible until migration 010 and is not any
  more, so a reader stepping through a history no longer meets a version whose
  origin is a third thing.

  **`:reason` and `:context` come the same two ways and are `nil` far more often**,
  which is the difference worth stating. They are what the agent said it was doing
  when it wrote that version, they are mandatory only on a machine write, and no
  version written before migration 015 has them — so a reader stepping through a
  history meets them on the newest entries and not on the old ones, and the page
  shows a line only where there is one. A version the *owner* wrote never has them
  by design, so their absence is not a gap to be filled in later."
  [ds user-id id]
  ;; **Tombstones included**, which is what makes a deleted Recipe visitable: the
  ;; queue's `deleted` entry opens the version viewer, and the viewer asks for this.
  ;; Safe to widen here and nowhere near a write: this read is the owner's at every
  ;; id — a visitor is answered 404 whether the Recipe is published or not — and the
  ;; last entry it hands back for a tombstone is the version it was deleted on, which
  ;; is precisely the thing he wanted to be able to read.
  (when-let [current (get-recipe ds user-id id {:lean? false :tombstones? true})]
    (let [history (jdbc/execute! (db/get-conn ds)
                    (sql/format {:select [:version :title :useful_when :description :created_at
                                          :source :reason :context]
                                 :from [:recipe_history]
                                 :where [:= :recipe_id id]
                                 :order-by [[:version :desc]]})
                    db/jdbc-opts)]
      {:versions (into [(assoc (content-of current)
                               :version (:version current)
                               :created_at (:modified_at current)
                               :source (:source current)
                               :reason (:reason current)
                               :context (:context current)
                               :current true)]
                       history)
       :total (inc (count history))
       ;; **The one read that sees a tombstone says that it is one**, and that is why
       ;; the key is here rather than being asked for separately. Nothing else can
       ;; tell a client: `GET /api/recipes/:id` answers 404 for a deleted Recipe, so
       ;; the surface that opens one out of the queue would have had no way to know
       ;; what it was reading — and it has to know, because a page that offers to file
       ;; or approve something deleted is a page whose buttons all fail. nil for a
       ;; Recipe on the shelf, so the absence is the ordinary case.
       :deleted_at (:deleted_at current)})))

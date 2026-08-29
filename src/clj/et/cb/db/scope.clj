(ns et.cb.db.scope
  "**Scope**: a `title`, a `description` and its `tags`, and a Recipe can be filed
  under any number of them. Cookbook's categories, under cookbook's own word for
  them.

  **The `tags` are extra search terms, and they are the Scope's own** — *i need
  that we can apply tags, i.e. additional search terms for scopes, too.* A Recipe
  filed under `utwig`, where `utwig` is tagged `backend tag2 tag3`, is found by
  `utwig` and by `backend` as surely as by a word of its own title: the Scope's
  title and its tags join the Recipe's title and tags as one flat pool of words
  per Recipe, so `ab utw` finds a Recipe titled `abc def` filed there. One write
  on the Scope re-labels every Recipe in it, which is the whole point of the tags
  hanging here rather than being copied onto each Recipe. `search-clause` below is
  the clause, and `db.recipe/list-recipes` says how a term chooses between the
  three sources.

  **The word means this and nothing else here.** It is not tracker's `scope`
  column, which says whether a task is private, and it is no longer the db layer's
  visibility argument — that is `audience` now, and
  `et.cb.db.recipe/audience-clause` says so where a reader will meet it.

  **Filing is not content.** A Scope is a way back to a Recipe, like a tag and
  unlike its text: associating one writes no history row and moves no version, for
  the same reason `published` and `tags` do not. What it does move is
  `modified_at` — see `et.cb.db.recipe/update-recipe`, which explains why the one
  optimistic-concurrency guard has to cover everything a save can send.

  **Scopes are the owner's, unconditionally.** Not 'unless the Recipe is
  published' — a published Recipe's badges are as private as an unpublished one's,
  because publishing decides who may read the *Recipe* and this decides how the
  owner files his shelf. The mechanism is the one `db.recipe/select-columns`
  already uses for tags: for a visitor the join is **not run at all**, so the
  response carries no `scopes` key rather than an empty vector. Absent and empty
  are different answers, and an empty one would still be a claim about the owner's
  filing. A machine token is on the owner's side of that line, like it is for
  tags — an agent that cannot read the list cannot file a Recipe under the right
  Scope, and a curated retrieval index is most of what an agent gets out of an
  agentic memory store.

  **The same boundary covers narrowing by them, not only reading them.**
  `exclusion-clause` below hides the Recipes filed under given Scopes,
  `inclusion-clause` beside it keeps only those, and `db.recipe/list-recipes` runs
  **neither** for a visitor — because a caller who can watch rows vanish can test
  which published Recipes carry a Scope, which is the very thing not sending the key
  withholds. Absent values and an untestable presence are two halves of one refusal,
  and this is the half the tags deliberately do not have.

  **`search-clause` is the third thing here that narrows a listing, and it is
  refused a visitor beside the other two.** A Scope's words widen the owner's
  search and nobody else's. That is the one place a Scope's tags behave unlike a
  Recipe's — those are searched for every audience, and their presence is testable
  as a stated consequence — and the asymmetry is the point rather than an oversight:
  a search that matched a Scope's title for an anonymous caller would hand back
  exactly the inference the two filters above are not run for, one probe at a time
  and without needing a filter at all. `db.recipe/list-recipes` decides it, off the
  audience, in the same `when-not` shape as its siblings.

  **The positive one leaks the same fact more directly**, which is worth saying here
  rather than only at the clause: excluding Scope 4 asks *which rows go away*, and a
  reader has to diff two listings to learn anything; including it asks *which rows
  carry it*, and the answer is the response. Same refusal, less inference — so if the
  two ever came to be gated differently, this is the one that would have to be the
  stricter, and they are gated in one place so that they cannot.

  **One query per listing, not one per row.** `scopes-by-recipe` fetches every
  association for the whole page in one statement and `attach` puts them on the
  rows in Clojure — tracker's `associate-categories-with-tasks` shape, and the
  reason a shelf of thirteen cards does not cost thirteen round trips. **Two
  listings read it that way now**: the shelf, whose rows are Recipes, and the inbox,
  whose rows are events that merely name one — which is what `attach`'s `id-key` is
  for and why there is still only one grouping query.

  **Nothing enforces the foreign keys** (`PRAGMA foreign_keys` is 0 here), so the
  join rows are deleted by hand at both ends: `delete-scope` below, and
  `db.recipe/delete-recipe` for the other side. See migration 007."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]))

(def ^:private scope-columns
  "What a Scope is, read back. There is no lean shape here and no column to
  withhold: a Scope *is* its title, its description and its tags, so a projection
  that left one out would be most of the entity missing. Which is why the
  privacy boundary for Scopes is whether the join runs at all rather than which
  columns it selects.

  **`tags` is in here and deliberately not in `scopes-by-recipe`**, which is the one
  read of a Scope that is not about the entity: it fetches what a *card's badge*
  wears — the title it shows and the description its tooltip says — and a badge has
  no use for the search words. Sending every Scope's tags with every row of the
  shelf would be a listing paying for something nothing renders. So the four reads
  of the Scope itself (`list-scopes`, `get-scope`, and what `create-scope` and
  `update-scope` answer with) carry them, and the badge join does not."
  [:id :title :description :tags])

(defn- own
  "A Scope of this user's, by id. Both halves in one clause, so no caller can ask
  for a row by id and check the owner afterwards."
  [user-id id]
  [:and [:= :id id] (db/user-id-where-clause user-id)])

(defn list-scopes
  "The user's Scopes, by title, each with `recipe_count` — how many of their
  Recipes are filed under it.

  The count is aggregated in this query rather than derived by the caller, for the
  reason `db.recipe`'s provenance split is: the one place that needs it is a
  confirmation dialog asking whether to delete a Scope that Recipes still use, and
  a count taken from whatever the client happens to have listed would be wrong
  exactly when the shelf is narrowed by a search. The LEFT JOIN is what keeps a
  Scope nothing is filed under in the answer, at 0.

  **It counts what is on the shelf, so it joins `recipes` to skip the tombstones.**
  Since 012 a deleted Recipe keeps its filing — the associations are part of what a
  tombstone is for — so counting the join rows alone would have this answer, and the
  confirmation dialog that reads it, claim a Scope is still in use by Recipes that
  have been deleted. This is the one count in the app that `db.recipe/audience-clause`
  does not cover, because it is asked from the other side of the join; it was found by
  a test rather than by reading, which is the honest way round to record it."
  [ds user-id]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select (into (mapv #(keyword (str "scopes." (name %))) scope-columns)
                               [[[:count :recipes.id] :recipe_count]])
                 :from [:scopes]
                 :left-join [:recipe_scopes [:= :recipe_scopes.scope_id :scopes.id]
                             :recipes [:and [:= :recipes.id :recipe_scopes.recipe_id]
                                       [:= :recipes.deleted_at nil]]]
                 ;; Qualified, because joining `recipes` brings a second `user_id`
                 ;; into the query — the two-argument form of that function exists for
                 ;; exactly this, so the NULL rule stays in the one place that knows it.
                 :where (db/user-id-where-clause :scopes.user_id user-id)
                 :group-by [:scopes.id]
                 :order-by [[:scopes.title :asc] [:scopes.id :asc]]})
    db/jdbc-opts))

(defn get-scope
  "One Scope of this user's, or nil."
  [ds user-id id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select scope-columns :from [:scopes] :where (own user-id id)})
    db/jdbc-opts))

(defn- title-taken?
  "Whether this user already has a Scope by that name, ignoring `except-id` so a
  rename onto its own title is not a clash.

  **The same name, whatever the case** — *make sure that in cookbook we cant create
  two scopes with the same name.* `Baking`, `baking` and `BAKING` are one name, and
  before this they were three Scopes: a shelf with all three on it is one where
  every filing decision is a guess, and the badges on a card are indistinguishable
  at a glance. Trimming already made ` Baking` the same name; case is the other
  half of the same sentence.

  **So this is now the constraint rather than a readable answer in front of one.**
  `UNIQUE(title, user_id)` is still there and still catches an exact duplicate, but
  it is a backstop that this check is strictly stricter than — and it is deliberately
  *not* being replaced by a case-insensitive index. Two reasons, both about real
  data: SQLite's `NOCASE` collation folds ASCII only, so it would disagree with the
  fold below on the first non-ASCII title and leave two rules where there is meant to
  be one; and a `CREATE UNIQUE INDEX` migration **fails outright** on a database that
  already holds a case-variant pair, which is a deploy that stops on the owner's own
  Scopes rather than a check that quietly starts refusing new ones. Existing rows are
  left alone; what changes is what can be written from here on.

  The fold is `str/lower-case`, which is the host's Unicode one, so `KÄSE` and `käse`
  are the same name too — where SQLite's `lower()` would have called them different.
  That is also why the comparison happens in Clojure over the user's own titles
  rather than in the `:where` clause: the query cannot fold what this can, and the
  list being read is one person's Scopes.

  It does not bind the nil owner's rows through the index either, because SQLite
  treats NULLs in a UNIQUE index as distinct — so in dev this check *is* the whole
  constraint, exactly as it was before, and now it is the whole constraint everywhere."
  [ds user-id title except-id]
  (let [wanted (str/lower-case (str title))]
    (->> (jdbc/execute! (db/get-conn ds)
           (sql/format {:select [:id :title] :from [:scopes]
                        :where (cond-> [:and (db/user-id-where-clause user-id)]
                                 except-id (conj [:<> :id except-id]))})
           db/jdbc-opts)
         (some (fn [row] (= wanted (str/lower-case (str (:title row))))))
         boolean)))

(defn create-scope
  "A new Scope: `{:title :description :tags}`, the title trimmed like a Recipe's and
  the description and the tags left as typed. nil when the user already has one by
  that title — callers turn that into a 409 rather than a 500 from the unique index.

  **The tags are not trimmed, exactly as a Recipe's are not** (`db.recipe`'s
  `merge-tags` says so from the other side): the column holds what the owner typed,
  and the search reads it a word at a time — leading whitespace is one more
  separator to a clause that already treats every separator alike. The title is
  trimmed because it is a name, and because `UNIQUE(title, user_id)` would
  otherwise let ` Baking` and `Baking` both exist."
  [ds user-id {:keys [title description tags]}]
  (let [title (str/trim (str title))]
    (when-not (title-taken? ds user-id title nil)
      (let [result (jdbc/execute-one! (db/get-conn ds)
                     (sql/format {:insert-into :scopes
                                  :values [{:title title
                                            :description (or description "")
                                            :tags (or tags "")
                                            :user_id user-id}]
                                  :returning scope-columns})
                     db/jdbc-opts)]
        (tel/log! {:level :info :data {:id (:id result) :user-id user-id}} "Scope created")
        result))))

(def no-such-scope
  "`update-scope`'s answer for 'there is no such Scope of yours'. A named value
  rather than a bare keyword at both ends, the shape `db.recipe/visitor-audience`
  already uses: the caller compares against this var, so the two namespaces cannot
  come to spell the same refusal differently."
  ::no-such-scope)

(def title-taken
  "`update-scope`'s answer for 'you already have a Scope with that title'. See
  `no-such-scope`."
  ::title-taken)

(defn update-scope
  "Save a Scope's fields — `{:title :description :tags}`. **A field the caller left
  out keeps its value**, the rule `db.recipe/merge-content` already sets for a
  Recipe, so an edit meant for the description cannot silently blank the title, and
  a rename cannot silently drop the search words every Recipe in the Scope is found
  by. An empty string is a real value and does clear the tags, which is the only way
  to say 'none' — the pair `db.recipe/merge-tags` makes for a Recipe.

  Three outcomes: the saved Scope, `no-such-scope` when the id matches nothing the
  user owns, or `title-taken` when the new title is one of their other Scopes'.

  **The two refusals are told apart here rather than by the caller**, which used
  to answer both with nil and work out which it was from a separate, earlier
  `get-scope`. Two reads mean two moments, and between them the row can go: the
  caller would then say 'you already have a Scope with that title' about a Scope
  that no longer exists — the one answer that is wrong in both directions, since
  it names a clash that is not there and hides the deletion that is. Both
  decisions here are made inside one transaction off the same read, so the refusal
  cannot disagree with the state it was decided from."
  [ds user-id id {:keys [title description tags]}]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (if-let [current (get-scope tx user-id id)]
      (let [title (if (some? title) (str/trim (str title)) (:title current))]
        (if (title-taken? tx user-id title id)
          title-taken
          (let [result (jdbc/execute-one! tx
                         (sql/format {:update :scopes
                                      :set {:title title
                                            :description (if (some? description)
                                                           description
                                                           (:description current))
                                            :tags (if (some? tags)
                                                    tags
                                                    (:tags current))}
                                      :where (own user-id id)
                                      :returning scope-columns})
                         db/jdbc-opts)]
            (tel/log! {:level :info :data {:id id :user-id user-id}} "Scope saved")
            result)))
      no-such-scope)))

(defn delete-scope
  "Remove a Scope of this user's **together with every association to it**, in one
  transaction, join rows first.

  The Recipes themselves are untouched: each one keeps all its text and simply
  loses a badge. Deleting the join rows is not a nicety — nothing enforces the
  foreign key on this connection, so `ON DELETE CASCADE` in migration 007 is a
  promise nothing keeps, and skipping this would leave rows pointing at a Scope
  that no longer exists. Those orphans would then reappear as ghosts the day an id
  is reused by AUTOINCREMENT rollover or a restored backup.

  nil when the id matches nothing the user owns."
  [ds user-id id]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (when (get-scope tx user-id id)
      (jdbc/execute-one! tx (sql/format {:delete-from :recipe_scopes
                                         :where [:= :scope_id id]}))
      (jdbc/execute-one! tx (sql/format {:delete-from :scopes :where (own user-id id)}))
      (tel/log! {:level :info :data {:id id :user-id user-id}} "Scope deleted")
      {:success true})))

;; ---------------------------------------------------------------------------
;; the associations

(defn delete-recipe-scopes!
  "Unfile a Recipe from every Scope. Called by `db.recipe/delete-recipe` inside
  its transaction, for the reason spelled out on `delete-scope`: the cascade in
  the schema does not fire."
  [tx recipe-id]
  (jdbc/execute-one! tx (sql/format {:delete-from :recipe_scopes
                                     :where [:= :recipe_id recipe-id]})))

(defn- owned-ids
  "Which of `ids` are this user's own Scopes, as a set."
  [tx user-id ids]
  (if (empty? ids)
    #{}
    (into #{}
          (map :id)
          (jdbc/execute! tx
            (sql/format {:select [:id] :from [:scopes]
                         :where [:and [:in :id ids] (db/user-id-where-clause user-id)]})
            db/jdbc-opts))))

(defn current-ids
  "The Scope ids a Recipe is filed under, as a set. Narrowed through `scopes` on
  the owner rather than read straight off the join table, so the answer is the
  same one every other read here gives."
  [ds user-id recipe-id]
  (into #{}
        (map :scope_id)
        (jdbc/execute! (db/get-conn ds)
          (sql/format {:select [:recipe_scopes.scope_id]
                       :from [:recipe_scopes]
                       :join [:scopes [:= :scopes.id :recipe_scopes.scope_id]]
                       :where [:and
                               [:= :recipe_scopes.recipe_id recipe-id]
                               (db/user-id-where-clause user-id)]})
          db/jdbc-opts)))

(defn set-recipe-scopes!
  "Replace a Recipe's Scope associations with `scope-ids`, and say whether that
  changed anything.

  **Ids the caller does not own are dropped rather than refused.** Cross-user
  filing is then impossible by construction instead of by a check somebody has to
  remember, and the write path answers with the Recipe's `scopes` — so the
  response is the receipt for what was actually filed, and the caller is never
  told whether an id it does not own exists at all.

  Returns true when the set of associations is different afterwards, which is what
  lets `update-recipe` treat a Scope change as a touch: `modified_at` moves, no
  version is made, and a request that changed nothing stays a no-op. Written as a
  delete-then-insert of the whole set rather than a diff, because the set is
  small, the statement count is fixed and there is no partial state a reader could
  observe — it runs inside the caller's transaction."
  [tx user-id recipe-id scope-ids]
  (let [wanted (owned-ids tx user-id (distinct scope-ids))
        current (current-ids tx user-id recipe-id)]
    (when (not= wanted current)
      (delete-recipe-scopes! tx recipe-id)
      (when (seq wanted)
        (jdbc/execute-one! tx
          (sql/format {:insert-into :recipe_scopes
                       :values (mapv (fn [id] {:recipe_id recipe-id :scope_id id})
                                     (sort wanted))})))
      (tel/log! {:level :info :data {:recipe-id recipe-id :user-id user-id
                                     :scope-ids (vec (sort wanted))}}
                "Recipe scopes saved")
      true)))

(defn- scopes-by-recipe
  "Every association for `recipe-ids`, grouped by recipe id — **one statement for
  the whole listing**, which is the point of this function existing rather than a
  per-row lookup. Ordered by title, so the badges on a card read in the same order
  as the Scopes page lists them."
  [ds user-id recipe-ids]
  (->> (jdbc/execute! (db/get-conn ds)
         (sql/format {:select [:recipe_scopes.recipe_id :scopes.id :scopes.title
                               :scopes.description]
                      :from [:recipe_scopes]
                      :join [:scopes [:= :scopes.id :recipe_scopes.scope_id]]
                      :where [:and
                              [:in :recipe_scopes.recipe_id recipe-ids]
                              (db/user-id-where-clause user-id)]
                      :order-by [[:scopes.title :asc] [:scopes.id :asc]]})
         db/jdbc-opts)
       (group-by :recipe_id)))

(defn attach
  "Put `:scopes` on each of `rows` — a vector of `{:id :title :description}`, empty
  when a Recipe is filed under none.

  **`id-key` is which key on a row names the Recipe, and it defaults to `:id`**
  because a row of the shelf *is* a Recipe. The inbox's rows are not: an event's
  `:id` is the event's own and its Recipe is `:recipe_id`, so `db.event/list-unseen`
  passes that. Grouping such a row by `:id` would key the answer off an event id and
  attach one Recipe's filing to another Recipe's entry — silently, and plausibly,
  because both are integers and most of them exist.

  It is a parameter rather than something a caller reshapes its rows into, and
  rather than a second grouping query beside `scopes-by-recipe`. A call site that
  renamed `:recipe_id` to `:id` on the way in would be claiming an event is a
  Recipe, and a copied query would be a second answer to 'what is this filed under'
  — which is the thing this namespace argues against having.

  **The ids may repeat, and nothing here dedupes them.** That is the inbox's normal
  case rather than a corner: several events can name one Recipe, so a queue of
  thirteen entries about three Recipes hands over thirteen ids. `IN` is indifferent
  to a duplicate — it is a set test, and the join still returns each association
  once — and so is the per-row lookup, which reads the same grouped entry for every
  row that names it. `two-events-naming-one-recipe-both-get-its-scopes` is what says
  so out loud, because it is the case a test would otherwise not think to build.

  **Only ever called for a caller who may see them, and there are now two callers
  deciding that** — so the sentence has to name both rather than the one it was
  written for. The shelf: `db.recipe/with-scopes` decides from the audience and does
  not run this at all for a visitor, so a visitor's row has no `scopes` key rather
  than an empty vector. The inbox: `db.event/list-unseen`, whose only caller is
  `server.inbox-handler/list-inbox-handler` behind `common/owner-caller?`, so a
  visitor and a machine token are both refused the page before this could run, and
  the `user-id` it hands over is the owner's own.

  Nothing in here re-checks it, deliberately — two places answering one question is
  how they come to disagree — which is why this takes a `user-id` and not an
  audience: there is no audience value it could be given that means 'a visitor'.

  `:recipe_id` is stripped off the attached maps: it is the key the grouping was
  done by, not part of what a Scope is."
  ([ds user-id rows] (attach ds user-id rows :id))
  ([ds user-id rows id-key]
   (if (empty? rows)
     rows
     (let [by-recipe (scopes-by-recipe ds user-id (mapv id-key rows))]
       (mapv (fn [row]
               (assoc row :scopes
                      (mapv #(dissoc % :recipe_id) (get by-recipe (id-key row) []))))
             rows)))))

(defn attach-one
  "`attach` for a single row, nil-safe so a caller can pass a result that may not
  exist without branching on it first."
  [ds user-id recipe]
  (when recipe
    (first (attach ds user-id [recipe]))))

(defn exclusion-clause
  "A `:where` clause hiding every Recipe filed under one of `scope-ids`, or nil
  when the caller named none. The one thing this namespace does that *narrows* a
  listing rather than attaching to it.

  **`NOT EXISTS`, correlated on `recipe-id-column`, and not a second join.** The
  caller passes that column qualified, for the reason
  `db.proposal/pending-exists-clause` takes the same argument: the listing already
  left-joins `recipe_history` under a `GROUP BY` to aggregate the provenance
  split, and another multi-row join would multiply those counts. It has to be a
  clause and not a filter over the rows for a second reason — the shelf is ranked
  and sliced by the query, so rows taken away afterwards would leave a short page
  that nothing can top up.

  **A Recipe filed under no Scope at all is never excluded.** It falls out of `NOT
  EXISTS` on its own, but it is the case a reader asks about: the rule is 'carries
  none of these', not 'is filed under something else'.

  **An id from somebody else's shelf excludes nothing, silently** — no error and
  no 404. The subquery joins through `scopes` and narrows on its `user_id`, which
  is where migration 007 put the ownership question and where every other read
  here asks it, so an unowned id simply matches no association. That is
  `set-recipe-scopes!`'s rule read backwards: ids the caller does not own drop out,
  and the caller is never told whether they exist.

  Several ids are **one** clause with an `IN` rather than one clause each, which is
  the same thing said shorter: a Recipe survives only if it carries none of them.
  Tracker's `build-exclusion-clauses` emits one clause per category type and ANDs
  them; cookbook has one kind of category, so that collapses to this.

  It takes a `user-id` and never an audience, exactly as `attach` does and for the
  same reason: there is no value of this argument that means 'a visitor'. Who may
  narrow by Scopes at all is `db.recipe/list-recipes`' decision, made from the
  audience, and this cannot be handed a caller it should have refused."
  [user-id recipe-id-column scope-ids]
  (when (seq scope-ids)
    [:not [:exists {:select [[[:inline 1]]]
                    :from [:recipe_scopes]
                    :join [:scopes [:= :scopes.id :recipe_scopes.scope_id]]
                    :where [:and
                            [:= :recipe_scopes.recipe_id recipe-id-column]
                            [:in :recipe_scopes.scope_id (vec scope-ids)]
                            (db/user-id-where-clause :scopes.user_id user-id)]}]]))

(defn inclusion-clause
  "A `:where` clause keeping **only** the Recipes filed under at least one of
  `scope-ids`, or nil when the caller named none. `exclusion-clause`'s mirror, and
  the second thing this namespace does that narrows a listing rather than attaching
  to it.

  > and on the main page, below the searchbar, list all scopes and have them be an
  > OR filter for scopes, i.e. it filters when one or more are selected for all
  > recipes which match one or more selectd scopes

  **The `IN` *is* the OR.** One clause over the whole set and not one clause per id
  ANDed together, which would be an AND filter — *carries all of these* — and is the
  thing he named the opposite of. Its sibling emits the same `IN` for the same
  reason and reads as the same sentence negated: a Recipe survives if it carries at
  least one of them, or, there, only if it carries none.

  Every argument `exclusion-clause` makes about its shape holds here unchanged —
  `EXISTS` correlated on the passed-in qualified `recipe-id-column` rather than a
  second join (the listing already left-joins `recipe_history` under a `GROUP BY`,
  and another multi-row join would multiply those counts), and a clause rather than a
  filter over the rows because the shelf is ranked and sliced by the query. **Two of
  its arguments invert, and both are worth stating:**

  **A Recipe filed under no Scope at all now falls out**, where the exclusion always
  kept one. It drops out of `EXISTS` on its own, exactly as it drops out of `NOT
  EXISTS` on its own — the same mechanism, and here it is the *wanted* answer rather
  than the exception the negative one had to spell out. Asked to see the Recipes in
  Baking, a reader is not asking to also see the ones filed nowhere.

  **An id the caller does not own narrows to nothing rather than excluding
  nothing.** The subquery joins through `scopes` and narrows on its `user_id`, so an
  unowned id matches no association — which under `NOT EXISTS` means *takes nothing
  away* and under `EXISTS` means *keeps nothing*. So the failure mode of a stale id
  inverts with the clause: an exclusion carrying one is a full shelf, an inclusion
  carrying one is an **empty** one. That is why the client drops a deleted Scope from
  its selection more urgently than from its exclusions (`state/delete-scope`), and
  why the shelf's chip row keeps a way to clear the whole selection even when a
  chip's Scope is gone.

  It takes a `user-id` and never an audience, exactly as its sibling and `attach` do,
  and for the same reason: there is no value of this argument that means 'a visitor'.
  Who may narrow by Scopes at all is `db.recipe/list-recipes`' decision, made from the
  audience, and this cannot be handed a caller it should have refused."
  [user-id recipe-id-column scope-ids]
  (when (seq scope-ids)
    [:exists {:select [[[:inline 1]]]
              :from [:recipe_scopes]
              :join [:scopes [:= :scopes.id :recipe_scopes.scope_id]]
              :where [:and
                      [:= :recipe_scopes.recipe_id recipe-id-column]
                      [:in :recipe_scopes.scope_id (vec scope-ids)]
                      (db/user-id-where-clause :scopes.user_id user-id)]}]))

(defn search-clause
  "A clause for **one search term**: true when the Recipe at `recipe-id-column` is
  filed under a Scope of this user's carrying a word that starts with `term`, in its
  title or in its tags. The third thing here that narrows a listing, and the reason
  `scopes.tags` exists — *additional search terms for scopes*.

  **One term and not the whole search**, which is what makes this composable with
  the columns on the Recipe's own row: `db.recipe/list-recipes` hands it to
  `db/build-word-prefix-search-clause` as that function's `extra-disjunct-fn`, so
  each term is ORed against `recipes.title`, `recipes.tags` and this, and the terms
  are ANDed across the lot. `ab utw` then finds a Recipe titled `abc def` filed
  under `utwig` — `ab` from the title, `utw` from the Scope — and no term needs to
  know where any other one landed. A clause over the whole search would have meant
  *and it is filed under something matching too*, which is a different question and
  a narrower one.

  **The title and the tags, and not the description.** The same pair as a Recipe's,
  refused for the same reason `useful_when` and the body are: a Scope's description
  is prose — the line saying what belongs in it, written for a reader and for an
  agent choosing where to file — while a title is a name and a tag is a word chosen
  to be found by. The rule stays 'names and curated words', whichever table it is
  read from.

  **A Recipe's several Scopes are an OR for free.** `EXISTS` asks whether *any* row
  of the join satisfies the condition, so a Recipe in three Scopes matches a term
  that lands in any one of their titles or tag lists, and there is nothing per-Scope
  to write down. A Recipe filed under nothing falls out of it and is left to its own
  two columns, which is the correct silence: it has no Scope words, so it is found
  by its own words alone.

  **It doubles the `instr` tests a term costs**, which is worth saying out loud
  because the count is already large: the word rule tries every separator, so one
  term is one test per separator per column, and this adds a second column pair
  inside the subquery. It is affordable for the same reason the shape was
  affordable to begin with — the correlation is on the join table's own primary key,
  so the inner scan is over *this Recipe's* handful of associations rather than over
  the Scopes, and the shelf being read is one person's.

  Every argument its two siblings make about shape holds here unchanged: `EXISTS`
  correlated on a **qualified** `recipe-id-column` rather than a second join, since
  the listing already left-joins `recipe_history` under a `GROUP BY` and another
  multi-row join would multiply the provenance counts — and an unqualified `id`
  inside would correlate to `recipe_scopes` itself and be trivially true. It
  narrows through `scopes.user_id` like every other read here, so a Scope is never
  read across owners, and it takes a `user-id` and never an audience for the reason
  they give: there is no value of this argument that means 'a visitor'. Who may
  search Scope words at all is `list-recipes`' decision, made from the audience —
  and unlike the tags on a Recipe, a visitor is not one of them."
  [user-id recipe-id-column term]
  [:exists {:select [[[:inline 1]]]
            :from [:recipe_scopes]
            :join [:scopes [:= :scopes.id :recipe_scopes.scope_id]]
            :where [:and
                    [:= :recipe_scopes.recipe_id recipe-id-column]
                    (db/user-id-where-clause :scopes.user_id user-id)
                    (db/word-prefix-term-clause term [:scopes.title :scopes.tags])]}])

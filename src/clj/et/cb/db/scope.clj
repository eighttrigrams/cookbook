(ns et.cb.db.scope
  "**Scope**: a `title` and a `description`, and a Recipe can be filed under any
  number of them. Cookbook's categories, under cookbook's own word for them.

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
  `exclusion-clause` below hides the Recipes filed under given Scopes, and
  `db.recipe/list-recipes` does not run it for a visitor at all — because a caller
  who can watch rows vanish can test which published Recipes carry a Scope, which
  is the very thing not sending the key withholds. Absent values and an untestable
  presence are two halves of one refusal, and this is the half the tags
  deliberately do not have.

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
  withhold: a Scope *is* its title and its description, so a projection that left
  the description out would be most of the entity missing. Which is why the
  privacy boundary for Scopes is whether the join runs at all rather than which
  columns it selects."
  [:id :title :description])

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
  Scope nothing is filed under in the answer, at 0."
  [ds user-id]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select (into (mapv #(keyword (str "scopes." (name %))) scope-columns)
                               [[[:count :recipe_scopes.recipe_id] :recipe_count]])
                 :from [:scopes]
                 :left-join [:recipe_scopes [:= :recipe_scopes.scope_id :scopes.id]]
                 :where (db/user-id-where-clause user-id)
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
  "Whether this user already has a Scope with that title, ignoring `except-id` so
  a rename onto its own title is not a clash.

  `UNIQUE(title, user_id)` is the real constraint and this is the readable answer
  in front of it — a caller gets 'that title is taken' instead of a SQLite
  exception surfacing as a 500. It does not bind the nil owner's rows, because
  SQLite treats NULLs in a UNIQUE index as distinct, so in dev this check *is* the
  constraint rather than a friendlier restatement of it."
  [ds user-id title except-id]
  (some? (jdbc/execute-one! (db/get-conn ds)
           (sql/format {:select [:id] :from [:scopes]
                        :where (cond-> [:and
                                        [:= :title title]
                                        (db/user-id-where-clause user-id)]
                                 except-id (conj [:<> :id except-id]))})
           db/jdbc-opts)))

(defn create-scope
  "A new Scope: `{:title :description}`, the title trimmed like a Recipe's and the
  description left as typed. nil when the user already has one by that title —
  callers turn that into a 409 rather than a 500 from the unique index."
  [ds user-id {:keys [title description]}]
  (let [title (str/trim (str title))]
    (when-not (title-taken? ds user-id title nil)
      (let [result (jdbc/execute-one! (db/get-conn ds)
                     (sql/format {:insert-into :scopes
                                  :values [{:title title
                                            :description (or description "")
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
  "Save a Scope's fields. **A field the caller left out keeps its value**, the
  rule `db.recipe/merge-content` already sets for a Recipe, so an edit meant for
  the description cannot silently blank the title.

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
  [ds user-id id {:keys [title description]}]
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
                                                           (:description current))}
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

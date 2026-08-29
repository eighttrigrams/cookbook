(ns et.cb.db.proposal
  "**A proposal**: a rewrite an agent wants to make to a Recipe it may not write
  directly, waiting for the owner to approve or dismiss it.

  The rule that sends a write here instead of through is `db.recipe/machine-only?`
  — a Recipe is the agents' to write freely only while *every* one of its versions
  is stamped `machine`. One save of his own anywhere in its history and the next
  agent edit has to ask, which is what he asked for: *when there is a human
  modification inbetween, it needs approval.*

  **A proposal is a proposed version, so it is exactly the three content fields.**
  Title, useful-when, description — the same three `recipe_history` holds. No tags
  and no `scope_ids`: filing is not versioned and the rule is about the text he
  wrote, so a machine retags and refiles an approval-required Recipe freely and only
  its content waits.

  **At most one unresolved proposal per Recipe, and the schema says so** — a partial
  unique index, not a check in a handler (migration 011). That is what makes *there
  are no merge conflicts* a property of the database: a second proposal cannot land
  while one is pending, so the write path answers 409 with the pending text and an
  agent replaces it with `?overwrite=true` rather than racing.

  **Resolved rows are kept.** The index is partial, so they cost nothing, and a
  dismissal is a fact about what an agent tried and the owner declined.

  Two invariants tie this table to the inbox, and both are held here rather than
  hoped for:

  - **A `proposed` event is unseen exactly while its proposal is unresolved.**
    Every path that resolves marks the event seen in the same transaction, and
    `POST /api/inbox/:id/seen` refuses a `proposed` event outright, so the two
    cannot come apart.
  - **An overwrite keeps the event where it is in the queue.** The proposal is
    updated in place — `modified_at` moves, `created_at` does not — and the event
    keeps its id, so an agent revising three times does not push itself to the
    bottom of a queue he is working through oldest-first, and does not appear three
    times."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]
            [et.cb.db.event :as db.event]))

(def ^:private proposal-columns
  "What a proposal is, read back. The three content fields, what it was written
  against, the two stamps — `created_at` for its place in the queue and
  `modified_at` for the last revision of it — and the agent's own two sentences
  about the write.

  **`reason` and `context` are read back because this *is* the review surface.**
  They were asked for here first — *shown on the inbox when i look at the item …
  on the item page for review* — and a proposal that could not hand them to the page
  deciding on it would be the one place the pair is missing when it matters most.
  They are NULL on every proposal filed before migration 015, and the page shows a
  line only where there is one."
  [:id :recipe_id :base_version :title :useful_when :description
   :created_at :modified_at :reason :context])

(defn- unresolved
  "The clause that makes this table's whole design work, in one place: a proposal is
  pending exactly while `resolved_at` is NULL, which is the condition the partial
  unique index is built on. Written once so a read cannot come to disagree with the
  constraint about what 'pending' means."
  []
  [:is :resolved_at nil])

(defn- recipe-title
  "The Recipe's own title, read inside the caller's transaction.

  **Read here rather than accepted from the caller**, and that is what makes one bug
  unrepeatable rather than merely fixed. A `proposed` event's `recipe_title` is a
  snapshot of the *Recipe's* title (migration 009); what used to be handed to
  `db.event/record!` was the **proposal's**, so an agent proposing a rename put its own
  wording into the field the queue heads the row with, and an entry that outlived its
  Recipe named it something it had never been called. No call site is now in a position
  to pass the wrong string.

  It is deliberately the one fact here that is *not* passed in the way `base_version`
  is, and the difference is real rather than stylistic. `base_version` has to be the
  version the agent's edit was computed against, so it can only come from the caller's
  own read; the title is a display snapshot, so it should be as fresh as the row it is
  a snapshot of."
  [tx user-id recipe-id]
  (:title (jdbc/execute-one! tx
            (sql/format {:select [:title]
                         :from [:recipes]
                         :where [:and [:= :id recipe-id]
                                 (db/user-id-where-clause user-id)]})
            db/jdbc-opts)))

(defn pending-for
  "The unresolved proposal for one Recipe, or nil. At most one can exist — the
  index, not a `LIMIT`."
  [ds user-id recipe-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select proposal-columns
                 :from [:recipe_proposals]
                 :where [:and [:= :recipe_id recipe-id]
                         (db/user-id-where-clause user-id)
                         (unresolved)]})
    db/jdbc-opts))

(defn by-event
  "The proposal a `proposed` inbox entry points at, or nil — by **event** id, which
  is what the approve and dismiss routes are keyed by.

  Keyed by the event and not by the proposal because the queue is what he is acting
  on: he is looking at an entry, and the entry is the thing with an id in the URL.
  Joined rather than read in two steps so that 'this event, that proposal, one
  owner' is one question with one answer.

  It deliberately does **not** narrow on `unresolved`: a caller that resolves an
  already-resolved proposal has to be told which of the two it is — gone or already
  answered — and a read that hid resolved rows could only ever say 'not found'."
  [ds user-id event-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select (into (mapv #(keyword (str "recipe_proposals." (name %)))
                                     proposal-columns)
                               [[:recipe_proposals.resolved_at :resolved_at]
                                [:recipe_proposals.resolution :resolution]
                                [:recipe_events.id :event_id]])
                 :from [:recipe_events]
                 :join [:recipe_proposals
                        [:= :recipe_proposals.id :recipe_events.proposal_id]]
                 :where [:and [:= :recipe_events.id event-id]
                         [:= :recipe_events.kind [:inline "proposed"]]
                         (db/user-id-where-clause :recipe_events.user_id user-id)]})
    db/jdbc-opts))

(defn propose!
  "Write a proposal for `recipe-id`, or replace the one already pending, **in one
  transaction with its inbox entry**.

  `current-version` is the Recipe's version now, which becomes `base_version`: the
  caller has it in hand and passing it in is what keeps this namespace from needing
  `db.recipe` (which needs *this* one).

  **The three fields are expected complete**, already merged through
  `db.recipe/merge-content` — a proposal is a proposed *version*, and a version in this
  app is all three or it is not one. So this does not implement absent-keeps and must
  not start to: the caller merges, because the caller is the one holding the Recipe.
  The `(or … \"\")` below is the column's own default for a caller that genuinely
  proposes an empty field, not a place for a missing key to be quietly filled in.

  Two paths, and the difference between them is the queue:

  - **Nothing pending** — insert, then append one `proposed` event carrying the new
    proposal's id and the base version. That entry is his notification, and it goes
    to the bottom of the queue like every other.
  - **Something pending** — update it in place. `created_at` stays where it was, so
    the entry keeps its position in a queue he works through oldest-first, and the
    event keeps its id, so three revisions are one thing to answer rather than
    three. `modified_at` moves, and so does `base_version` — with the event's
    `version` **and its `recipe_title`** alongside it — because the text being proposed
    now answers the Recipe as it reads now, and telling him it was proposed against an
    older version, or naming the Recipe as it read two of his saves ago, would both
    overstate how stale it is.

  Either way the entry is titled with the **Recipe's** title and never with the
  proposal's — see `recipe-title`.

  **`reason` and `context` ride with the text and are replaced with it**, which is
  the only thing a revision could do with them: the pending proposal is whatever the
  agent last proposed, so a second `?overwrite=true` write carrying a new
  explanation is now explaining the text it also just replaced. Keeping the first
  attempt's sentences beside the third attempt's text is the mismatch this avoids.

  Returns the proposal as it now reads."
  [ds user-id recipe-id current-version {:keys [title useful_when description reason context]}]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (let [existing (pending-for tx user-id recipe-id)
          title-now (recipe-title tx user-id recipe-id)
          values {:base_version current-version
                  :title title
                  :useful_when (or useful_when "")
                  :description (or description "")
                  ;; NULL rather than `""` for a caller that sends nothing, so that
                  ;; 'not recorded' stays distinguishable from 'said nothing' — 015's
                  ;; call. Through the API a machine cannot send nothing (400), so in
                  ;; practice a NULL here is a proposal from before that rule.
                  :reason reason
                  :context context}]
      (if existing
        (let [result (jdbc/execute-one! tx
                       (sql/format {:update :recipe_proposals
                                    :set (assoc values :modified_at [:raw "datetime('now')"])
                                    :where [:= :id (:id existing)]
                                    :returning proposal-columns})
                       db/jdbc-opts)]
          (db.event/rebase-proposal! tx user-id (:id existing) current-version title-now)
          (tel/log! {:level :info :data {:id (:id existing) :recipe-id recipe-id
                                         :user-id user-id :base-version current-version}}
                    "Recipe proposal replaced")
          result)
        (let [result (jdbc/execute-one! tx
                       (sql/format {:insert-into :recipe_proposals
                                    :values [(assoc values :recipe_id recipe-id
                                                    :user_id user-id)]
                                    :returning proposal-columns})
                       db/jdbc-opts)]
          (db.event/record! tx user-id "proposed" {:id recipe-id :title title-now
                                                   :version current-version}
                            {:proposal_id (:id result)})
          (tel/log! {:level :info :data {:id (:id result) :recipe-id recipe-id
                                         :user-id user-id :base-version current-version}}
                    "Recipe proposal filed")
          result)))))

(defn resolve!
  "Close a proposal — `\"approved\"` or `\"dismissed\"` — and mark its inbox entry
  seen, **in the caller's transaction**.

  A `tx` and not a datasource, because this is never the whole of what happens: a
  dismissal is this plus nothing, an approval is this plus a new version of the
  Recipe, and a delete is this plus the Recipe going away. All three have to be one
  write or the invariant that a `proposed` entry is unseen exactly while its
  proposal is unresolved is only true most of the time.

  `resolution` may be nil, which is the one case that is neither of the two words:
  the Recipe was deleted, so the question can no longer be answered. `resolved_at`
  is what the partial index reads, so a nil resolution still takes the proposal out
  of the way — and the `CHECK` permits it, because inventing a third word would be
  claiming he decided something he never saw."
  [tx user-id {:keys [id event_id]} resolution]
  (jdbc/execute-one! tx
    (sql/format {:update :recipe_proposals
                 :set {:resolved_at [:raw "datetime('now')"]
                       :resolution resolution}
                 :where [:= :id id]}))
  (when event_id
    (db.event/seen! tx user-id event_id))
  (tel/log! {:level :info :data {:id id :user-id user-id :resolution resolution}}
            "Recipe proposal resolved"))

(defn resolve-for-recipe!
  "Close whatever proposal is pending for a Recipe, with no resolution word, and
  mark its entry seen. Called by `db.recipe/delete-recipe` inside its transaction.

  **The opposite choice from `recipe_events`, deliberately.** A deleted Recipe's
  events are left alone, because an event records that something happened and it
  did; a proposal is a question waiting for an answer, and a question about a Recipe
  that no longer exists cannot be answered — leaving it pending would keep an
  unanswerable entry at the top of the queue and go on blocking the agent that filed
  it. The `deleted` event is what remains as the record.

  The row itself is kept, like every other resolved proposal: what an agent tried is
  a fact even when the Recipe is gone."
  [tx user-id recipe-id]
  (when-let [pending (pending-for tx user-id recipe-id)]
    (let [event (jdbc/execute-one! tx
                  (sql/format {:select [:id] :from [:recipe_events]
                               :where [:and [:= :proposal_id (:id pending)]
                                       [:= :kind [:inline "proposed"]]
                                       (db/user-id-where-clause user-id)]})
                  db/jdbc-opts)]
      (resolve! tx user-id (assoc pending :event_id (:id event)) nil))))

(defn pending-exists-clause
  "`pending` for a listing row, as an **`EXISTS` subquery** correlated on the
  Recipe's id — true when something is waiting for him on that Recipe.

  **Not a second `LEFT JOIN`.** The listing already left-joins `recipe_history`
  under a `GROUP BY` to count the provenance split; adding another table to that
  multiplies the rows the aggregate sees, which would silently double the counts on
  the card. An `EXISTS` answers per row and joins nothing.

  Narrowed on the owner as well as the recipe id, and with `IS` rather than `=` on
  the `user_id` pair: both columns are nullable, dev's owner is the NULL one, and
  `NULL = NULL` is NULL rather than true — the same trap `db.event/recipe-still-there`
  documents, met a second time."
  [recipe-id-column]
  [[:exists {:select [[[:inline 1]]]
             :from [:recipe_proposals]
             :where [:and [:= :recipe_proposals.recipe_id recipe-id-column]
                     [:is :recipe_proposals.user_id :recipes.user_id]
                     [:is :recipe_proposals.resolved_at nil]]}]
   :pending])

(defn attach-to-events
  "Put the proposed text, and the Recipe's **current** text, on every `proposed`
  entry of an inbox page — and leave every other entry exactly as it was.

  Both texts, because reviewing a proposal means reading a diff, and the diff is
  against what the Recipe says now rather than against what it said when the
  proposal was filed. `base_version` beside the current `version` is what tells him
  the two are not the same thing.

  **`recipe_published` comes with them, and it is not decoration.** A machine may
  propose against a published Recipe — *its up to the human to approve or not* — so
  approving one puts an agent's wording into text he has already put his name to, in
  public, with no unpublish. His click is the only gate there is on that, which is
  exactly why the item has to be able to say what the click will do before he makes it.
  Same argument as `base_version` beside `recipe_version`, one door along.

  **It reads the Recipe's text straight from the table and must keep doing so.**
  Going through `GET /api/recipes/:id?detail=full` would bump `view_count`, which
  ranks the shelf — so working through a queue of proposals would quietly reorder
  his Cookbook, and reviewing what an agent wrote is not consuming a Recipe.
  `reading-the-inbox-moves-no-view-count-and-no-modified-at` pins that.

  **The text stays on the list even though the queue row no longer draws it**, and
  that was weighed rather than left. The client used to show the comparison under the
  row, which is where these six fields were read; it shows it in the version viewer
  now, so the list carries text that nothing renders until something is clicked. Both
  answers were honest — leave it, or ship a lean row and fetch on open the way the
  viewer fetches versions.

  **This paragraph got the size wrong by 2.8× and argued from something false, so both
  are corrected here and the decision is taken again on what is true.** What it said:
  his largest body is 2550 characters, a `proposed` entry carrying it weighs 5993
  bytes, and *the only route that serves a Recipe's text counts a consumption*.

  - **A real queue holds one.** Production, 2026-08-08: 36 Recipes, **1** pending
    proposal. The partial unique index makes one per Recipe the ceiling, so the ceiling
    on a whole queue is the number of Recipes.
  - **Seventeen kilobytes, not six.** His largest body is **7287** characters
    (Recipe 19), and **18 of the 36** are longer than the 2550 that was written down —
    2550 is Recipe 7, which was measured because it is the one with the pending
    proposal, not because it is the largest. Measured rather than extrapolated: that
    body seeded into dev with a machine proposal of the same order of length on it takes
    `/api/inbox` from 8562 to 25412 bytes, so the one entry weighs **16850** — 66% of
    the whole response — against **262** bytes for that same row without its proposal.
    The entry carries a body twice, `description` and `current_description`, so it
    scales at about 2× the body and an error in the body compounds.
  - **Half of what a lean row would fetch is already on a route that costs nothing.**
    `GET /recipes/:id/versions` serves the current row's all three fields —
    `db.recipe/list-versions` puts `(content-of current)` at the head of the list — and
    calls no `record-view!`; only `?detail=full` does. So *the only route that serves a
    Recipe's text counts a consumption* was false, and it was the clause carrying the
    argument's weight. What survives is the other half: **nothing serves a proposal's
    three fields**, so a lean row is still a new owner-only endpoint, its entry in
    `/api/describe`, its own 403/404, and a loading state in a viewer that opens on the
    click.
  - **And it would put the text in two places.** On the list there is one copy: an
    agent revising its proposal while he reads it lands with the next `fetch-inbox`
    and the open viewer follows, because the viewer looks the entry up in the queue
    rather than holding it. A fetched copy would need invalidating, which is what
    `forget-versions!` is and why it exists.
  - **One statement, one moment.** Both texts *and* both version numbers leave here
    together, so the comparison a reader sees and the warnings printed over it cannot
    be from two different instants. That is not an argument against a lean row, but it
    is a constraint on one: whoever builds it must serve all six fields and both
    versions in a **single** response. Assembling the Recipe's half from `/versions` and
    the proposal's half from somewhere else would put a skew inside the one screen where
    approving is decided, and the staleness warning is the thing that skew would lie
    about.

  **Still: leave it.** 17 kilobytes on the owner's own private page, at one pending
  proposal, does not buy a new route, a loading state and an invalidation. The trigger
  for revisiting is now a number rather than a feeling: the list is refetched after
  **every** write, so several pending proposals against long Recipes multiply directly —
  ten of them would be about 170 KB per refetch, and that is where this decision should
  be made again.

  One statement for the whole page rather than one per entry, which is
  `db.scope/attach`'s shape and the same reason: a queue of thirteen entries must
  not cost thirteen round trips."
  [ds user-id events]
  (let [ids (into #{} (comp (filter #(= "proposed" (:kind %))) (map :proposal_id)) events)]
    (if (empty? ids)
      events
      (let [by-id (into {}
                        (map (juxt :id identity))
                        (jdbc/execute! (db/get-conn ds)
                          (sql/format {:select [:recipe_proposals.id
                                                :recipe_proposals.base_version
                                                :recipe_proposals.title
                                                :recipe_proposals.useful_when
                                                :recipe_proposals.description
                                                :recipe_proposals.created_at
                                                :recipe_proposals.modified_at
                                                ;; The agent's account of the write,
                                                ;; and **this** is the read that
                                                ;; carries it to the page he decides
                                                ;; on: the item page draws the entry
                                                ;; out of the queue rather than
                                                ;; fetching the proposal again. NULL
                                                ;; on anything filed before 015, and
                                                ;; the surface draws no line for one.
                                                :recipe_proposals.reason
                                                :recipe_proposals.context
                                                [:recipes.version :recipe_version]
                                                [:recipes.published :recipe_published]
                                                [:recipes.title :current_title]
                                                [:recipes.useful_when :current_useful_when]
                                                [:recipes.description :current_description]]
                                       :from [:recipe_proposals]
                                       ;; An inner join, because **an unresolved
                                       ;; proposal always has its Recipe**:
                                       ;; `db.recipe/delete-recipe` resolves the pending
                                       ;; one and marks its entry seen in the same
                                       ;; transaction, so a `proposed` entry that is
                                       ;; still in the queue has a row to join to.
                                       ;; `deleting-a-recipe-closes-its-pending-proposal`
                                       ;; is what holds that.
                                       :join [:recipes
                                              [:= :recipes.id :recipe_proposals.recipe_id]]
                                       :where [:and [:in :recipe_proposals.id ids]
                                               (db/user-id-where-clause
                                                 :recipe_proposals.user_id user-id)]})
                          db/jdbc-opts))]
        (mapv (fn [event]
                (if-let [p (get by-id (:proposal_id event))]
                  (assoc event :proposal (dissoc p :id))
                  event))
              events)))))

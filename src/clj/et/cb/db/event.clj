(ns et.cb.db.event
  "**The owner's inbox**: one row per change **an agent** made to a Recipe, in the
  order the changes happened, unseen until he marks it so.

  What he asked for, in his words: *every recipe change (addition, modification)
  appears there, in order of a queue, that is, newer appended items go bottomwards
  … so i can go through the things topmost first (oldest unseen change first)*.

  **His own edits are not in it**, which is the later word and the one that wins:
  *no my own ui edits should not land in the inbox*. This is not a change log — it
  is the record of what the agents did to his shelf while he was not looking, which
  is also what makes working through it oldest-first worth doing. An inbox that
  filled up with his own saves would be a queue he had to empty to find the two
  rows he actually wanted.

  So **an event exists exactly when the write it records was stamped `source =
  'machine'`**, decided off the same fact the label itself is decided off — the
  token's `:machine?` claim, which `recipe-handler/human-write?` already reads.
  One fact, read once, so the queue and the version labels cannot come to disagree.
  There is deliberately no `source` column on the row: it would have one possible
  value (see migration 009).

  **An event is a record that something happened, which is why this is a table and
  not a query over `recipe_history`.** Migration 009 makes that argument in full;
  the short form is that a history row's `created_at` says when its version was
  *displaced*, so no column in the schema before 009 could answer 'when was v2
  written'. An event is written as the thing happens, in the same transaction as
  the thing — an event for a save that then rolled back would be worse than no
  inbox at all — and it is therefore also the honest home for that timestamp.

  **One event per version an agent writes, plus the two lifecycle facts.**
  `created` for v1, `modified` for each new version, `deleted` for the version a
  Recipe died on, and `proposed` for a rewrite waiting for approval (see
  `et.cb.db.proposal`). Which means the events line up with the version ladder and
  with nothing else: a save that changes nothing makes no event because it makes no
  version, a tags- or Scope-only save makes none because filing is not content, and
  publishing makes none because it writes no version and a machine cannot publish
  at all. `db.recipe/update-recipe`'s three branches are exactly that split, and
  the two quiet ones must stay quiet.

  One thing falls out of the `machine` rule rather than needing its own decision:
  **approving a proposal writes a `machine` version and still makes no event.** The
  approve path is the owner acting, and the proposal's own event is already the
  record — which it has just resolved.

  **Events outlive their Recipe.** `delete-recipe` takes the history and the Scope
  associations, which are parts of a Recipe, and leaves the events, which are
  records of what happened to it — otherwise an agent could create a Recipe and
  delete it again and the inbox, whose whole promise is that changes show up
  there, would record the create and then erase it. `recipe_title` is a snapshot
  precisely so such an event still reads as something.

  **The queue is `id` ascending**, never `created_at` — see 009: the stamp is
  second-resolution and the append order is the thing being served."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.cb.db :as db]))

(def kinds
  "The four things an agent can do to a Recipe, as migration 009's `CHECK` spells
  them. Named here so a caller writes a word this table accepts rather than
  finding out from a constraint violation, and so `inbox-handler` can tell the one
  kind that is not an acknowledgement — `proposed` — from the three that are."
  #{"created" "modified" "deleted" "proposed"})

(def ^:private queue-columns
  "What an event is, read back for the queue. `proposal_id` is in here from the
  start although nothing writes it until 011 — the column exists from 009 (see the
  migration for why), and the page it feeds has to be able to tell a proposal from
  an acknowledgement.

  There is no `source`: the table has no such column, because every event here is a
  machine's.

  `seen` is deliberately not selected either. Every row of this list is unseen, so
  the column would only restate the query's own `:where`, and a caller that read a
  0 there would be reading a constant."
  [:id :recipe_id :recipe_title :kind :version :proposal_id :created_at])

(def ^:private recipe-still-there
  "Whether the Recipe an event names is still on the shelf, as an `EXISTS`
  subquery — 1 or 0.

  **Not derivable by the reader, and it is the one thing an entry cannot say for
  itself.** A `deleted` entry obviously names a Recipe that is gone; what is easy
  to miss is that the `created` and `modified` entries *above* it name the same
  Recipe and are just as dead, and one of them can still be sitting unseen in the
  queue after the `deleted` one has been acknowledged. A client cannot work this
  out either: the shelf it holds may be narrowed by a search, so 'not in the
  listing' does not mean 'not there'.

  An `EXISTS` and not a `LEFT JOIN`, the same call the listing's `pending` makes:
  this query has no `GROUP BY` today, but a join would put one row per match in
  front of anybody who later adds one.

  Narrowed on the owner as well as the id. Strictly the id alone would do —
  AUTOINCREMENT never reuses one, so a recipe id in this table can never come to
  name somebody else's row — but every other read in this app answers ownership
  from the row that owns it, and a reader should not have to reconstruct that
  argument to trust the query.

  **The owner clause is `IS` and not `=`, and that is not a stylistic choice.**
  Both `user_id` columns are nullable because dev's owner has no `users` row, and
  in SQL `NULL = NULL` is NULL rather than true — so `=` here answered *false for
  every event the dev owner has*, which is every event on his own machine. It said
  each Recipe was gone and quietly took the link off every row of the page. This is
  `db/user-id-where-clause`'s rule met one step further along: that function exists
  because a nil owner needs `IS NULL` instead of `= NULL`, and a column-to-column
  comparison needs SQLite's `IS` for exactly the same reason. It cannot be routed
  through that function, which compares a column against a *value*."
  [[:exists {:select [[[:inline 1]]]
             :from [:recipes]
             :where [:and [:= :recipes.id :recipe_events.recipe_id]
                     [:is :recipes.user_id :recipe_events.user_id]]}]
   :recipe_exists])

(defn list-unseen
  "The owner's unseen events, **oldest first** — the queue as he asked for it: he
  works down from the top and the newest arrivals are at the bottom.

  Ordered by `id` and never by `created_at`. The stamp is second-resolution, so two
  events written in one second — a create and the proposal an agent files a moment
  later — would be a tie the database could break either way, and the append order
  is the one fact being served. Migration 009 makes the argument in full.

  Every entry also carries `recipe_exists` — see `recipe-still-there`, which is the
  question a page cannot answer for itself and has to be told.

  Narrowed through `db/user-id-where-clause`, like every other read in this app:
  dev's owner has no `users` row, and a `= user-id` would answer nothing for him."
  [ds user-id]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select (conj queue-columns recipe-still-there)
                 :from [:recipe_events]
                 :where [:and [:= :seen [:inline 0]] (db/user-id-where-clause user-id)]
                 :order-by [[:id :asc]]})
    db/jdbc-opts))

(defn get-event
  "One event of this owner's, or nil. Both halves in one clause, so no caller can
  fetch a row by id and check the owner afterwards."
  [ds user-id id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select (conj queue-columns :seen)
                 :from [:recipe_events]
                 :where [:and [:= :id id] (db/user-id-where-clause user-id)]})
    db/jdbc-opts))

(defn seen!
  "Mark one event seen, unguarded — the bare write, in the caller's transaction.

  Two callers: `mark-seen!` below, which decides whether this event is one that may
  be acknowledged at all, and every path that resolves a proposal, which marks that
  proposal's own event seen in the same transaction as the resolution. That second
  one is what holds the invariant a `proposed` event lives by: **it is unseen
  exactly while its proposal is unresolved.** Hence a primitive rather than only the
  guarded version — a resolution must not have to pass a guard written for a
  request."
  [tx user-id id]
  (jdbc/execute-one! tx
    (sql/format {:update :recipe_events
                 :set {:seen [:inline 1]}
                 :where [:and [:= :id id] (db/user-id-where-clause user-id)]})))

(defn rebase-proposal!
  "Move a `proposed` entry onto the base its proposal now answers — its `version`, and
  the `recipe_title` snapshot with it — leaving everything else about the entry alone,
  **including its id and its `created_at`, which is its place in the queue**.

  An agent that revises its proposal three times must be one thing for the owner to
  answer, not three, and it must not push itself to the bottom of a queue he is
  working through oldest-first. So an overwrite updates in place; what moves is what
  the entry says about *now*, because the text being proposed was written against the
  Recipe as it reads now, and an entry claiming an older base would overstate how stale
  it is.

  **The title moves for that same reason and not as a courtesy.** It is a snapshot of
  the Recipe's title (009), the Recipe may have been renamed since this entry was
  written, and an entry rebased onto the Recipe as it reads now must not go on naming
  it as it read then. What it must never become is the *proposal's* title —
  `db.proposal/recipe-title` is where that is made impossible.

  Keyed by the proposal rather than by the event id, because that is what the caller
  in `db.proposal/propose!` holds: it found a pending proposal, not an entry."
  [tx user-id proposal-id version recipe-title]
  (jdbc/execute-one! tx
    (sql/format {:update :recipe_events
                 :set {:version version :recipe_title recipe-title}
                 :where [:and [:= :proposal_id proposal-id]
                         [:= :kind [:inline "proposed"]]
                         (db/user-id-where-clause user-id)]})))

(def no-such-event
  "`mark-seen!`'s answer for 'there is no such event of yours'. A named value at
  both ends, the shape `db.scope/no-such-scope` already uses: the caller compares
  against this var, so the two namespaces cannot come to spell the same refusal
  differently."
  ::no-such-event)

(def proposal-needs-resolving
  "`mark-seen!`'s answer for 'this event is a proposal'. See `no-such-event`.

  A proposal is not something to acknowledge — it is something to approve or
  dismiss — and letting one be marked seen would strand it: the proposal would go
  on blocking the agent that filed it with nothing left in the inbox to resolve it
  through. So the refusal is a category error being named, not a permission being
  withheld."
  ::proposal-needs-resolving)

(defn mark-seen!
  "Acknowledge one event: the event as it now reads, `no-such-event`, or
  `proposal-needs-resolving`.

  **Both refusals are decided inside one transaction off one read**, the shape
  `db.scope/update-scope` argues for: two reads mean two moments, and between them
  the row can change — a caller that established the kind and then wrote would be
  able to acknowledge a `proposed` event that arrived in between."
  [ds user-id id]
  (jdbc/with-transaction [tx (db/get-conn ds)]
    (if-let [event (get-event tx user-id id)]
      (if (= "proposed" (:kind event))
        proposal-needs-resolving
        (do (seen! tx user-id id)
            (tel/log! {:level :info :data {:id id :user-id user-id :kind (:kind event)}}
                      "Inbox event marked seen")
            (assoc event :seen 1)))
      no-such-event)))

(defn record!
  "Append one event, **in the caller's transaction** — the argument is a `tx` and
  not a datasource on purpose, because an event that outlived a rolled-back write
  would be the inbox reporting something that never happened.

  `kind` is one of `kinds`. `recipe` is the row the event is about, and the id, the
  title snapshot and the version are taken off it: for a `modified` event that is
  the row as it now reads, so the version is the *new* one, and for a `deleted`
  event it is the row as it died. `overrides` replaces any of those for the one
  caller that knows better — a `proposed` event's `version` is the base the
  proposal was written against, and it carries the `proposal_id` besides.

  **Whether to call this at all is the caller's decision and not this function's**,
  because the caller is the only one holding the fact it turns on: an event exists
  exactly when the write is an agent's. `db.recipe/machine-write?` is that fact,
  named once.

  Returns the new event's `id`, which is what the proposal path holds onto: an
  overwrite has to find its own event again to leave it where it is in the queue."
  ([tx user-id kind recipe] (record! tx user-id kind recipe nil))
  ([tx user-id kind recipe overrides]
   (jdbc/execute-one! tx
     (sql/format {:insert-into :recipe_events
                  :values [(merge {:user_id user-id
                                   :recipe_id (:id recipe)
                                   :recipe_title (:title recipe)
                                   :kind kind
                                   :version (:version recipe)}
                                  overrides)]
                  :returning [:id]})
     db/jdbc-opts)))

(ns et.cb.server.inbox-handler
  "The inbox API: the owner's queue of what his agents did, and the three ways off
  it.

  **Three, and which one applies is not a preference.** A `created`, `modified` or
  `deleted` entry is a notification, so it is *acknowledged* — `POST /:id/seen`. A
  `proposed` entry is a question, so it is *answered* — `POST /:id/approve` or
  `POST /:id/dismiss` — and `seen` refuses it outright, because acknowledging a
  question would strand the agent waiting on it. That is the whole shape of this
  namespace.

  **Owner-only, asked for here rather than inherited.** These routes are siblings
  of `/api/recipes` and sit outside both recipe guards, exactly like `/api/scopes`
  — `wrap-recipe-write-guard` and `wrap-machine-recipe-rules` are about recipe ids
  and the publish latch, and neither has an answer for an event id. So every
  handler asks `common/owner-caller?` for itself, which is the one function the
  owner-only routes of this app share: a machine token is refused and so is a
  caller with no credentials. Do not move these inside the `/recipes` context to
  'get a guard for free'; the guard there does not answer this question, and
  assuming a guard elsewhere covers you is exactly what a URL-encoding bug
  exploited in this codebase before.

  **A machine is refused deliberately and not incidentally.** The queue is the
  owner reviewing what his agents wrote; an agent reading it would learn what he
  has not looked at yet, and an agent *marking* it would empty the review he has
  not done. The same argument, one step further, is why approving a proposal is
  his alone — *only a human can approve that then afterwards on that new page*.

  There is **no unseen-count endpoint**. The count the top bar shows is the length
  of the list this page has already fetched, and a second endpoint answering it
  would be a number that could disagree with the list beside it.

  Every docstring below is `METHOD /path — explanation`, which is what
  `server/route-doc-re` matches, and this namespace is in
  `server/describe-namespaces`. A handler documented any other way, or a namespace
  missing from that list, is absent from `/api/describe` entirely — and an agent
  reading that catalogue is the primary caller of this API."
  (:require [next.jdbc :as jdbc]
            [et.cb.db :as db]
            [et.cb.server.common :as common]
            [et.cb.db.event :as db.event]
            [et.cb.db.proposal :as db.proposal]
            [et.cb.db.recipe :as db.recipe]))

(def ^:private forbidden
  "One refusal, so the routes cannot come to word it differently — and it says
  nothing about whether there is anything in the queue."
  {:status 403 :body {:error "The inbox is the owner's: a machine caller cannot read or resolve it"}})

(defn list-inbox-handler
  "GET /api/inbox — the owner's unseen Recipe changes, **oldest first**.

  **Every entry is an agent's work.** This is the record of what the machine
  callers did to his shelf, not a change log: his own creates, saves, deletes and
  publishes make no entry at all, which is what he asked for in as many words. So
  there is no `source` on an entry — every one of them would say `machine`.

  One entry per change, appended as it happened, and it is a queue rather than a
  feed: the top is the oldest thing he has not looked at, new arrivals go to the
  bottom, and an entry leaves the list when it is resolved. Each carries `id` (the
  event's own, which is what the other routes here take), `recipe_id`,
  `recipe_title` as it read at the time, `kind`, `version`, `created_at`,
  `proposal_id` — the join key behind the `proposal` described below, and null on
  the three kinds that are notifications rather than questions — and `scopes`, the
  Scopes the Recipe is filed under, `{id, title, description}` each, in title order
  and empty for a Recipe filed under none.

  **`kind` is one of four.** `created` — an agent wrote a Recipe, at version 1.
  `modified` — an agent's save changed its content, and `version` is the **new**
  version. `deleted` — an agent deleted it, and `version` is the one it died on.
  `proposed` — an agent has a rewrite waiting for approval (see POST
  /api/inbox/:id/approve); `proposal_id` names it and `version` is the version it
  was written against.

  Nothing else makes an entry. A machine save that changes no content makes none,
  for the same reason it makes no version; one that changes only `tags` or
  `scope_ids` makes none, because filing is not content; and publishing makes none,
  because it writes no version and a machine cannot publish at all.

  `recipe_title` is a snapshot rather than a join, and `recipe_id` may name a
  Recipe that no longer exists: an event is the record that something happened, so
  a `deleted` entry outlives the Recipe it is about, and so do the entries before
  it. There is nothing to fetch for such an entry, which is why the title is on it.

  **`scopes` is the other way round on purpose: it is current, not a snapshot.** The
  title says what the Recipe was called when the change happened; the Scopes say
  where it is filed now, read live at request time. So refiling a Recipe changes the
  badges on entries that are already in the queue and does not change their titles,
  and that is the pairing triage wants — what an entry is *about*, and what area it
  belongs to *now*. For a Recipe that is gone the associations went with it, so
  `scopes` is empty; it is empty for an unfiled Recipe too, and the two are not worth
  telling apart.

  **`recipe_exists` says which case an entry is in**, 1 or 0. It is not derivable
  by the caller and it is not the same question as `kind`: after an agent creates a
  Recipe and deletes it again, the `created` entry above the `deleted` one names a
  Recipe that is equally gone, and it can still be unseen after the `deleted` one
  has been acknowledged. So this is the flag to read before following an entry to
  GET /api/recipes/:id or /versions — those answer 404 for a Recipe that is gone,
  and this is how to know that without asking.

  **Ordered by the event id and never by `created_at`**: the stamp is
  second-resolution and two entries in one second is the normal case, so the
  append order is served from the column that *is* the append order.

  **A `proposed` entry carries a `proposal`**, and it carries *both* texts: the three
  fields the agent proposes, and the three the Recipe says now
  (`current_title`, `current_useful_when`, `current_description`), plus
  `base_version` and the Recipe's `recipe_version`. Reviewing a proposal means
  reading a diff, and the diff that matters is against what the Recipe says *now* —
  not against what it said when the proposal was filed, which may be two of his
  saves ago. When `base_version` is behind `recipe_version`, approving replaces his
  newer text with the agent's, and both numbers are here so a client can say so
  before the click rather than leaving it to be discovered after.

  **`recipe_published` is on the proposal for the same reason**, 1 or 0. An agent may
  propose against a published Recipe, so approving one writes an agent's wording into
  text that is already public and that he has put his name to — and there is no
  unpublish. The client says so on the item, in words, before the button. What a
  *visitor* sees meanwhile is unchanged and stays unchanged: the last approved version,
  never the proposal (see GET /api/recipes/:id).

  **A `proposed` entry always has its Recipe**, unlike the other three kinds: deleting
  a Recipe resolves its pending proposal and takes that entry out of the queue in one
  transaction (see DELETE /api/recipes/:id), so `recipe_exists` is 1 on every entry
  that carries a `proposal` and the three `current_` fields are never null.

  **Reading this list moves no `view_count`.** The current text is read straight from
  the table, not through `GET /api/recipes/:id?detail=full` — that endpoint counts a
  consumption, and consumption ranks the shelf, so working through a queue of
  proposals would quietly reorder his Cookbook. Reviewing what an agent wrote is not
  using a Recipe.

  The owner's alone: 403 for a machine token and 403 for a caller with no
  credentials. There is no listing of *seen* events — marking one seen is what
  takes it out of this list, and this list is the whole feature."
  [req]
  (if (common/owner-caller? req)
    (let [ds (common/ensure-ds)
          user-id (common/get-user-id req)]
      {:status 200 :body (db.proposal/attach-to-events ds user-id
                                                       (db.event/list-unseen ds user-id))})
    forbidden))

(defn mark-seen-handler
  "POST /api/inbox/:id/seen — acknowledge one entry, by its **event** id, and it
  disappears from GET /api/inbox.

  That is all this does: nothing about the Recipe changes, and there is no way
  back — a seen entry is not listed anywhere, because the queue is what has not
  been looked at.

  **Acknowledging an entry that is already seen is an idempotent 200**, not a 404.
  `seen` is a latch and nothing takes it off, so this follows the precedent the
  publish latch sets: the first acknowledgement is the fact being recorded, and a
  second one changes nothing and says so. The 404 is about an id that names no entry
  of *yours* — being out of the queue is not the same as not existing, and a client
  that lost the response to its first call would otherwise be told it had imagined
  the entry.

  **It refuses a `proposed` entry with a 400.** A proposal is not something to
  acknowledge, it is something to approve or dismiss (POST /api/inbox/:id/approve
  and /dismiss), and marking one seen would strand it: the proposal would go on
  blocking the agent that filed it with nothing left in the queue to resolve it
  through. So a `proposed` entry has exactly two exits and this is not one of them.

  **The entry it answers with is the queue row without `scopes`, and with `seen`.**
  The two shapes have always differed by `seen` — the listing leaves it off because
  every row there is unseen, while here it is the fact being reported (see
  `db.event/queue-columns`) — and they now differ by `scopes` as well: GET
  /api/inbox attaches the Recipe's Scopes to every row it serves and this route does
  not, so there is **no `scopes` key at all** in this body rather than an empty one.
  Read it that way: absent here means *not fetched*, and it is not the empty vector
  the listing gives an unfiled or a deleted Recipe. Absent and empty are load-bearing
  elsewhere in this codebase — `db.scope/attach` spends a paragraph on the difference,
  because on the shelf it is the whole privacy boundary — so a third state that is
  neither has to be written down where a caller meets it.

  It is left off rather than attached, and that is a decision and not an oversight:
  the page refetches the list after acknowledging rather than splicing this body into
  the row (the server decides what is in the queue), so a second grouping query here
  would be a round trip whose result nothing reads. A caller that wants the filing of
  the Recipe an acknowledged entry named asks GET /api/recipes/:id for it, which is
  the live answer anyway.

  200 with the acknowledged entry, 400 for a `proposed` one, 404 when the id
  matches no entry of yours, 403 for a machine token or a caller with no
  credentials."
  [req]
  (if (common/owner-caller? req)
    (let [id (common/path-id req)
          ;; A path naming no id at all never reaches the db: `nil` there would be
          ;; `WHERE id IS NULL`, which matches nothing and would answer 404 anyway
          ;; — but by way of a transaction opened for a request already malformed.
          result (when id (db.event/mark-seen! (common/ensure-ds)
                                               (common/get-user-id req) id))]
      (condp = result
        nil
        {:status 404 :body {:error "Inbox entry not found"}}

        db.event/no-such-event
        {:status 404 :body {:error "Inbox entry not found"}}

        db.event/proposal-needs-resolving
        {:status 400 :body {:error "This is a proposal: approve or dismiss it instead"}}

        {:status 200 :body result}))
    forbidden))

(defn- resolving
  "The three things the approve and dismiss handlers do identically: refuse anybody
  but the owner, find the proposal an entry names, and refuse one that has already
  been answered.

  Written once because the two routes must not come to disagree about any of them —
  in particular about the **409 for an already-resolved proposal**, which is the
  answer that keeps a double click from looking like a second decision. `f` gets the
  proposal and returns the response."
  [req f]
  (if-not (common/owner-caller? req)
    forbidden
    (let [id (common/path-id req)
          proposal (when id (db.proposal/by-event (common/ensure-ds)
                                                 (common/get-user-id req) id))]
      (cond
        (nil? proposal)
        {:status 404 :body {:error "No proposal is waiting on that inbox entry"}}

        (some? (:resolved_at proposal))
        {:status 409 :body {:error "That proposal has already been resolved"
                            :resolution (:resolution proposal)}}

        :else
        (f proposal)))))

(defn approve-proposal-handler
  "POST /api/inbox/:id/approve — accept an agent's proposed rewrite, by the **event**
  id of the inbox entry showing it.

  One transaction: the Recipe's current version is archived, the proposal's three
  fields become the new version, the proposal is resolved `approved`, and the entry
  leaves the queue. 200 with the Recipe as it now reads.

  **The owner's alone**, like everything under /api/inbox — *only a human can approve
  that then afterwards on that new page*. A machine approving its own proposal would
  be the entire mechanism undone, so a machine token gets 403 here as it does on the
  listing.

  Two things worth knowing before calling it:

  - **The new version is labelled `machine`.** The agent wrote the text; approving is
    letting it in, not authoring it. `has_human_edit` is therefore untouched too, so
    the Recipe still needs approval for the *next* agent edit — which is intended.
  - **It does not check what the proposal was written against.** If you saved in
    between, `base_version` is behind the Recipe's `version` and approving replaces
    your newer text with the agent's. That is deliberate: the inbox shows the proposal
    diffed against the Recipe as it reads *now* and says in words when the two have
    diverged, so this is a decision you make with your eyes open rather than one the
    API refuses on your behalf.
  **Deleting the Recipe is what closes a proposal nobody answered**, and it is not
  this route's business: DELETE /api/recipes/:id resolves the pending proposal and
  takes its entry out of the queue in the same transaction. So there is no answer here
  for 'the Recipe is gone' — such an entry is not in the queue to be clicked, and a
  call naming one after the fact meets the 409, because the delete already resolved it.

  409 when the proposal has already been approved, dismissed, or closed by a delete;
  404 when the entry names no proposal of yours; 403 for a machine token or an
  anonymous caller."
  [req]
  (resolving req
    (fn [proposal]
      {:status 200
       :body (db.recipe/approve-proposal! (common/ensure-ds)
                                          (common/get-user-id req) proposal)})))

(defn dismiss-proposal-handler
  "POST /api/inbox/:id/dismiss — decline an agent's proposed rewrite, by the **event**
  id of the inbox entry showing it.

  The proposal is resolved `dismissed` and the entry leaves the queue. **The Recipe is
  not touched at all** — no version, no history row, nothing — which is the whole
  difference from approving.

  The agent's text is not kept anywhere a reader can get at it afterwards: the row
  survives as the record that something was proposed and declined, and nothing serves
  its content again. So this is the one route here worth a confirmation in a client,
  and the UI has one.

  The owner's alone, for the reason approving is. 409 when the proposal has already
  been resolved, 404 when the entry names no proposal of yours, 403 for a machine
  token or an anonymous caller."
  [req]
  (resolving req
    (fn [proposal]
      (let [ds (common/ensure-ds)]
        (jdbc/with-transaction [tx (db/get-conn ds)]
          (db.proposal/resolve! tx (common/get-user-id req) proposal "dismissed"))
        {:status 200 :body {:success true}}))))

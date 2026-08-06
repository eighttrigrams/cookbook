(ns et.cb.server.inbox-handler
  "The inbox API: the owner's queue of Recipe changes, and the two ways off it.

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
  (:require [et.cb.server.common :as common]
            [et.cb.db.event :as db.event]))

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
  `recipe_title` as it read at the time, `kind`, `version` and `created_at`.

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

  The owner's alone: 403 for a machine token and 403 for a caller with no
  credentials. There is no listing of *seen* events — marking one seen is what
  takes it out of this list, and this list is the whole feature."
  [req]
  (if (common/owner-caller? req)
    {:status 200 :body (db.event/list-unseen (common/ensure-ds) (common/get-user-id req))}
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

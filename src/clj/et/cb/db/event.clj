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
            [et.cb.db :as db]))

(def kinds
  "The four things an agent can do to a Recipe, as migration 009's `CHECK` spells
  them. Named here so a caller writes a word this table accepts rather than
  finding out from a constraint violation, and so `inbox-handler` can tell the one
  kind that is not an acknowledgement — `proposed` — from the three that are."
  #{"created" "modified" "deleted" "proposed"})

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

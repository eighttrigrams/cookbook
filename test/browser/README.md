# Browser checks — the Inbox, a proposal, and the viewer it opens on

There is no cljs test runner in this project, so the properties that only exist in a
browser are checked from a Playwright session by an agent. This directory is what
makes such a run **repeatable and re-breakable**, which is the whole reason it is in
the repository: the previous pass wrote a check that could tell a real bug from a
fixed one, published its two columns in a report, and shipped only the report. A check
that exists as a table is a check nobody can re-run.

    seed.py        the queue a run needs, as Recipes named CHECK-…
    checks.js      the suite, one async arrow function, pasted into `evaluate`
    focus-probe.js one line, evaluated between *real* Tab presses
    cleanup.py     takes every CHECK- Recipe back out again

## Running it

1. `cd cookbook && make start` — the dev app on the port `config.edn` names.
2. `python3 test/browser/seed.py` — seven proposals: four plain and three that carry
   a warning. It prints what it made.
3. Open the app and **reload the page**. Not a hot-swapped one: shadow's reload
   replaces the code under a react tree that is already mounted, and a check about
   what a fresh mount does has to run against a fresh mount.
4. Evaluate the contents of `checks.js` in the page. It returns
   `{passed, of, failed, results, notes}`.
5. The focus trap is the one property real keystrokes are needed for, because a
   synthetic `KeyboardEvent` does not move focus and nothing inside the page can send
   a real one. Drive it from the session instead: click a `proposed` row's title, then
   for each of six presses of `Tab`, evaluate `focus-probe.js` and read
   `insideOverlay`. Check 11 makes the same claim structurally, in one evaluate, by
   counting what is focusable in the document at all — the two together are the
   census and the keystroke.
6. `python3 test/browser/cleanup.py` — sqlite and not `DELETE /api/recipes/:id`,
   because the API's delete is right to leave a `deleted` entry in his queue and a
   cleanup must not add one.

The suite assumes the dev queue also holds at least one `modified` entry, which check
5 opens to compare the two readings' markup. `seed.py` does not create one: a
`modified` entry needs a Recipe with a history, and the dev database has several.

A check's **number is its name and not its position**. 1–9 keep the numbers they had
when the mutation table was first published, so the two can be compared; 10, 11 and 12
were added afterwards, and 11 sits in the middle of the file because it is about a
viewer that is open and that is where one is.


## The two house rules every wait here follows

- **Wait on the visible consequence, never on the network.** `networkidle` answers
  "the data arrived", not "reagent has re-rendered". Every `until` in `checks.js`
  polls the DOM for the thing the click was supposed to produce — an overlay, a row
  gone, a modal — and never for a request.
- **Break it and watch it go red.** An assertion nobody has seen fail is not
  evidence. The mutations below are how this suite was shown to be able to fail, and
  re-running them is how the next change to it is shown to be too.

## The mutations

Each is applied to the source, hot-rebuilt, and run against a freshly seeded queue on
a reloaded page. The numbers are in the report of the session that ran them, not here:
a table in a document goes stale silently, and this list is the part that does not.

| | the edit |
|---|---|
| **M1** | render `[proposal-review entry]` under the row again — as a **sibling** of `.inbox-row` inside a wrapper, which is the shape `4c9ebfa:views/inbox.cljs:266` actually had |
| **M2** | delete the `(when (= event-id (:diffing-proposal @*app-state)) (stop-diff))` from `state/resolve-proposal`'s `done` |
| **M3** | drop `"proposed"` from `openable?`'s set |
| **M4** | make the viewer's Dismiss call `state/dismiss-proposal` directly instead of `start-dismissing-proposal` |
| **M5** | give `proposal-reading` its own `[:div.diff-overlay …]` instead of `shell` |
| **M6** | put the removed `fetch-inbox` guard back (it cannot fire; see the commit that took it out) |
| **M7** | drop `published?`/`stale?` from the row's Approve `:disabled` |
| **M8** | remove `inert-behind!`'s call from `surface-ref` |

M1 is the one to be careful with. Nesting the pane *inside* `.inbox-row` produces row
heights in the thousands and reddens a height check trivially; that shape never
shipped. As a sibling, `.inbox-row` stays one line and the height check has to measure
the **entry** — `.inbox-list`'s own children — to see anything at all. Check 2 does.

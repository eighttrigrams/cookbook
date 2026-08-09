# Browser checks

There is no cljs test runner in this project, so the properties that only exist in a
browser are checked from a Playwright session by an agent. This directory is what
makes such a run **repeatable and re-breakable**, which is the whole reason it is in
the repository: the previous pass wrote a check that could tell a real bug from a
fixed one, published its two columns in a report, and shipped only the report. A check
that exists as a table is a check nobody can re-run.

Two suites live here, each with its own numbering and its own mutation table: the
**Inbox**, a proposal and the viewer it opens on — everything down to the end of *The
mutations* — and **a Recipe's own page and its address**, at the bottom. They are
separate files because a check's number is its name, and numbers can only stay put in
a file nothing unrelated is appended to.

    seed.py        the queue a run needs, as Recipes named CHECK-…
    checks.js      the suite, one async arrow function, pasted into `evaluate`
    focus-probe.js one line, evaluated between *real* Tab presses
    cleanup.py     takes every CHECK- Recipe back out again

    recipe-page-checks.js   a second suite: a Recipe's own page, and its address
    provenance-seed.py      the one Recipe that suite's fourth phase cannot find

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


## `recipe-page-checks.js` — a Recipe's own page, and its address

A **second suite**, in its own file rather than appended to `checks.js`. That one is
the Inbox's, and a check's number there is its name and not its position — adding
unrelated checks to it would break exactly that promise. This one has its own numbers
and its own subject.

Three of its four phases need no seed and write nothing. What they read is one Recipe
of the dev database, named at the top of the file:

    const SUBJECT = 'Sourdough starter';

which has to be **published** and has to have a **body**: the body is what check 1
compares across the card and the page, and the latch is what makes check 4a the
visitor's case it claims to be. If that Recipe is gone, point the constant at another
one that is both. The only trace a run leaves is the `view_count` those reads move,
which is what reading a Recipe *is*.

### Four phases, and why it is not one evaluate

    (<contents of the file>).shelf()       — signed in, standing on /
    (<contents of the file>).coldLoad()    — after loading /recipe/<id> fresh
    (<contents of the file>).signedOut()   — signed in, standing on /; it signs itself out
    (<contents of the file>).provenance()  — the provenance view; needs provenance-seed.py

`coldLoad` cannot share a context with the others: the load it is about replaces the
JS context, which is the whole point of it. Back and Forward are the opposite case and
stay inside `shelf()` — nothing here ever leaves the document, every move is a
`pushState`, so `history.back()` fires a `popstate` in the same context and can be
waited on like any other consequence.

`shelf()` refuses to run from anywhere but the shelf, and says where it found itself
instead. `:recipes` is in the atom on every page, so a suite that only checked for the
Recipe would half-run from a Recipe page and produce two false reds.

    1   the Page button opens the Recipe at its own address, and the body is the card's
    6   the page wears the same header facts as the card
    3a  Back returns to the shelf, and the bar says so
    3b  Forward returns to the Recipe, at the same address
    5   a top-bar button leaves the page and puts / back in the bar
    2   a cold load of the address lands on the Recipe, not on a 404   (coldLoad)
    4a  signed out, a published Recipe still has a page                (signedOut)
    4b  an address that names no readable Recipe says so, and offers a way back
    7   the toggle swaps the rendered body for the source, and back    (provenance)
    8   the numbers run 1..n over the body as it is stored
    9   each line is tinted with its own caution, not its neighbour's
    10  a line between the ends is a third colour, not rounded to one
    11  the legend on the page is the string the API sent
    12  no caution in the response, no button — even signed in

6 is out of sequence for the reason 11 is in `checks.js`: it is about a page that is
*open*, and that is where one is. It was written after the fact — the first version of
the page showed five of the card's six header facts, because the two version counts are
a **listing** aggregate that `GET /api/recipes/:id` does not carry, and `source-split`
reads a count it was not sent as a fact it has not been told. Nothing failed. Comparing
the two surfaces is the only assertion that could have caught it, because each of them
on its own looked complete.

### What this suite cannot assert, and where it is asserted instead

**A genuine anonymous caller is unreachable from the dev browser.** Dev runs with
`:dangerously-skip-logins? true`, so every request is served in the owner's audience
whatever the client sends — there is no token to withhold. So `signedOut()` drives
`state/logout`, the fn the Sign out button calls, and what it asserts is the *client*
half: that a Recipe page is not owner-only, and that a 404 is a sentence rather than a
spinner. Which Recipes a visitor is refused is the server's half, and it is asserted
where it can be — `publish_latch_integration_test.clj`, *a visitor asking for the draft
by id gets the same 404 as for an id that never existed*.

4b therefore uses an id nothing was ever written under. That is the same 404 from the
same handler an unpublished Recipe gives a visitor; the server answers both identically
on purpose, and the page deliberately does not try to tell them apart.

Check 12 is the same limitation met a second time, and it is answered the same way. A
visitor is served no `caution` key at all, so the provenance button must not be there
— but dev cannot produce a visitor to prove it with. What 12 does instead is make the
exact condition a visitor's response creates and leave the session alone: it removes
`:caution` from the cached row and asserts the button goes with it *while still signed
in*. That is the client rule the page actually has ("the button exists when the answer
does"), and it is the assertion a button keyed off `logged-in?` fails. The server half
is `caution_integration_test.clj`, *a visitor is served no split*.

### The provenance phase's fixture

`provenance()` is the one phase here that cannot read what it needs out of the dev
database, and `provenance-seed.py` says at length why. Short version: checks 8, 9 and
10 need one body carrying a `1.00` line, a `0.00` line, a line strictly between them
and a trailing empty line, and nothing anybody has been keeping is guaranteed to have
all four. The seed builds that body through the app's own rules — the agent writes v1,
the owner rewrites the middle in v2, and the agent's v3 goes in as a **proposal the
owner approves**, which is the only way an agent can write a third version of a Recipe
the owner has touched.

    python3 test/browser/provenance-seed.py    # prints the id and the ranges
    …run provenance() …
    python3 test/browser/cleanup.py            # CHECK-PROV goes with the rest

The trailing newline in that body is load-bearing rather than untidy. The API's ranges
keep a trailing empty line and `clojure.string/split-lines` throws it away, so a view
built on `split-lines` draws one row fewer than the answer it is tinting — at the end,
silently, on the most ordinary body there is. The fixture is 8 lines to the API and 7
to `split-lines`, which is what gives check 8 something to fail against, and M9 below
is that failure on purpose.

### The mutations

| | the edit |
|---|---|
| **M1** | render `(:useful_when recipe)` where `views/recipe/found` renders the description — the page shows the wrong field |
| **M2** | delete `(GET "/recipe/*" [] serve-index)` from `server.clj` and restart |
| **M2′** | drop the `(sync-from-url!)` call from `state/fetch-auth-required` — the server route stands and the boot never reads the bar |
| **M3** | delete the `popstate` listener from `core.cljs/init` |
| **M4** | put `page-body`'s gate back to `(if logged-in? page :shelf)` |
| **M5** | call `api/fetch-json`'s 3-arity in `fetch-recipe-page!` — no error handler |
| **M6** | make `state/toggle-page` call `show-page!` instead of `go-to-page`, so the top bar bypasses the one writer of the bar |
| **M7** | render `[found recipe …]` instead of `[found (with-provenance recipe recipes) …]` |
| **M8** | render `[source-view …]` as a `when` *above* the `cond` in `views/recipe/found`, so the rendered body stays on screen with it |
| **M9** | `clojure.string/split-lines` in `provenance/split-lines`, instead of `(str/split … #"\n" -1)` |
| **M10** | drop the `inc` from `line-cautions` — `#(get by-line %)` — so every line wears its neighbour's number |
| **M11** | threshold the tint in `source-line`: `(if (< caution 0.5) 0 100)` |
| **M12** | write the legend into the cljs — `[:div.provenance-legend "1.00 means human, 0.00 means agentic"]` — instead of rendering the API's |
| **M13** | key the button off the session: `offered? (and logged-in? (not blank?))` |

M2 is the one that reddens hardest and is worth doing at least once: with the route
gone there is no app on the page at all — `/recipe/1` is the JSON 404 — so `coldLoad()`
has nowhere to run. That is the shape of the bug a pushState-only implementation has,
and everything `shelf()` asserts still passes while it is there.

M9 and M10 are the pair worth understanding, because both are one character and
neither looks like anything. M9 draws seven rows for an eight-line answer and nothing
else notices — checks 7, 10, 11 and 12 all stay green. M10 shifts every tint onto the
line above and stays green through 8 and 10, because the numbers are still 1..n and
there are still three distinct colours; only 9, which compares the screen against the
ranges the API sent, can see it. M11 reddens 9 *and* 10, and M13's evidence is worth
reading once: with the key gone the button is still there **and the source view still
draws**, every row untinted, which is precisely the control-that-does-nothing the
property gate exists to prevent.

M2′ is the same check's other half and needs no restart, which makes it the cheap one
to re-run: the index is served, the app boots, and it puts the shelf up under an
address naming a Recipe.

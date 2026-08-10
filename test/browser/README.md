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

    recipe-page-checks.js   a second suite: a Recipe's own page, its address and
                            its two modes
    provenance-seed.py      the one Recipe two of that suite's phases cannot find

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
viewer that is open and that is where one is. **13 and 14** came with the versions
view joining the shell — the ✕ becoming a back button in the top bar's left slot — and
they sit beside 11 and 4 for its reason: both are about a viewer that is *up*.

14 replaced a bare `close the viewer` step. Closing was something this suite did in
order to get to the next check; now that where you land is a claim, it is a check, and
the step it grew out of is still doing its old job of moving the run along.


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
| **M9** | drop `.top-bar` from `inert-behind!`'s skip test, so the bar goes inert under the viewer again |
| **M10** | render `[:button.diff-close …]` back into `shell`'s header |
| **M11** | make `views/diff/back-to-origin` ignore `page` and always say `← Back` |

**11 changed sides rather than being deleted**, which is the entry in this table worth
reading first. It used to assert `topBarInert` and that *nothing at all* outside the
overlay is focusable. Both are now false by design: the way off the viewer is the top
bar's left slot, so a bar taken out of the tab order would be a dialog whose one exit
the keyboard cannot reach. It asserts the exact set instead — the back button and the
theme toggle, both inside `.top-bar`, and the page behind still inert. **M9** is that
decision's mutation: with the exemption gone the bar is unreachable and 11 reddens on
`topBarInert`, where before it would have gone green.

**M10** puts the ✕ back and reddens 13 alone; **M11** flattens the derived label and
reddens 13 (`← Inbox`) here and `recipe-page-checks.js` 25 (`← Recipe`) there — the two
origins of one `case`, which is why they are two checks in two files.

M1 is the one to be careful with. Nesting the pane *inside* `.inbox-row` produces row
heights in the thousands and reddens a height check trivially; that shape never
shipped. As a sibling, `.inbox-row` stays one line and the height check has to measure
the **entry** — `.inbox-list`'s own children — to see anything at all. Check 2 does.


## `recipe-page-checks.js` — a Recipe's own page, and its address

A **second suite**, in its own file rather than appended to `checks.js`. That one is
the Inbox's, and a check's number there is its name and not its position — adding
unrelated checks to it would break exactly that promise. This one has its own numbers
and its own subject.

Four of its six phases need no seed and write nothing. What they read is one Recipe
of the dev database, named at the top of the file:

    const SUBJECT = 'Sourdough starter';

which has to be **published** and has to have a **body**: the body is what check 1
compares across the card and the page, and the latch is what makes check 4a the
visitor's case it claims to be. If that Recipe is gone, point the constant at another
one that is both. The only trace a run leaves is the `view_count` those reads move,
which is what reading a Recipe *is*.

### Six phases, and why it is not one evaluate

    (<contents of the file>).shelf()       — signed in, standing on /
    (<contents of the file>).coldLoad()    — after loading /recipe/<id> fresh
    (<contents of the file>).coldEdit()    — after loading /recipe/<id>?edit=true fresh
    (<contents of the file>).signedOut()   — signed in, standing on /; it signs itself out
    (<contents of the file>).provenance()  — the provenance view; needs provenance-seed.py
    (<contents of the file>).filing()      — the Scope picker; needs the same seed

The two cold loads cannot share a context with the others, or with each other: the load
each is about replaces the JS context, which is the whole point of them. There are two
because the page has two modes and a load is the only way to arrive at one from outside
— `coldEdit` is the half that `sync-from-url!` alone can answer, and everything
`shelf()` asserts about the editor still passes if the boot never looks at `.-search`.

Back and Forward are the opposite case and stay inside `shelf()` — nothing there ever
leaves the document, every move is a `pushState`, so `history.back()` fires a `popstate`
in the same context and can be waited on like any other consequence. That now covers
Back **out of the editor** as well as Back out of the page (16, 17).

`filing()` is the one phase that **writes**, so it works on the seeded fixture and not
on `SUBJECT`: filing a Recipe is a save, and a suite that promises to leave his shelf
alone must not start filing it. It needs two of the owner's Scopes to exist, and it puts
the fixture back where it found it.

`shelf()` refuses to run from anywhere but the shelf, and says where it found itself
instead. `:recipes` is in the atom on every page, so a suite that only checked for the
Recipe would half-run from a Recipe page and produce two false reds.

    13  a card carries Page and nothing else
    1   the Page button opens the Recipe at its own address, and the body is the card's
    6   the page wears the same header facts as the card
    14  the four actions are reachable across the slot and the panel
    22  a Recipe page keeps the theme toggle and no page selectors
    25  the versions viewer says ← Recipe and comes back to it
    3a  Back returns to the shelf, and the bar says so
    3b  Forward returns to the Recipe, at the same address
    5   a top-bar button leaves the page and puts / back in the bar
    16  Edit goes to ?edit=true, prefilled, and Cancel comes back
    17  Back leaves the editor and Forward returns to it
    23  the slot is ← Shelf while reading and Save+Cancel while editing
    24  Cancel abandons the draft, and the next visit shows the Recipe
    2   a cold load of the address lands on the Recipe, not on a 404   (coldLoad)
    15  the confirmation and the editor draw from the page's own row
    18  a cold load of ?edit=true opens the editor, prefilled          (coldEdit)
    4a  signed out, a published Recipe still has a page                (signedOut)
    4b  an address that names no readable Recipe says so, and offers a way back
    19  signed out at ?edit=true gets the reading, with the query gone
    7   the toggle swaps the rendered body for the source, and back    (provenance)
    8   the numbers run 1..n over the body as it is stored
    9   each line is tinted with its own caution, not its neighbour's
    10  a line between the ends is a third colour, not rounded to one
    11  the legend on the page is the string the API sent
    12  no caution in the response, no button — even signed in
    20  a toggle files the Recipe, and the version does not move       (filing)
    21  two chips in one frame both land

6 is out of sequence for the reason 11 is in `checks.js`: it is about a page that is
*open*, and that is where one is. It was written after the fact — the first version of
the page showed five of the card's six header facts, because the two version counts are
a **listing** aggregate that `GET /api/recipes/:id` does not carry, and `source-split`
reads a count it was not sent as a fact it has not been told. Nothing failed. Comparing
the two surfaces is the only assertion that could have caught it, because each of them
on its own looked complete.

22, 23 and 24 came with the change that moved the page's **chrome into the top bar**:
the left slot holds `← Shelf` while reading and Save and Cancel while editing, and the
right-hand side keeps the theme toggle and nothing else. They are three claims about one
move — what is *not* up there any more, what stands in the slot in each mode, and that
the draft the slot's Save reads does not outlive a Cancel. The last of those is the one
with teeth: the draft used to be four component-local ratoms that went out of scope with
the component, and is now app-state that has to be *cleared*.

**14 was rewritten rather than re-listed.** The four actions live in *two* containers
now — the top bar's left slot carries `← Shelf`, Edit and Versions, and the panel keeps
Publish and Delete, by the page's own rule that the slot holds ways of *looking* at a
Recipe and the panel keeps what *changes* it. So the check spans both, with set equality
in each half: one that looked at a single container would go green with the other empty,
which is precisely the unreachability it exists to catch.

**5 changed its mechanism and kept its number**, because the route it asserted stopped
existing: it pressed `.inbox-toggle` from the Recipe page, and that button is not there
any more. It presses the left slot's `← Shelf` instead, which is now the only way out of
the page the bar offers — so the check is more nearly about its own name than it was. And
**18 gained the prefill assertion** it was always half-making: it compared the title
against nothing, and now compares all four fields against the stored row.

13, 14 and 15 came with the change that took **Publish, Edit, Versions and Delete off
the card and put them on the page**, and they are three claims about one move: the card
carries one button, the four are somewhere, and the two confirmations can draw on a page
the shelf's listing had nothing to do with. The last of those is the bug the whole change
turned on — the confirmations used to look their Recipe up in `:recipes` — and it is in
`coldLoad()` because clicking through from the shelf cannot see it. Each of the three is
green while either of the other two is broken, which is why they are three.

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

**The seed asserts that newline; check 8 only reports it.** The fixture is a Recipe in
a dev database and editing it in the UI is a normal thing for the owner to do — he did,
mid-run, which is how this came up. A check that failed for that would be blaming the
app for somebody using the app, so check 8 asserts the rows against the API's own line
count and pushes a **note** when the body no longer ends in a newline: the run was
thinner than intended, nothing is wrong, re-run the seed for a fixture that exercises
it again. Editing the fixture is also worth knowing about before running `cleanup.py`,
which deletes it and any edits with it.

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
| **M14** | put Publish, Edit, Versions and Delete back in `views/recipes`' card footer |
| **M15** | delete `(when logged-in? [actions recipe])` from `views/recipe/found` — the removal from the card without its replacement, which is the reading the work order for that change refused |
| **M16** | source `publish-modal` and `delete-modal` from `:recipes` again, as they were: `(first (filter #(= publishing (:id %)) recipes))` |
| **M17** | make `views/recipe/actions`' Edit set a plain flag instead of navigating — `(swap! state/*app-state assoc :recipe-page-edit? true)` — so the editor renders and the bar never moves |
| **M18** | drop the `?edit=true` half of `url/recipe-path`'s two-arity: `([id edit?] (recipe-path id))` |
| **M19** | stop reading the flag in `state/sync-from-url!`: `(show-page! :recipe id false)` |
| **M20** | let a visitor keep it — `edit? (url/editing?)` in `sync-from-url!`, without the `logged-in?` conjunct |
| **M21** | route the filing through `update-recipe` with a one-key map instead of `toggle-recipe-scope` |
| **M22** | send only what is selected: `(cond-> {} (seq ids) (assoc :scope_ids (vec ids)))` in `put-filing!`, so unfiling the last Scope omits the key |
| **M23** | hand the picker the next set again — `:on-toggle #(state/set-scopes id (if (contains? selected id) …))` — computed from `:selected` in the render |
| **M24** | disable the chips while a save is out instead of queueing: `:disabled? (= id (:id (:filing @state/*app-state)))` |
| **M25** | `assoc-in` in `cache-detail!` instead of `merge` |
| **M26** | render `[recipe/back-to-shelf]` inside `.recipe-page` again, above the `case`, and drop it from `core/left-slot` |
| **M27** | make `core/focused-surface?` answer `false` always — the selectors and Sign out come back to the Recipe page |
| **M28** | draw `← Shelf` in the slot while editing too, beside Save and Cancel |
| **M29** | drop `:recipe-draft {}` from `show-page!`'s `swap!`, so a draft survives a Cancel |
| **M30** | seed the draft instead of resolving it: have `views/recipe/editor` `swap!` the row's four fields into `:recipe-draft` on its first render, and make `recipe-edit-fields` read the draft alone |
| **M31** | put Edit and Versions back in `.recipe-page-actions` and drop `[recipe/navigation-actions …]` from `core/left-slot` |
| **M32** | move Delete up into the slot with them — `navigation-actions` renders it too |
| **M33** | make `core/focused-surface?` ignore `:diffing`, so the bar keeps its selectors under the viewer |
| **M34** | give the viewer's slot `← Shelf`, Edit and Versions as well as its back button |

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

M16 is this file's second `checks.js`-11 case: a mutation that only one check can see.
With the confirmations reading `:recipes` again, `shelf()` stays green and 2 stays green
— clicking Page from the shelf works because the listing is already in the atom — and 15
goes red on its own, which is the whole reason it lives in `coldLoad()` and empties the
listing before it presses anything.

M17 to M20 are the same lesson about the address, and they redden in four different
places on purpose. **M17** leaves 16 red and 18 green: the form is on screen either way,
so only the check that reads the bar can see it. **M18** reddens 16 and 17 and leaves 18
green, because a cold load still reads a flag the app never writes. **M19** is the
opposite — 18 goes red and 16 stays green, since the push still happens and the render
still follows it in the same context; **17** catches it too, which is the reason it is
not folded into 16. **M20** reddens 19 alone, and it is the one worth looking at: a
visitor gets a form, fills it in, presses Save, and the API answers 403.

M33 and M34 are the versions view's, and both are about the slot being an *order*:
**M33** leaves the page selectors up while a dialog is open, which 22 does not see (it
is about a Recipe page, not the viewer) and `checks.js` 13 does. **M34** puts Versions
in the slot while the versions view is up — a control for the surface you are already on
— and reddens 25.

M31 and M32 are the amendment's, and they are the pair that shows why 14 spans two
containers rather than one: **M31** empties the slot's half and fills the panel's, so a
check that only counted the panel would go green on it — 14 reddens because it asserts
both, and 23 reddens on the slot. **M32** is the one the rule exists to prevent: Delete
in a row of navigation, where a mis-aimed click costs a Recipe rather than a step. It
reddens 14 on both halves at once and is worth doing to see what it looks like.

M26 to M30 are the chrome's. **M28** is the one that looks harmless and is not: three
buttons where the middle one is Cancel and the first means *leave without asking* is the
arrangement a hurried reader gets wrong, and only 23 says so. **M30** is the one to run
if you want to see Part 3.3's trap for yourself: it passes everything in `shelf()`,
because pressing Edit has the row in hand, and reddens **18** alone — the editor comes up
with four empty fields on a cold load, which is indistinguishable on screen from a Recipe
that has no content. **M27** reddens 22 alone; **M26** reddens 5 and 23; **M29** reddens
24 and nothing else.

M21 to M25 are the filing's, and the pair worth understanding is **M23** and **M24** —
both of them are what an unhurried reader would write, and both lose a click. M23 sends
two saves that succeed with one chip missing from the result; M24 swallows the second
click outright. Only 21 sees either. **M22** reddens 20's second half alone — everything
about filing looks right until the moment he unfiles his last Scope, and then the key is
omitted and the Recipe stays where it was. **M21** reddens 20 on the version badge,
which is the whole point of the split. **M25** is the quietest of all of them: nothing in
either suite went red when it shipped, because the key it drops is `caution` and the
only thing that notices is the provenance toggle disappearing on a page nobody was
looking at — run `filing()` and then `provenance()`, in that order and in one context,
and 7 throws for want of a button.

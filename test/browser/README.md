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

Four of its seven phases need no seed and write nothing. What they read is one Recipe
of the dev database, named at the top of the file:

    const SUBJECT = 'Sourdough starter';

which has to be **published** and has to have a **body**: the body is what check 1
compares across the card and the page, and the latch is what makes check 4a the
visitor's case it claims to be. If that Recipe is gone, point the constant at another
one that is both. The only trace a run leaves is the `view_count` those reads move,
which is what reading a Recipe *is*.

### Seven phases, and why it is not one evaluate

    (<contents of the file>).shelf()       — signed in, standing on /
    (<contents of the file>).coldLoad()    — after loading /recipe/<id> fresh
    (<contents of the file>).coldEdit()    — after loading /recipe/<id>?edit=true fresh
    (<contents of the file>).signedOut()   — signed in, standing on /; it signs itself out
    (<contents of the file>).save()        — a version-making save; builds its own fixture
    (<contents of the file>).provenance()  — the provenance view; needs provenance-seed.py
    (<contents of the file>).filing()      — the Scope picker; needs the same seed
    (<contents of the file>).draftProvenance([token])
                                           — the draft preview; builds its own two
    (<contents of the file>).clampedBody() — the shelf's abbreviation; builds its own
    (<contents of the file>).barActions()  — Publish in the top bar; builds its own,
                                             and publishes it

The two cold loads cannot share a context with the others, or with each other: the load
each is about replaces the JS context, which is the whole point of them. There are two
because the page has two modes and a load is the only way to arrive at one from outside
— `coldEdit` is the half that `sync-from-url!` alone can answer, and everything
`shelf()` asserts about the editor still passes if the boot never looks at `.-search`.

Back and Forward are the opposite case and stay inside `shelf()` — nothing there ever
leaves the document, every move is a `pushState`, so `history.back()` fires a `popstate`
in the same context and can be waited on like any other consequence. That now covers
Back **out of the editor** as well as Back out of the page (16, 17).

**Two phases write, and neither writes to anything of his.** `filing()` works on the
seeded fixture and not on `SUBJECT`: filing a Recipe is a save, and a suite that promises
to leave his shelf alone must not start filing it. It needs two of the owner's Scopes to
exist, and it puts the fixture back where it found it.

`save()` is the only phase that makes a **version**, and it is the only one that builds
its own fixture rather than reading a seeded one — for the reason the version makes: a
save moves the ladder of cautions `provenance()` reads, so borrowing `CHECK-PROV` would
cost the *next* run its columns rather than this one a column. It creates `CHECK-SAVE`
through the API as a machine (three lines the API reads as `0.00` throughout, so a line
the owner then rewrites has somewhere visible to move to), saves an edit through the UI,
and **leaves the Recipe behind for `cleanup.py`** — deleting it through the API would
file a `deleted` event in his queue, which is the same reason `cleanup.py` uses sqlite.
It runs from anywhere and leaves the browser on the shelf, so the two phases after it
still start and end where they always did.

`shelf()` refuses to run from anywhere but the shelf, and says where it found itself
instead. `:recipes` is in the atom on every page, so a suite that only checked for the
Recipe would half-run from a Recipe page and produce two false reds.

    13  a card carries Page and nothing else
    1   the Page button opens the Recipe at its own address, and the body is the card's
    6   the page wears the same header facts as the card
    22  a Recipe page keeps the theme toggle and no page selectors
    25  the versions viewer says ← Recipe and comes back to it
    3a  Back returns to the shelf, and the bar says so
    3b  Forward returns to the Recipe, at the same address
    5   a top-bar button leaves the page and puts / back in the bar
    14  the four actions are reachable, set per container and per mode
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
    31  saving keeps the button, with the split of the version it wrote (save)
    32  a filing toggle keeps the split it did not change
    7   the toggle swaps the rendered body for the source, and back    (provenance)
    8   the numbers run 1..n over the body as it is stored
    9   each line is tinted with its own caution, not its neighbour's
    10  a line between the ends is a third colour, not rounded to one
    11  the legend on the page is the string the API sent
    12  no caution in the response, no button — in either mode
    26  the alignment rule keeps a line only at the same index and text
    27  edit mode tints the draft: a typed line is untold, its neighbours are not
    28  a line inserted on top makes the rest untold — the conservative arm
    29  Cancel leaves the reading's provenance exactly as it was
    30  the toggle is in the panel's corner, in both modes
    20  a toggle files the Recipe, and the version does not move       (filing)
    21  two chips in one frame both land
    33  an insertion leaves every other line as the API had it (draftProvenance)
    34  the inserted line previews at 1.00 — his
    35  the preview is what the save produces, line for line
    36  past the alignment budget the preview says untold, not "yours"
    37  an expanded card abbreviates a long body                       (clampedBody)
    39  the abbreviation keeps a fenced code block whole
    38  See more shows the rest of the body, and goes
    40  a short body is shown whole, with nothing to press
    41  Publish is in the bar, immediately left of the theme toggle    (barActions)
    42  the versions viewer takes Publish out of the bar
    44  an address that names no Recipe offers no Publish
    45  signed out, an unpublished Recipe offers no Publish
    43  Publish from the bar publishes, and the button goes

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

26 to 29 came with **provenance in edit mode**, over the draft. 26 is the only check in
either suite that calls a *pure function* — `provenance/draft-cautions` — because the
alignment rule is worth testing apart from the view that draws it: a red in 27–29 could
be the rule or the drawing of it, and 26 tells them apart. Its `twoIdenticalLines` case
is the one that records a decision rather than a behaviour: a text-keyed lookup would
tint the second `alpha` confidently and wrongly, so the rule is index **and** text.

**28 asserts the conservative arm on purpose.** A line inserted at the top makes every
line below it untold, which reads like a defect and is the design — under-claiming beats
a confident tint against the wrong line. It is written down as a check so that the next
person to think "a diff would fix this" meets the argument first. M35 is that mistake.

**12 was extended rather than duplicated**, because the rule is one rule: the button
exists when the answer does, in both modes.

**31 is the owner's complaint, and the only check in either suite that a green suite
could have hidden.** *saving made the show provenance button disappear until i went ofr
overview and came back.* The `PUT` now answers with the split of the version it wrote —
but `update-recipe` cached that response and dropped the split on the *very next line*,
so the server change on its own is invisible and nothing about it looks wrong. So 31
asserts the split as well as the button: the line he rewrote reads `1.00`, the two he
left alone still read `0.00`, and the version has moved by exactly one. Keeping the old
ranges would satisfy the button half while describing text that no longer exists, and
refetching the page would satisfy both while inflating `view_count`.

**32 is the other half of the same rule** — a filing save carries no split and needs
none, because `cache-detail!` merges and the answer cannot have changed. The two
together are what says the halves meet: the button survives a save that was told the
split and a save that was not. Which saves are *served* it is the server's half, in
`caution_integration_test.clj`.

**30 is the corner, measured rather than looked at.** *also it should be placed in the
top right corner of that REcipe's space.* Three claims: the toggle is the panel's first
line and flush with its right edge; it does not collide with the title, asserted as
*side by side or stacked* so that it holds at 390px as well as at full width; and
`.recipe-page-body-tools` — which now only ever holds the legend — does not render at
all while the view is off. That last one is the fifth leftover container of this run of
work and the only one a suite catches cheaply: an empty row keeps the panel's spacing
and reads as a rendering bug nobody can name. It measures against the **row** and not
the title, because the title carries `margin-right: auto` and its box ends where its
text does.

**14 has now been revised four times** — off the card, into the slot, when Delete went
to the bottom right, and now that Publish has gone up into the bar's *other* slot, so
that the panel has no actions row at all any more. Its shape is what made the fourth
revision one table and no new mechanism: set equality **per container, per mode**, with
the panel's vanished row asserted as the *absence of the element* rather than as an
empty list. What it cannot assert is the mode gate, because `SUBJECT` is published —
see `barActions()` 41. It also **moved** in the file, to sit after 3a/3b/5: it
navigates now (Edit, then Cancel), and those three read a history stack of exactly
`[/, /recipe/<id>]`. Above them it reddens 3a and then eight checks in a row, which is
how the constraint was rediscovered.

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
    python3 test/browser/cleanup.py            # CHECK-PROV, and the CHECK-SAVE
                                               # each save() run leaves behind

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

### The draft preview's fixtures, and the one credential that has drifted

`draftProvenance()` builds its own two Recipes rather than borrowing `CHECK-PROV`,
for `save()`'s reason: check 35 has to **save**, and a save moves the ladder of
cautions the `provenance` phase reads. `CHECK-DRAFT` is six lines an agent wrote,
two of them empty; `CHECK-DRAFT-BIG` is 250 lines, which is what it takes to get
past `alignment-budget` once the common head and tail are trimmed. `cleanup.py`
takes both out with everything else called `CHECK-`.

**It takes an optional machine token, and here is when you need one.** `save()`
logs in as `machine-user` / `pw`, which is what a fresh dev database is seeded
with — but a dev database is where passwords get rotated by hand, and this one's
had been, so both phases died at the login with nothing wrong in the app. Mint one
on the backend nREPL and pass it in:

    ;; on :nrepl-port from config.edn
    (et.cb.auth/create-machine-token nil "machine-user")

    …evaluate…  (<contents of the file>).draftProvenance('<the token>')

The phase notes which of the two routes it took, so a run always says on the record
whether it authenticated as documented.

### `clampedBody()` — the shelf abbreviates, and says there is more

*when uncollapsing a card, it should not show the full text immediately. rather it
should be abbreviated and show a show more button exactly as tracker does.* The clamp
is tracker's `ui.components.task-item/clampable-description`, ported into
`views.recipes/clampable-body`: the first `visible-blocks` blocks of the body and a
`See more`, one-way, with the state in a component-local ratom.

The phase **builds its own fixture** and needs no machine token — nothing here is
about authorship, so the plain POST dev reads as the owner's is enough. It builds
rather than borrows for `save()`'s reason turned around: what these checks need is a
body *longer than the threshold*, and `SUBJECT` is two blocks, which would make 37, 38
and 39 vacuously green. `CHECK-CLAMP` is `visible-blocks + 4` blocks — nine
paragraphs, a fenced code block, four more paragraphs — and `cleanup.py` takes it out
with everything else called `CHECK-`.

**The threshold is read out of the app rather than written down here.** The phase
takes `views.recipes/visible-blocks` off the namespace, so turning the app's 10 into a
5 does not redden anything — which is the point. A suite holding its own copy would go
red on a decision that had been made deliberately, and a copy "kept in sync" would
prove nothing at all. It reads `body-blocks` for the same reason, in 40.

**The fixture's code fence is placed where a blank-line split cuts it in half.** Nine
paragraphs put its three pieces at naive blocks 9, 10 and 11, so tracker's regex shows
two of them and leaves the fence open — the failure 39 exists for, and cookbook's own
case rather than a refinement of tracker's: the bodies here carry code with blank
lines in it, which is why this field gets the full parser and the highlighter at all.
So 39 asserts *that the fixture is still cut that way* as well as the outcome. A
fixture that stopped being cut would leave the check green while proving nothing, and
this one is built by the phase itself — so unlike check 8's trailing newline it cannot
drift without somebody editing this file, and it is an assertion rather than a note.

**39 runs before 38**, because 38 is what asks for the rest and 39 is about the
abbreviated reading. 40 is the control, and it is not ceremony: *every* card wearing a
See more is what a threshold of zero looks like, and M52 is that mistake — it passes
37, 38 and 39 without any of them noticing.

### `barActions()` — Publish moves into the top bar's right-hand slot

*In the Page view, put the Publish button in the top right, to the left of the dark
mode switcher.*

**A phase of its own because the button only exists on a Recipe that is not
published, and `SUBJECT` is.** That is not a detail: 14 and 22 assert that a Recipe
page's corner holds the theme toggle and nothing else, and on a published Recipe both
of them go on passing whether or not Publish was ever built. So this phase builds
`CHECK-BAR`, an ordinary owner's Recipe with a body, and asserts the five conditions
where they can actually fail. `cleanup.py` takes it out with everything else called
`CHECK-`.

It is the only phase in either suite that presses a **latch**: 43 publishes the
fixture, and there is no unpublish. That is the reason it builds its own rather than
borrowing one — the same reason `save()` does, one step further, since what this
leaves behind cannot be undone even in principle.

41 asserts a *position* and not a container: the button is inside `.top-bar-actions`,
that box is the toggle's immediately preceding sibling, and the two sit on one line at
one height. It also carries the mode, which is the line of 14's table that cannot be
tested on a published Recipe.

**Two of these came out of the mutation run rather than out of the design**, which is
the part worth reading:

- dropping the mode gate reddened **nothing** at first — all thirteen of `shelf()`
  green, 14's `publishAbsentWhileEditing: true` in its evidence, and the button sitting
  in the bar over the editor the whole time. 14 is on a published Recipe, where that
  line is vacuous. The editing arm moved into 41, where the button exists.
- 41 itself then reddened under M56, a mutation that has nothing to do with what 41
  asserts. It had captured `.top-bar-actions` as a **node** and asked it for its
  sibling at the end, after a mode round trip had unmounted and remounted it — a
  detached element answers `null` however right the bar is. It measures at the moment
  and keeps booleans now.

And the phase's own first wait was the house rule's first hazard, met with the wrong
consequence rather than with no wait at all: it waited for `.recipe-page-body`, which
the Recipe it was *leaving* also has, so 41 read a bar that was still the previous
page's — no Publish, no container, red — while 42 one check later saw the button
perfectly well. It waits on this Recipe's own title.

### The bar's mutations

| | the edit |
|---|---|
| **M53** | drop the `(some? diffing) nil` branch from `core/surface-actions`, so the page's own action stays in the bar while the viewer is over it |
| **M54** | render `(surface-actions app-state)` after the dark-mode toggle instead of before it |
| **M55** | drop `(not recipe-page-edit?)` from the gate — Publish survives into the editor |
| **M56** | `when-let` to `let` on the row lookup, so a missing row reads as *not published yet* |
| **M57** | drop `logged-in?` from the gate |
| **M58** | render `[:div.recipe-page-actions]` back into `views/recipe/found`, empty |

**M53 is the one part 2 depends on**, and it reddens 42 alone with
`top-bar-actions:Publish` sitting in the corner above a dialog. That is not only a
tidiness failure: `views.diff/inert-behind!` exempts the whole top bar from the
overlay's `inert`, so a Publish left standing there is one Tab and one Enter from a
one-way latch, from inside a surface that is not about publishing.

**M54** reddens 41 alone on `leftOfIt` and the sibling test, which is what makes 41 a
check about *to the left of the dark mode switcher* rather than about a class being
present somewhere in the bar. **M55** reddens 41 on its editing arm — and reddened
nothing at all before that arm existed. **M56** reddens 44 with a Publish button over
*No such Recipe here*. **M57** reddens 45 alone. **M58** is the leftover container, and
it is the only one that reddens two checks in two phases — 41 and 14 — which is what
that assertion is for.

**22 says which case it is looking at now.** The corner is no longer only ever the
theme toggle — a Recipe page carries Publish up there — but `SUBJECT` is published, so
what 22 sees is a corner with nothing in it but the toggle, and reading its `=== 1` as
*the rule* would be reading a happenstance. It asserts `subject.published === 1`
alongside, so a run against a database where that Recipe is not published says which it
was rather than going red about a control that is behaving.

**22 was found red before any of this was written, and it is worth knowing why.** Its
last conjunct was `barOnTheShelf.right.length === 4` — three page selectors and the
theme toggle — and the Deleted page put a fourth selector in that slot, so the check
had been failing since 🗑 joined the bar, for a decision made on purpose. It reads
`barOnTheShelf.right.length > here.right.length` and the theme toggle's presence now:
the shelf's half of 22 is a guard against comparing the page against nothing, and a
census of the bar is not what makes a Recipe page's slot a *narrowing*. The exactness
stays on the page's own slots, where the claim is. Nothing in the clamp's work touches
the top bar, and 22 is the one check in this suite that a fifth page would have
reddened again.

Check 1 in `shelf()` gained a step for the same change: it compares the body across
the card and the page, so it now presses `See more` if there is one before reading the
card's. `SUBJECT` is short and there is none today — which is exactly why the step is
written down rather than left to be discovered. The README invites pointing that
constant at another published Recipe with a body, and a long one would have reddened
check 1 for a card that was behaving correctly.

### The mutations

| | the edit |
|---|---|
| **M49** | `clamped?` to `false` in `clampable-body` — the state before this change, a card that shows the whole body the moment it opens |
| **M50** | `blocks` from `str/split` on blank lines, as tracker's `markdown-blocks` does, instead of `body-blocks` |
| **M51** | key the affordance off the body instead of the state — `(when (> (count blocks) visible-blocks) …)` — so it survives being pressed |
| **M52** | `(> (count blocks) 1)` in `clamped?` — a threshold of one block, with the cut left where it was |
| **M46** | `draft-cautions` back to the index-and-text rule it replaced — a draft line keeps its caution only at the same index with the same text |
| **M47** | in `draft-cautions`, `(nil? m) nil` instead of `1.0` — the alignment is right and the line being typed is left untold |
| **M48** | in `aligned-to-stored`, `:unknown` collapsed to `nil` on the over-budget branch — an alignment that was never computed reads as *you typed all of this* |
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
| **M35** | make `provenance/draft-cautions` match on text alone — `(some #(= line %) stored)` — instead of index and text |
| **M36** | have the editor tint the stored body: pass `line-cautions` where it passes `draft-cautions` |
| **M37** | key edit mode's toggle off `logged-in?` instead of `caution` being present |
| **M38** | render `[delete-action recipe]` only in `found`, so the edit page loses it |
| **M39** | drop the `when-not` from `mutating-actions`, so a published Recipe gets an empty actions row |
| **M40** | answer the `PUT` with the row alone — `{:status 200 :body result}` in `update-recipe-handler`, without the `cond->` — the state before the server change |
| **M41** | put `state/forget-versions!` back to dropping the split as well: `(update-in [:details id] dissoc :caution)` beside the `:versions` line |
| **M42** | bind the split *before* the write in `update-recipe-handler`, so the response describes the version it displaced |
| **M43** | drop the version gate — `split (caution-body ds req id)` unconditionally — so a filing `PUT` pays for a history fold too |
| **M44** | put the toggle back over the body: render it inside `.recipe-page-body-tools` again and drop the `corner` argument from `header` |
| **M45** | render `[provenance-legend legend]` unconditionally at both call sites, so the row is there with nothing in it |

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

M35 to M39 are provenance-in-edit-mode's and Delete's. **M35** is the one worth running:
it reddens 26 on `twoIdenticalLines` alone and leaves 27, 28 and 29 green, because a body
whose lines are all distinct cannot tell the two rules apart — which is why 26 exists.
**M36** reddens 27 and 28 (the editor stops describing the draft) and leaves 26 green.
**M37** reddens 12's editing half only. **M38** and **M39** are 14's: the first empties
the bottom-right container in the editing column, the second puts an empty row back under
the header for a published Recipe — the leftover this run of work has met four times.

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

M40 to M45 are the split surviving a save, and the toggle's corner. **M40 and M41 are
one bug from either end, and their evidence is the argument for asserting the ranges**:
M40 — the server before the change — leaves the button on the page with `[0, 0, 0]`
under a body whose second line he has just rewritten, and M41 takes the button away
entirely (`toggle: false`, the complaint verbatim). Both redden 31, and a check that had
only looked for the button would have called M40 a pass. M41 also reddens 32, as a
cascade rather than a second sighting: 31's save has already taken the split away, so
there is nothing left for the filing toggle to keep.

**M42** is the one a carelessly placed call would have shipped: the button is there, the
ranges are real, and they describe the version that was *displaced* — `[0, 0, 0]` again,
plausible and wrong. 31 sees it because it asserts which lines moved; so does
`caution_integration_test.clj`, on four assertions, one of which exists only to state the
difference from `before`.

**M43** is the honest gap in this suite. Nothing here goes red — the browser cannot
see it, because a split recomputed on a filing save is the *same* split; the whole cost
is a fold over the entire history per Scope chip clicked. Three assertions in
`caution_integration_test.clj` see it, *a filing-only save carries no split* first.

**M44** and **M45** are 30's, one per half. M44 — the toggle back over the body —
reddens 30 alone. M45 — the legend row rendered unconditionally — reddens 30, **11 and
12** as well, which is worth knowing: 11 asserts the legend goes away with the view and
12 asserts it never appears without a `caution` to explain, so an empty row is three
different lies at once.

**M49 to M52 are the abbreviation's, and each of the four reddens a different check.**
That is the run they were written from, and it is why there are four checks rather than
two. **M49** — no clamp at all, which is the state this change replaced — reddens 37
with all fourteen blocks on the card and 38 on `nothing to click: .see-more`, and
leaves 39 and 40 green: a body shown whole trivially has its fence whole, which is
worth knowing, because it says 39 is not a second reading of 37. **M50 is the one to
run**: it reddens **39 alone**, with `codeLinesShown: ['A']` and the listing rendered
as `;; MARK-10-CODE-A\n(defn a [] :a)` — two lines of code the reader can see the
beginning of, an unclosed fence, and 37 green throughout because the tenth block's
marker happens to be inside the code. **M51** reddens 38 on its second half only:
the whole body arrives and the See more is still sitting under it, which is the
control-that-did-nothing a check reading only the text would have called a pass.
**M52** reddens **40 alone** — `Sourdough starter`, two blocks, wearing a See more,
with `threshold: 10` still in the evidence beside it.

**M46 to M48 are the draft preview's, and they are three different failures rather
than three strengths of one.** M46 is the bug as reported: 33 goes red with four
untold rows where the API had said `0.00`, 34 with the owner's own new line untold,
and 35 with `preview: [0,0,0,null,null,null,null]` against `afterSave:
[0,0,0,1,0,0,0]` — which is the report itself, in one evidence object. M47 leaves
33 green and reddens 34 and 35, so it is the check that the *claim* is being made
and not merely that the alignment survived a shift. **M48 is the one that matters
most and the only one 36 sees**: it reddens nothing else, because a wholesale
replacement is the one edit where under- and over-claiming look alike everywhere
except on the rows themselves — 250 lines of an agent's work drawn as the owner's,
with no other check in either suite able to tell.

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

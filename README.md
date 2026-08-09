# Cookbook

A one-person store of **Recipes** — a title, a *useful-when* line, and a body —
written by the owner *and* by the agents he gives credentials to.

## What this is for

Cookbook is an **agentic memory store**. That decides more about it than the
schema does, and it inverts the house default, so it goes first:

> Every other plurama sibling treats an agent's write as something to be gated —
> there, a machine token is read-only until the owner switches recording mode on.
> **Cookbook deliberately does not.** A caller holding cookbook credentials
> writes freely, with no supervision and no toggle. What bounds it is a pair of
> rules about *his own writing*, below — neither of them a mode, and neither
> something anyone can turn off.

There is no `recording_mode` namespace here, no `/api/recording-mode` route, and
no machine-write guard in the middleware chain. Their absence is the feature. If
you are a future agent about to add one back "to be safe": don't — you would be
removing the reason this project exists.

What replaces the gate is two boundaries, and **both are about text the owner
wrote** rather than about agents being untrusted:

| | who may write |
|---|---|
| a Recipe with only agent-written versions | the owner **and** any credentialled agent — the shared scratch space |
| a Recipe he has written part of | the owner writes; an agent **proposes** and he approves |
| a published Recipe | the owner writes; an agent **proposes** and he approves — he has put his name to it |

The first is the **publish latch**: publishing is not primarily a visibility act
but an act of taking ownership. It makes a Recipe public *and* takes it out of an
agent's hands, in one irreversible step, and there is no unpublish, because
un-latching would hand a machine back the right to rewrite something the owner
had signed.

Out of its hands, not out of its reach: an agent may still **propose** against a
published Recipe. His call — *i think a machine should be able to propose against a
published Recipe. its up to the human to approve or not.* So on a **content** edit the
latch and the approval rule do the same thing, and the latch is the stronger of the two
there, because it also holds on a Recipe an agent wrote every word of. On everything
else they part company, and in both directions: the latch refuses the filing and the
approval rule allows it — see the table below, which is where that is spelled out.

The second is the **approval rule**, and it is deliberately softer: an agent's
edit of his text is neither applied nor refused, it is *filed* — a 202 and a
proposal in his inbox. Nothing is silently dropped, which is what keeps it from
being the gate this app refuses to have; the agent is told exactly what happened
and the decision is made in the open.

All of it is built: `POST /api/recipes/:id/publish` with anonymous visitors who
see published Recipes only, the machine table below, the approval rule and the
inbox it is reviewed in.

### The one machine user

One owner, one machine acting for him. The username is the literal
`machine-user`, there is at most one such row — a partial unique index says so,
not a handler — and the owner's only setting is its password: `GET
/api/machine-user` says whether it exists and when the password was last set,
`PUT /api/machine-user/password` sets or resets it. Creating it and changing its
password are the same operation on a fixed name, which is why the UI is one field
and one button. Neither route ever returns the password or its hash, and both
refuse a machine token — an agent rotating its own credential is how it would
lock the owner out of his own store.

**Its audience is the owner's, resolved once at login.** Recipes are keyed by
`user_id` and the machine user has a `users` row of its own, so a token carrying
that row's id would authenticate perfectly and then show an *empty shelf*. So
`login-handler` mints a machine token whose `:user-id` is the row's `for_user_id`
— the owner's. A Recipe an agent writes therefore belongs to the owner and shows
up on his shelf, and every handler is already right without having to remember a
resolution step.

What the machine may do — and there are two questions now, not one: whether the
Recipe is published, and **whose writing it holds**.

| | unpublished, all the agents' | unpublished, he wrote part of it | published |
|---|---|---|---|
| read | yes | yes | yes |
| create | yes | – | – |
| edit (content) | yes, unsupervised | **202, filed as a proposal** | **202, filed as a proposal** |
| file (`tags`, `scope_ids`) | yes, unsupervised | yes, unsupervised | **403** |
| delete | yes, unsupervised | **403** | **403** |
| publish | **403** | **403** | **403** |

**Published outranks the approval rule**, which is the one thing to get right in that
table: on a published Recipe a machine `PUT` is *always* a proposal, even in the first
column's case where every version is an agent's and the rule would otherwise let it
write straight through. A 200 in that cell would mean an agent had rewritten public text
unsupervised, so the two are asked as peers rather than one after the other.

Filing is the exception in the other direction. Tags and Scopes are not the text he
wrote, so an agent files an approval-required Recipe freely — but not a published one,
where filing stays his, and a `PUT` carrying `tags` or `scope_ids` is refused **whole**,
content and all: half-applying a request this rule means to refuse would be worse than
refusing it.

Delete is refused on a published Recipe because removing one takes it out of the public
listing, history and all, which is un-latching by demolition, and there is no such thing
as proposing a deletion. And a machine may not publish at all, published or not, because
the latch is irreversible — a machine that could set it could make private content
permanently public, and take its own writing out of its hands, with no way back for
either. Those rules live
in one place, `wrap-machine-recipe-rules`, which every mutating recipe route
passes through — installed with compojure's `wrap-routes` so that it runs *after*
the route has matched, and therefore reads the same recipe id the handler does
rather than parsing one off the raw path. There is no switch that lifts any of them.

The `DELETE`-only shape of the delete rule is the trap in that middleware: written on
every mutating method it would refuse the very `PUT`s the proposal path exists for.

### Edits that need approving

**A Recipe is the agents' to write freely only while every one of its versions was
written by an agent and it is not published.** One save of the owner's anywhere in its
history — or the latch — and the next machine edit to its *content* is not applied: it
is filed as a **proposal**, and the Recipe goes on reading exactly as it did until he
approves it.

His words: *an agent can modify any recipe which has been generated by an agent and
has only agent-stamped versions. but when there is a human modification inbetween,
it needs approval.*

The rule is `machine_versions = version` — two numbers every listing row already
carries. There is deliberately **no `approval_required` flag**: a flag could drift
from the counts the card shows, and an agent can check the rule before it writes.
Note what the rule is *not*: not `has_human_edit`, which read 0 for every Recipe he
typed by hand before migration 004; and not the row's own `source`, because his
version may be two saves back in the history, which is exactly the case approval is
wanted for.

| | |
|---|---|
| a machine `PUT` that would change content | **202** `{pending, recipe}` — accepted, not applied |
| a second proposal on the same Recipe | **409** `reason: proposal-pending`, carrying the pending text |
| the same with `?overwrite=true` | 202, replacing it in place, keeping its place in the queue |
| a machine `PUT` whose `modified_at` is stale | **409** `reason: modified-elsewhere`, checked first |
| `tags` or `scope_ids` in the same request | applied immediately — filing is not the text he wrote |
| the same on a **published** Recipe | **403**, nothing applied — filing a published Recipe stays his |
| a machine `PUT` that changes nothing | 200, still a no-op; it proposes nothing |

202 rather than 200 because the honest thing to say is *accepted, not applied* —
an agent that read 200 as "my text is live" would be wrong in a way nothing else in
this API is. And a proposal is emphatically **not** the machine-write gate this app
refuses to have: nothing is silently dropped, the response carries both texts, and
what happens next is a decision the owner makes in the open.

At most **one unresolved proposal per Recipe**, said by a partial unique index
rather than by a handler — which is what makes *there are no merge conflicts* a
property of the database. A proposal is the three content fields and nothing else,
because that is what a version is here.

Only the owner resolves one, from the inbox: `POST /api/inbox/:id/approve` writes the
agent's three fields as the next version, stamped `machine`, archiving the outgoing
one with *its own* label. It does **not** set `has_human_edit` — putting your name to
text an agent wrote is not writing it — so the Recipe still needs approval next time.
`POST /api/inbox/:id/dismiss` closes it and touches nothing. Both are 403 for a
machine token: an agent approving its own proposal would be the whole mechanism
undone.

`base_version` says what the proposal was written against and is deliberately not a
guard. If he saved in between, approving replaces his newer text with the agent's —
so the inbox says that in words, on the item, before the click. The item says when the
Recipe is **published** for the same reason: approving then replaces text that is
already public and signed, and there is no unpublish.

**What a visitor is shown is the last approved version, always.** Publishing is allowed
while a proposal is waiting, and an agent may propose against a published Recipe — so
this sentence is the whole of what stands between an unapproved wording and an anonymous
reader, and it is a guarantee rather than a consequence. It holds because a proposal is
not a version: it lives in `recipe_proposals`, the `recipes` row is the last approved
state, every read serves the row, and publishing publishes the row. His case, in his
words: *if say the last version v3 was from a machine and the human approved, and then
the machine sends another request, on publish, what an anon user sees is v3.*
**Two tests hold it, one per half.**
`what-a-visitor-sees-is-the-last-approved-version` is the reads: it reddens if any read
starts serving the pending text, at any `?detail`, to anybody.
`publishing-while-a-proposal-pends-publishes-the-approved-version` is the publish: it
reddens if the latch is refused while something waits, or if publishing applies the
pending proposal on its way out. The second one is named here because the first cannot
fail for it — a bug in the publish is not a bug in a read, and it went untested until it
was looked for.

### The inbox

**Every change an agent makes to a Recipe appears in a queue, oldest first, and he
answers each one — marking it seen if it already happened, approving or dismissing it
if the agent is asking.** That is the page the ✉ button in the top bar opens, and the
count on it is how many are waiting. A Recipe with a proposal against it also says so
on its own card, so the shelf shows what the queue is about — but only the queue can
answer it, since only there is the agent's text shown against the Recipe's.

His words: *every recipe change appears there, in order of a queue, that is, newer
appended items go bottomwards … so i can go through the things topmost first (oldest
unseen change first).* And, asked whether his own edits belonged in it: *no my own ui
edits should not land in the inbox.*

So this is **not a change log** — it is the record of what the agents did while he
was not looking, which is also what makes working through it oldest-first worth
doing. An entry exists exactly when the write was stamped `source = machine`, off the
same fact the label itself comes from.

| kind | what it means | `version` |
|---|---|---|
| `created` | an agent wrote a Recipe | 1 |
| `modified` | an agent's save changed its content | the **new** version |
| `deleted` | an agent deleted it | the version it died on |
| `proposed` | an agent is waiting for approval | the version proposed against |

Nothing else makes an entry: a save that changes nothing makes none because it makes
no version, a tags- or Scope-only save makes none because filing is not content, and
publishing makes none. `deleted` is the one kind he did not ask for by name — without
it an agent could create a Recipe and delete it again and the inbox would record the
create and then erase it.

An entry also wears the **Scope badges** of the Recipe it is about, as a shelf card
does — *this page doesnt show the scope badges yet … so i dont know for what the
recipes are* — because a queue is worked through by deciding what to look at, and a
title alone does not say what area a change was in. Note that the two facts on a row
behave differently on purpose: `recipe_title` is a snapshot taken when the change
happened, so an entry still reads after its Recipe is renamed or deleted, while the
badges are read *now*. A refiled Recipe therefore shows its new Scopes beside its old
title, which is the pairing triage wants — what the entry is about, and what area it
belongs to today. A Recipe that is gone lost its associations with it, so those
entries simply have no badges.

`GET /api/inbox` is the whole read surface; there is no listing of *seen* entries,
because the queue is what has not been looked at, and no unseen-count endpoint,
because the count is the length of that list. `POST /api/inbox/:id/seen`
acknowledges one — and **refuses a `proposed` entry**, which is answered rather than
acknowledged. All of it is the owner's alone: a machine token is refused, and so is a
caller with no credentials.

**Every entry is one row, and the title is the way through to it wherever there is
something left to open.** The version viewer is where a change is read: what a
`modified` save changed, what a `created` Recipe says, and on a `proposed` entry the
agent's text against the Recipe's, with Approve and Dismiss in the viewer's header
beside the pair on the row. **Not on every entry, though** — a `deleted` entry's title
is plain text, and so is a `created` or `modified` one whose Recipe has since gone: the
history went with it, so there is nothing to show and a link would 404. `openable?`
decides that from the server's `recipe_exists` rather than from the kind, because the
client's copy of the shelf may be narrowed by a search and 'not in the listing' is not
'not there'.

A proposal used to be shown inline instead, under its own row — *Proposals, just like
the other changes, should be shown on a different page (note the difference in
treatment)*. One entry taking two thirds of the page is one entry pushing the rest of
the queue off the bottom of it, and the inline panes were capped in height, so a
change beginning in the second paragraph of a long Recipe was below the fold of the
thing meant to show it. There is **one** overlay: both readings are drawn from the
same shell, so they cannot drift into two pages that merely resemble each other. It is
a **dialog** and not a panel that happens to cover the page: `inert` on everything
behind it, so the keyboard cannot reach what the mouse cannot.

A `proposed` entry is the one that has to say more than what happened, because
approving it writes. It carries the agent's three fields *and* the Recipe's current
three, so the comparison is against what the Recipe says now; it says in words when the
proposal was written against an older version; and it says in words when the Recipe is
**published**, because approving then replaces text that is already public and signed
and there is no unpublish. Two notes, and both can be on at once. Those six fields ride
on the list rather than being fetched when the viewer opens, which is a decision with
its measurements written down in `db.proposal/attach-to-events`.

**Those two are also the two that cannot be approved from the row.** Approve is on the
row for triage and on the viewer's header for deciding having read, and where one of
those notes applies the row's button is dead: the sentences are paragraphs and a row is
one line, so the row carries a `published` flag and a version badge reading `v1 → v3`,
and the answer is given on the surface that has room for the words. A proposal with
nothing to warn about is still one click on the row. Dismiss is unaffected and asks
first on both paths.

Two things the queue is careful about. It is ordered by the event `id` and never by
`created_at`, which is second-resolution — two entries in one second is the normal
case. And **events outlive their Recipe**: deleting one takes its history and its
Scope associations, and leaves its entries, each of which keeps a snapshot of the
title so it still reads as something.

### What a visitor sees

Recipes are **private by default** and a visitor is shown the published ones,
whoever owns them. An unpublished Recipe is *absent* rather than redacted: not
in the listing, no title and no id, and asking for it by id gives the same 404
as an id that never existed. The version history is the owner's — a visitor
gets a 404 there whether or not the Recipe is published, because the states
behind a published Recipe were never published themselves.

The lean rule applies to a visitor unchanged, `?detail=full` and all: the
collapse is about verbosity, the privacy boundary is the latch.

And **what a visitor sees is the last approved version, always** — see *Edits that need
approving*. An agent's proposal is not a version and no read consults one, so a published
Recipe with an unapproved rewrite waiting on it hands a visitor the approved text and no
part of the proposal, at any `?detail`. That is the guarantee the whole approval design
rests on now that publishing is allowed while a proposal pends. They are not told a
proposal exists either: `pending` is absent from their rows, like the tags.

**How the owner files his shelf is not theirs at any `?detail`** — *to logged in users
only, no matter what*. A visitor's rows carry no `scopes` key at all, and
`?exclude-scopes` is **ignored for them entirely** rather than applied to their published
rows. That second half is what makes the first hold: a caller who could watch rows vanish
on request could binary-search which published Recipes carry a given Scope, one id at a
time. It is also where the Scopes are a stronger boundary than the tags — a tag's
*presence* is testable through `?search=` even though its value is never sent, while
nothing searches the Scopes and nothing narrows by them for an anonymous caller.

## Recipes

A Recipe has three fields, and the split between them is a retrieval index, not
a bandwidth optimisation:

- `title` and `useful_when` — what an agent scans to decide whether a Recipe is
  relevant, the title being the one `?search=` matches on;
- `description` — the body, fetched for exactly the one Recipe that turned out
  to be relevant.

So **lean is the default**, in the API and in the UI alike: a listing and a plain
`GET` carry no `description` key at all, `?detail=full` adds it, and expanding a
card in the browser goes and fetches it. Recipes are **fully versioned** — every
edit that changes something archives the outgoing state and bumps the version.

A Recipe is private when created and stays that way until the owner publishes
it from its card, behind a confirmation because the step is one way. A published
card wears a badge and loses its Publish button. A card also wears a `proposal`
badge while an agent is waiting for approval on it — see *Edits that need
approving* — and that one is a badge and not a control: the deciding happens in
the inbox, against the agent's text.

The shelf narrows three ways at once, all of them the endpoint's own clauses
rather than anything the browser filters: the search box, the human-edited
checkbox, and **shift+click on a Scope badge, which hides the Recipes filed under
that Scope**. That last one is tracker's gesture, chosen so the two apps answer to
the same finger, and it only ever hides — a plain click still expands the card.
Several Scopes can be hidden at once; each one is listed as a chip above the shelf
with an × that brings it back, which is the only way back, since an excluded
Scope's badges leave along with the Recipes carrying them. Nothing is remembered
across a reload.

### A Recipe's own address

Every Recipe is also a page of its own at **`/recipe/<id>`** — bare, no type
prefix and no title in the slug. The **Page** button in a card's footer goes
there, beside Publish, Edit, Versions and Delete; it is called *Page* rather than
*Open* because expanding a card is what "open" already means on the shelf.

The point of it is the address. `/recipe/1` can be linked to, bookmarked, sent to
somebody and **reloaded**: the server answers every `/recipe/…` with the index,
so a cold load of one lands on the Recipe and not on a 404. Back and Forward
work, because the browser's history is what the page is derived from rather than
something the app writes into and then ignores. Everywhere else in the app is
`/` — there is one addressable thing here and the rest is the app.

The wildcard deliberately does not look at the id. Which Recipes exist, and which
of them the caller may see, is the API's answer; so `/recipe/999999` and a
visitor's `/recipe/<unpublished>` are served the app, which asks, is told no, and
says *No such Recipe here* with a way back to the shelf. Those two cases are the
same 404 by design and the page does not try to tell them apart — see *What a
visitor sees*.

**The page is not owner-only**, and it is the only one that is not: a link to a
published Recipe that stopped working when you were not signed in would not be a
link. What it shows a visitor is what the API gives them — every content field,
and none of the owner's filing. And **opening it counts as a read**, the same as
expanding a card or fetching the Recipe through the API, which is the number the
shelf is ranked by.

All three fields are meant to be markdown, with clojure code blocks highlighted
in the body. **That is not built yet**: it needs `marked`, `highlight.js` and
`DOMPurify`, and the sanitizer is not optional here — agents write unsupervised
and a published Recipe is served to anonymous visitors, so `marked` output
reaching the DOM unsanitized would be an XSS route no sibling app has. Until all
three are in, bodies render as plain text.

## Hosting

Runs standalone in dev, and in production inside the [plurama](../plurama)
umbrella at `cookbook.eighttrigrams.net`. Namespace prefix `et.cb`.

## Ports

- `PORT` **3170** — the clojure server
- `SHADOW_PORT` **9807** — shadow-cljs
- nREPL **7901** (dev only)

The defaults are declared in `config.edn` / `config.edn.template` and
`shadow-cljs.edn`; nothing needs to set the env vars.

## Development

```bash
make start   # shadow-cljs watch + the clojure server
make stop
make test
make lint
```

Then open http://localhost:3170. Dev uses `:dangerously-skip-logins? true`, so
there is no login to get past; `scripts/start.sh` writes a default `config.edn`
on first run (it is gitignored).

In production `plurama` mounts cookbook at `cookbook.eighttrigrams.net` by `Host`
header. It calls `et.cb.server/build-app` with cookbook's `:apps :cookbook`
sub-config, so the db path comes from plurama, not from here.

## API

The API documents itself:

```bash
curl localhost:3170/api/describe
```

Every route handler's docstring is its documentation, in the form
`METHOD /path — what it does`. The listing carries `:method` and `:path` as
separate fields. An agent is the primary reader of this API, so the docstrings
are the interface, not decoration.

### Recipes

- `GET /api/recipes` — the listing, **ranked by use**: `0.7 × view_count +
  0.3 × version` descending, then most recently modified, then highest id.
  `?search=` narrows over the **title and the tags** by **word-prefix**, AND
  across whitespace-separated terms: `ab cd` finds `abc cde` but not `ad cd`, and
  `cd` does not find `abcd`. A word is a run of letters and digits, so `heating`
  finds `Re-heating`. `%` and `_` are ordinary characters. `?human=true` narrows to
  the Recipes a human has edited. `?exclude-scopes=3,7` **hides** the Recipes filed
  under those Scope ids — the only *negative* filter here, and there is deliberately
  no positive one; several ids take more away, a Recipe filed under no Scope at all
  is never hidden by it, and an id you do not own hides nothing rather than erroring.
  All three narrowings compose, being clauses on one query. **An anonymous caller's
  `?exclude-scopes` is ignored entirely** — see *What a visitor sees*. **Lean**: no
  `description` key at all. Each row carries `machine_versions` / `ui_versions`,
  `view_count`, and `pending` — whether a proposal is waiting on it.
- `GET /api/recipes/:id` — one recipe, lean the same way.
- **`?detail=full`** on either of those adds the description. That is the only
  way to get a body, and it is meant to be asked for one recipe at a time. On the
  single-Recipe read it also adds `caution`, the line-level provenance split of that
  body — see *Which lines are his*.
- `POST /api/recipes` — `{:title :useful_when :description}`. Title required.
  The new recipe is version 1 and private; `published` is not accepted here.
- `PUT /api/recipes/:id` — the same three fields; anything you leave out keeps
  its current value, on a proposal exactly as on a save. Pass `modified_at` from your
  last read to be told (409) when someone else saved in between. A save that changes
  nothing is a no-op. **From a machine token, this answers 202 and files a proposal on
  a Recipe the owner has written part of *or* that is published** — both triggers, and
  the second one holds even when every version is an agent's — see *Edits that need
  approving*, and note the two different 409s that route can give, told apart by
  `reason`. `?overwrite=true` replaces a proposal already pending. On a published
  Recipe a `tags` or `scope_ids` key makes the whole call a 403.
- `DELETE /api/recipes/:id` — the recipe and its whole history. Its inbox entries
  survive it; a pending proposal on it is closed.
- `GET /api/recipes/:id/versions` — every version, newest first, each with all
  three fields, its `created_at` and its `source`. The newest carries
  `current: true`. Owner-only.
- `POST /api/recipes/:id/publish` — set the latch. **Idempotent**: publishing
  something already published is a 200 no-op and does not move `published_at`,
  because the first publish is the fact being recorded. Not a content change —
  no version bump, no history row, and `modified_at` stays where it was. There
  is no unpublish route, deliberately.

### The inbox

Owner-only, all four: a machine token is refused, and so is a caller with no
credentials.

- `GET /api/inbox` — the unseen entries, **oldest first**. Each carries its own
  `id` (which the other three take), `recipe_id`, `recipe_title` as it read then,
  `kind`, `version`, `created_at`, `proposal_id` (the join key behind `proposal`,
  null unless the entry is one), `recipe_exists` and `scopes` — the Scopes the
  Recipe is filed under *now*, `{id, title, description}` each and empty for one
  filed under none. A `proposed` entry also
  carries `proposal`, with the three proposed fields *and* the three the Recipe
  says now, so it can be reviewed as a diff against current.
- `POST /api/inbox/:id/seen` — acknowledge one. Idempotent. **400 for a
  `proposed` entry**, which is answered rather than acknowledged.
- `POST /api/inbox/:id/approve` — apply an agent's proposal as the next version.
- `POST /api/inbox/:id/dismiss` — decline it; the Recipe is untouched.

### Versioning

`recipes` holds the current state, `recipe_history` the superseded ones keyed
`(recipe_id, version)`. A save archives the outgoing state at its own version
number and moves the row to the next one, so the history holds versions 1..N-1
and the row is N. The version is stored **on the row** rather than derived from
the history, because recipes are a collection and deriving would mean a
correlated subquery per row in every listing.

Publishing does not create a version and `published` is not in the history
table: versions are about content, the latch is a separate fact about the row.

### Where each version came from

Every version carries a `source`, and it is **one of exactly two values**: `ui`
for a save made by hand here, `machine` for one written by an agent. It sits where
the version it describes sits — on the row for the current version, on each history
row for the superseded ones — and it never changes after the fact: a save archives
the outgoing version with *its own* label, so an agent's edit cannot retroactively
relabel what the owner wrote. `GET /api/recipes` counts them per Recipe as
`machine_versions` and `ui_versions`, which sum to `version`; `GET
/api/recipes/:id/versions` gives the label per version.

There used to be a third answer. `source` was nullable and NULL meant *nobody
recorded this* — true of every version written before migration 005, which
deliberately refused to guess who wrote them. That refusal is what left the answer
to the one person who could give it: asked what those versions were, the owner said
they were his. Migration 010 wrote that down, brought `has_human_edit` up to match
it, and made the column `NOT NULL CHECK (source IN ('ui','machine'))` — so the
distinction is now two-valued in the schema and nothing downstream has a third case
to handle.

`has_human_edit` is the row-level bit beside it, true exactly when some version
reads `ui`, and it is what `?human=true` narrows by. It is kept rather than derived
because deriving it would mean an aggregate over the history on every listing read.

### Which lines are his

**The same question asked of the text instead of the versions.** A `?detail=full`
read of one Recipe carries `caution` beside the description: the lines of that
description in ranges, one-based and inclusive, each with a number saying how careful
an agent should be there — and the legend for that number, in the body, every time.

```json
"caution": {
  "legend": "1.00 saved here by hand, 0.00 written by an agent; in between, a stretch both have touched",
  "ranges": [{"from": 1, "to": 2, "caution": 0.0},
             {"from": 3, "to": 3, "caution": 1.0}]
}
```

`1.00` is his: treat it as close to sacred and have a good reason before touching it.
`0.00` is an agent's and up for grabs. In between is a stretch both have had a hand
in. The ranges cover the body exactly once, in order, and adjacent lines that come
out at the same number are one range.

The legend is nested with the ranges rather than sitting beside them as a
`caution_legend`, because neither half means anything alone, and it is repeated on
every read rather than left to `/api/describe` for a plain reason: the reader is
usually an agent that fetched one Recipe and read no documentation at all, and to
that reader a bare `0.0` next to a line range is a number it has to already know how
to read. It is the same string every time — it explains the scale, not this Recipe's
answer — so read it once and treat it as a constant thereafter. Its wording is the
version viewer's tooltip said again on purpose (*saved here by hand* / *written by an
agent*); one fact told in two vocabularies is two facts to anyone who meets both.

**This is not `machine_versions`/`ui_versions` asked again.** Those count *versions*,
this attributes *the lines of the text as it stands now*, and the two come apart
immediately: a Recipe he wrote once and an agent has since edited nineteen times
reads `1(ui)/19(machine)` on its card while his opening paragraph is still at `1.00`
here. Neither is wrong; only the second tells an agent about to rewrite that body
which parts of it are its own to redo and which are his to leave alone. That is what
it is for, and it is why **a machine token is served it** — as it reads everything
else here, in the owner's audience. It is the one number in this API written for an
agent to act on.

**It is an estimate.** Nobody recorded who typed which line. It is computed by
[us-vs-them](../us-vs-them) — a sibling library, wired in by `:local/root`, whose
`caution_test.clj` is the specification of what the numbers mean — by diffing the
Recipe's versions against each other and attributing from that, looking for islands
of his writing in a sea of generated text.

A number between the ends is what dilution looks like, and it is a property of the
stretch rather than of any one line: an agent's line landing *inside* a stretch of
his joins it instead of splitting it, and the stretch drops to the share of itself
that is still his — three of four lines reads `0.75`. That is deliberate, and worth
knowing before acting on one. The alternative would make that line an island at
`0.00`, free to edit, at exactly the spot where it is most tangled up in his work.
So read a middling number as *be careful here anyway*.

Cookbook had both halves of the input all along and had never joined them up: every
version carries a `source`, every superseded version keeps its own text in
`recipe_history`, and that is exactly a history of versions each under identifiable
authorship, which is the only thing the library asks for. `et.cb.caution` is the
whole of the adapter, and none of the arithmetic.

**A visitor gets no `caution` key at all** — legend included, at any `?detail`,
published or not. Not an empty list: absent, the way `tags` and `scopes` are absent
for a caller who may not have them. It is derived from the version history and the
history is his, so it goes where the history goes; `GET /api/recipes/:id/versions` is
a 404 for an anonymous caller at every id. Publishing puts today's text in public. It
does not publish the record of who wrote which part of it.

Not cached, and there is no column for it — the same argument the counts on the card
make: a stored split could come to disagree with the labels the version list shows.
The cost is a second read of the history and a diff per version, on a full read only,
which is nothing at the size of a Recipe and is the first thing to look at if that
route ever gets slow.

### Rate limiting

A single global window, outermost in the middleware chain: 180 requests/minute in
production, 720 in dev. Override with `RATE_LIMIT_MAX_REQUESTS` and
`RATE_LIMIT_WINDOW_SECONDS`. Over the limit returns a bare `429`. This is not a
supervision mechanism — it is there for a runaway loop or a hostile caller, and
nothing about unsupervised writes argues for removing it.

## Layout

- `src/clj/et/cb` — ring/compojure backend, next.jdbc + honeysql over SQLite,
  ragtime migrations in `resources/migrations/net/et/cb`.
- `src/cljs/et/cb/ui` — reagent SPA.
- `resources/public/cookbook` — `index.html`, `styles.css`, `css/` (amber theme
  in `base.css`, app layout in `cookbook.css`, phone rules last in `mobile.css`).

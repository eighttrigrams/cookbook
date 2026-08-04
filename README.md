# Cookbook

A one-person store of **Recipes** — a title, a *useful-when* line, and a body —
written by the owner *and* by the agents he gives credentials to.

## What this is for

Cookbook is an **agentic memory store**. That decides more about it than the
schema does, and it inverts the house default, so it goes first:

> Every other plurama sibling treats an agent's write as something to be gated —
> there, a machine token is read-only until the owner switches recording mode on.
> **Cookbook deliberately does not.** A caller holding cookbook credentials
> writes freely, with no supervision and no toggle. The one boundary is the
> publish latch, below — not a mode, and not something the owner can turn off.

There is no `recording_mode` namespace here, no `/api/recording-mode` route, and
no machine-write guard in the middleware chain. Their absence is the feature. If
you are a future agent about to add one back "to be safe": don't — you would be
removing the reason this project exists.

What replaces the gate is the **publish latch**, and nothing else:

| | who may write |
|---|---|
| unpublished Recipe | the owner **and** any credentialled agent — the shared scratch space |
| published Recipe | **the owner only** — he has put his name to it |

Publishing is therefore not primarily a visibility act but an act of taking
ownership: it makes a Recipe public *and* freezes it against machine mutation,
in one irreversible step. There is no unpublish, because un-latching would hand
a machine back the right to rewrite something the owner had signed.

Both halves are built: `POST /api/recipes/:id/publish` with anonymous visitors
who see published Recipes only, and the machine half below.

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

**Its scope is the owner's, resolved once at login.** Recipes are scoped by
`user_id` and the machine user has a `users` row of its own, so a token carrying
that row's id would authenticate perfectly and then show an *empty shelf*. So
`login-handler` mints a machine token whose `:user-id` is the row's `for_user_id`
— the owner's. A Recipe an agent writes therefore belongs to the owner and shows
up on his shelf, and every handler is already right without having to remember a
resolution step.

What the machine may do:

| | unpublished Recipe | published Recipe |
|---|---|---|
| read | yes | yes |
| create | yes | – |
| edit | yes, unsupervised | **403** |
| delete | yes, unsupervised | **403** |
| publish | **403** | **403** |

Delete is refused on a published Recipe for the same reason edit is: removing one
takes it out of the public listing, history and all, which is un-latching by
demolition. And a machine may not publish at all, published or not, because the
latch is irreversible — a machine that could set it could make private content
permanently public *and* freeze the Recipe out of its own reach. Both rules live
in one place, `wrap-machine-recipe-rules`, which every mutating recipe route
passes through — installed with compojure's `wrap-routes` so that it runs *after*
the route has matched, and therefore reads the same recipe id the handler does
rather than parsing one off the raw path. There is no switch that lifts either.

### What a visitor sees

Recipes are **private by default** and a visitor is shown the published ones,
whoever owns them. An unpublished Recipe is *absent* rather than redacted: not
in the listing, no title and no id, and asking for it by id gives the same 404
as an id that never existed. The version history is the owner's — a visitor
gets a 404 there whether or not the Recipe is published, because the states
behind a published Recipe were never published themselves.

The lean rule applies to a visitor unchanged, `?detail=full` and all: the
collapse is about verbosity, the privacy boundary is the latch.

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
card wears a badge and loses its Publish button.

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

- `GET /api/recipes` — the listing, most recently saved first. `?search=` narrows
  over the **title** by **word-prefix**, AND across whitespace-separated terms:
  `ab cd` finds `abc cde` but not `ad cd`, and `cd` does not find `abcd`. A word
  is a run of letters and digits, so `heating` finds `Re-heating`. `%` and `_`
  are ordinary characters. **Lean**: no `description` key at all.
- `GET /api/recipes/:id` — one recipe, lean the same way.
- **`?detail=full`** on either of those adds the description. That is the only
  way to get a body, and it is meant to be asked for one recipe at a time.
- `POST /api/recipes` — `{:title :useful_when :description}`. Title required.
  The new recipe is version 1 and private; `published` is not accepted here.
- `PUT /api/recipes/:id` — the same three fields; anything you leave out keeps
  its current value. Pass `modified_at` from your last read to be told (409)
  when someone else saved in between. A save that changes nothing is a no-op.
- `DELETE /api/recipes/:id` — the recipe and its whole history.
- `GET /api/recipes/:id/versions` — every version, newest first, each with all
  three fields and its `created_at`. The newest carries `current: true`.
  Owner-only.
- `POST /api/recipes/:id/publish` — set the latch. **Idempotent**: publishing
  something already published is a 200 no-op and does not move `published_at`,
  because the first publish is the fact being recorded. Not a content change —
  no version bump, no history row, and `modified_at` stays where it was. There
  is no unpublish route, deliberately.

### Versioning

`recipes` holds the current state, `recipe_history` the superseded ones keyed
`(recipe_id, version)`. A save archives the outgoing state at its own version
number and moves the row to the next one, so the history holds versions 1..N-1
and the row is N. The version is stored **on the row** rather than derived from
the history, because recipes are a collection and deriving would mean a
correlated subquery per row in every listing.

Publishing does not create a version and `published` is not in the history
table: versions are about content, the latch is a separate fact about the row.

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

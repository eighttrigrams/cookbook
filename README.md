# Cookbook

A one-person store of **Recipes** — a title, a *useful-when* line, and a body —
written by the owner *and* by the agents he gives credentials to.

## What this is for

Cookbook is an **agentic memory store**. That decides more about it than the
schema does, and it inverts the house default, so it goes first:

> Every other plurama sibling treats an agent's write as something to be gated —
> a machine token is read-only until the owner switches recording mode on.
> **Cookbook deliberately does not.** A caller holding cookbook credentials
> writes freely, with no supervision and no toggle.

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

## Recipes

A Recipe has three fields, and the split between them is a retrieval index, not
a bandwidth optimisation:

- `title` and `useful_when` — what an agent scans to decide whether a Recipe is
  relevant;
- `description` — the body, fetched for exactly the one Recipe that turned out
  to be relevant.

So **lean is the default**, in the API and in the UI alike: a listing and a plain
`GET` carry no `description` key at all, `?detail=full` adds it, and expanding a
card in the browser goes and fetches it. Recipes are **fully versioned** — every
edit that changes something archives the outgoing state and bumps the version.

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

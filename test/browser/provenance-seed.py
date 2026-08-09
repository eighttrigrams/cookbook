#!/usr/bin/env python3
"""Seed the one Recipe the provenance checks need: a body written by both of them.

`recipe-page-checks.js` reads `SUBJECT` — a Recipe of the dev database — for
everything else it asserts, and could not read this: checks 7-10 need a body with a
line the API calls 1.00, a line it calls 0.00 **and** a line strictly between them,
and no Recipe anybody has been keeping is guaranteed to have all three. So this makes
one, named CHECK-PROV so that `cleanup.py` takes it back out with the rest.

Three versions, and the ladder is the point rather than the text:

  v1  machine   three lines, all the agent's
  v2  ui        the owner replaces the middle line with two of his own, and appends
                a closing note after a blank line
  v3  machine   the agent puts one line back inside the block he wrote

which the API then reads as four ranges — his closing note at 1.00, the agent's two
surviving lines at 0.00, and the block the agent landed inside at 0.67, because that
island holds and dilutes rather than splitting. That middle number is the reason this
fixture exists: it is what a check that the view does not flatten the spectrum into
two buckets has to have in front of it.

**v3 goes in as a proposal the owner approves, not as a save**, and that is not
ceremony either — it is the only way an agent can write a third version of a Recipe
the owner has touched. `PUT` from a machine token answers 202 and files it; approving
writes those fields as the next version, stamped `machine`. So this walks the app's
own rules to build its fixture rather than reaching past them into sqlite, and a
change that broke the approval path would break this script too, which is the right
place to find out.
"""
import json, sys, urllib.request, urllib.error

BASE = 'http://localhost:3170/api'
TITLE = 'CHECK-PROV a body written by both of them'

V1 = ("Run make stop, then make start.\n"
      "Check the lock file if that hangs.\n"
      "Ask a human if it still hangs.")

# **Both of the owner's versions end in a newline, and that is load-bearing.** The
# ranges keep a trailing empty line and `clojure.string/split-lines` throws it away,
# so a view that split the body that way would draw one row fewer than the answer it
# is tinting — at the end, silently, on the most ordinary body there is: one typed
# into a textarea. With the newline this fixture is 8 lines to the API and 7 to
# `split-lines`, which is what gives check 8 something to fail against.
V2 = ("Run make stop, then make start.\n"
      ".dev-server.lock names the environment that owns the server.\n"
      "stop.sh refuses a cross-env stop on purpose, so read it before forcing anything.\n"
      "Ask a human if it still hangs.\n"
      "\n"
      "Written up the third time I had to work this out again.\n")

V3 = ("Run make stop, then make start.\n"
      ".dev-server.lock names the environment that owns the server.\n"
      "If the port is held by a proxy on the host, restarting will not help.\n"
      "stop.sh refuses a cross-env stop on purpose, so read it before forcing anything.\n"
      "Ask a human if it still hangs.\n"
      "\n"
      "Written up the third time I had to work this out again.\n")


def req(path, data=None, method=None, token=None):
    body = json.dumps(data).encode() if data is not None else None
    r = urllib.request.Request(BASE + path, data=body,
                               method=method or ('POST' if data else 'GET'))
    r.add_header('Content-Type', 'application/json')
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, json.loads(resp.read() or b'null')
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b'null')


def main():
    token = req('/auth/login', {'username': 'machine-user', 'password': 'pw'})[1]['token']

    # v1 — the agent writes it. Unpublished, deliberately: publishing would take it
    # out of an agent's hands and v3 could never be filed.
    s, recipe = req('/recipes', {'title': TITLE,
                                 'useful_when': 'the dev server will not come back up',
                                 'description': V1}, token=token)
    if s != 201:
        print('v1 failed:', s, recipe); return 1
    rid = recipe['id']

    # v2 — the owner. Dev skips logins, so an unauthenticated call is his.
    s, _ = req(f'/recipes/{rid}', {'description': V2}, method='PUT')
    if s != 200:
        print('v2 failed:', s); return 1

    # v3 — the agent again, which is now a proposal rather than a save
    s, pending = req(f'/recipes/{rid}', {'description': V3}, method='PUT', token=token)
    if s != 202:
        print('expected 202 and a filed proposal, got', s, pending); return 1

    # and the owner approves it, which is what makes it version 3 stamped machine
    entry = next((e for e in req('/inbox')[1]
                  if e['recipe_id'] == rid and e['kind'] == 'proposed'), None)
    if not entry:
        print('no proposed entry in the inbox for', rid); return 1
    # `method` spelled out: `req` infers it from `data` being *truthy*, and an empty
    # body is falsy, so an inferred approve goes out as a GET and 404s.
    s, _ = req(f"/inbox/{entry['id']}/approve", {}, method='POST')
    if s != 200:
        print('approve failed:', s); return 1

    _, full = req(f'/recipes/{rid}?detail=full')
    ranges = (full.get('caution') or {}).get('ranges')
    print(f'CHECK-PROV recipe {rid}, version {full["version"]}')
    print('  url:    /recipe/%d' % rid)
    print('  ranges:', ranges)
    values = sorted({r['caution'] for r in ranges or []})
    print('  values:', values)
    if not (1.0 in values and 0.0 in values and any(0 < v < 1 for v in values)):
        print('  NOT the fixture the checks need — expected 0.0, 1.0 and one in between')
        return 1
    print('  seen by the checks as SUBJECT_MIXED — run cleanup.py to remove it')
    return 0


if __name__ == '__main__':
    sys.exit(main())

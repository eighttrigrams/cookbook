#!/usr/bin/env python3
"""Seed the dev cookbook with the proposals a check run needs.

Four plain ones for the checks that resolve a proposal (each check needs its own, so
that a run does not depend on the order the previous one left the queue in), a fifth
that nothing resolves, and three that carry a warning, because the row's Approve
behaves differently on those.

  CHECK-1..4   nothing to warn about — unpublished, base version is the current one
  CHECK-5      the same, and never answered: check 12 reads it as the case a row may
               still approve, and it runs after 7-10 have emptied 1 to 4
  CHECK-WP     published
  CHECK-WS     stale: the owner saved after the agent had proposed
  CHECK-WB     both

The owner writes v1 — dev skips logins, so an unauthenticated call is his — and that
is what makes the machine's next edit a *proposal* rather than a save: a Recipe whose
current version came from the ui is one an agent may not overwrite. The machine then
PUTs its rewrite and gets a 202 with a pending proposal against version 1.

For the stale cases the owner PUTs again afterwards, which bumps the Recipe past the
proposal's `base_version` and leaves the proposal pending — that is the whole of what
"stale" is. For the published ones he publishes.

And one entry of the **other** kind:

  CHECK-MOD    a `modified` entry — the agent's own Recipe, saved twice

**It is here because a check answers it, and answering is destructive.** The suite
used to borrow a `modified` row out of the dev database for check 5, which only *read*
it; 15 and 16 press its Seen button, and an entry marked Seen leaves the queue for
good. Borrowing one and consuming it would take something of his and leave the next
run with nothing to open — so the suite makes its own, and `cleanup.py` takes it back
out with everything else called CHECK-.

Making one takes the machine twice, and that is the rule this fixture is built out of
rather than around: a Recipe whose current version came from the ui is one an agent may
not overwrite, which is what turns the eight cases above into proposals. So here the
**machine** writes v1 — a `created` entry — and then PUTs again, which it may, because
the version it is overwriting is its own. That second save is the `modified` entry.

Every title starts with CHECK- so cleanup.py can find them again.
"""
import json, sys, urllib.request, urllib.error

BASE = 'http://localhost:3170/api'

# (name, publish?, stale?)
CASES = [('1', False, False), ('2', False, False), ('3', False, False), ('4', False, False),
         ('5', False, False), ('WP', True, False), ('WS', False, True), ('WB', True, True)]

LABELS = {(False, False): 'a Recipe with a proposal waiting on it',
          (True, False): 'a proposal with the published warning',
          (False, True): 'a proposal with the staleness warning',
          (True, True): 'a proposal with both warnings'}


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


def machine_token():
    """The machine's token, minted or borrowed.

    `machine-user` / `pw` is what a fresh dev database is seeded with, and a dev
    database is where passwords get rotated by hand — this one's had been, and the
    login answered a 401 with no `token` key, so the seed died on a `KeyError` with
    nothing wrong in the app. `recipe-page-checks.js`' `draftProvenance()` had already
    met that and takes a token as an argument; this does the same, and says which of
    the two routes it took so a run is on the record either way.

        ;; on :nrepl-port from config.edn
        (et.cb.auth/create-machine-token nil "machine-user")

        python3 test/browser/seed.py <the token>
    """
    if len(sys.argv) > 1:
        print('using the token given on the command line')
        return sys.argv[1]
    status, body = req('/auth/login', {'username': 'machine-user', 'password': 'pw'})
    if status != 200 or 'token' not in (body or {}):
        sys.exit('could not log in as machine-user / pw (status ' + str(status) + '). '
                 'The dev password has probably been rotated — mint a token on the '
                 'backend nREPL and pass it as an argument. See machine_token().')
    print('logged in as machine-user / pw')
    return body['token']


def main():
    token = machine_token()
    made = []
    for name, publish, stale in CASES:
        s, recipe = req('/recipes', {
            'title': f'CHECK-{name} {LABELS[(publish, stale)]}',
            'useful_when': f'check {name}: the queue holds a proposal against this',
            'description': ('The owner wrote this paragraph, and it is version 1.\n\n'
                            'A second paragraph, so a proposal has somewhere to change '
                            'something that is not the first line.\n'),
            'tags': '', 'scope_ids': []})
        assert s == 201, (s, recipe)
        rid = recipe['id']
        s, body = req(f'/recipes/{rid}', {
            'description': ('The owner wrote this paragraph, and it is version 1.\n\n'
                            f'An agent rewrote the second paragraph of check {name}, and '
                            'is waiting to be told whether that was wanted.\n')},
            method='PUT', token=token)
        assert s == 202, (s, body)
        if stale:
            s, body = req(f'/recipes/{rid}', {
                'description': ('The owner wrote this paragraph, and it is version 1.\n\n'
                                'And then the owner saved a second time, after the agent '
                                'had already proposed against version 1.\n')},
                method='PUT')
            assert s == 200, (s, body)
        if publish:
            # `method='POST'` spelt out: an empty body is falsy in python, so the
            # default `'POST' if data else 'GET'` would send a GET and take a 404.
            s, body = req(f'/recipes/{rid}/publish', {}, method='POST')
            assert s == 200, (s, body)
        made.append({'recipe_id': rid, 'title': recipe['title'],
                     'published': publish, 'stale': stale})

    # CHECK-MOD: the agent's own Recipe, saved twice. The first POST files a `created`
    # entry and the PUT a `modified` one — both as the machine, which is what makes the
    # second call a save rather than a proposal.
    s, recipe = req('/recipes', {
        'title': 'CHECK-MOD an agent wrote this and then changed it',
        'useful_when': 'the queue needs a modified entry the suite may answer',
        'description': ('An agent wrote this paragraph, and it is version 1.\n\n'
                        'A second paragraph, so the save below has somewhere to change '
                        'something that is not the first line.\n')}, token=token)
    assert s == 201, (s, recipe)
    rid = recipe['id']
    s, body = req(f'/recipes/{rid}', {
        'description': ('An agent wrote this paragraph, and it is version 1.\n\n'
                        'And then the same agent rewrote the second paragraph, which is '
                        'a save and not a proposal, because the version it replaced was '
                        'its own.\n')}, method='PUT', token=token)
    assert s == 200, (s, body)
    made.append({'recipe_id': rid, 'title': recipe['title'], 'kind': 'created + modified'})

    print(json.dumps(made, indent=1))


if __name__ == '__main__':
    sys.exit(main())

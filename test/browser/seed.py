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


def main():
    token = req('/auth/login', {'username': 'machine-user', 'password': 'pw'})[1]['token']
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
    print(json.dumps(made, indent=1))


if __name__ == '__main__':
    sys.exit(main())

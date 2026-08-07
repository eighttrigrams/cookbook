#!/usr/bin/env python3
"""Take every CHECK- Recipe seeded for a run back out of the dev database.

Straight sqlite rather than DELETE /api/recipes/:id, because the API's delete leaves a
`deleted` event in his queue — which is correct behaviour and exactly what a cleanup
must not add. The rows removed are only ever ones a run created: recipes whose title
starts with CHECK-, their events, their proposals, their history and their filing.
"""
import sqlite3, sys

DB = sys.argv[1] if len(sys.argv) > 1 else 'data/cookbook.db'
con = sqlite3.connect(DB)
ids = [r[0] for r in con.execute("select id from recipes where title like 'CHECK-%'")]
print('recipes:', ids)
for table, col in [('recipe_events', 'recipe_id'), ('recipe_proposals', 'recipe_id'),
                   ('recipe_history', 'recipe_id'), ('recipe_scopes', 'recipe_id')]:
    try:
        n = con.execute(f"delete from {table} where {col} in ({','.join('?' * len(ids))})",
                        ids).rowcount if ids else 0
        print(f'{table}: {n}')
    except sqlite3.OperationalError as e:
        print(f'{table}: skipped ({e})')
if ids:
    print('recipes deleted:', con.execute(
        f"delete from recipes where id in ({','.join('?' * len(ids))})", ids).rowcount)
con.commit()
print('unseen queue now:', con.execute(
    "select count(*) from recipe_events where seen = 0").fetchone()[0])
con.close()

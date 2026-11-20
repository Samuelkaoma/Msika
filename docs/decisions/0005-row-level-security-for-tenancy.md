# 0005: Row-level security keeps councils apart

Status: Accepted

## Context
Several councils will use one Msika system, and each must see only its own data. The usual approach
is to add `WHERE council_id = ?` to every query. That depends on every developer remembering it
every time, forever. One missed clause leaks one council's traders to another.

## Decision
Every table holding council data has a `council_id` column and a Postgres row-level security (RLS)
policy that compares it to `current_setting('app.council_id')`. Each request or job sets that value
inside its transaction (`SET LOCAL`). The application connects as `msika_app`, a role that owns no
tables and does not have `BYPASSRLS`. Migrations run as a separate `msika_owner` role. Jobs that work
across councils loop over councils and set the value for each.

## Consequences
- A forgotten `WHERE` returns nothing from other councils instead of leaking it. The database enforces
  the rule, not the code.
- Table owners and `BYPASSRLS` roles skip RLS, so the app must never connect as the owner. Tests
  check the role setup (M-04) and that every table with a `council_id` has a policy (M-08).
- Every connection must set `app.council_id` before querying. Forgetting it gives empty results,
  which is safe but confusing.
- Slightly more setup per table and some query overhead. Acceptable for the isolation it gives.

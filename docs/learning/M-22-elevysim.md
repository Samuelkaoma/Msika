# M-22: elevysim

Card: `M-22` · Branch: `feature/M-22-elevysim` · Depends on: M-04

## What we are building, in one paragraph

Councils collect market levy through eLevy, which we cannot reach yet, so we build a stand-in: elevysim. Each trader
has a prepaid levy account at their market. Every trading day one day's levy is taken from it; what the balance
cannot cover becomes arrears. Msika will top the account up from deposits (card M-24). The demo can then show two
traders side by side: one drifting into arrears, one who deposits through Msika and never does.

## New ideas in this lesson

- **Prepaid account with arrears**
  - **What it is:** a balance that charges come out of, and a debt (arrears) for what the balance could not pay.
    Never both at once: a top-up pays the debt first.
  - **Tiny example:** balance K2, levy K5: after the charge, balance K0 and arrears K3. A K10 top-up then gives
    arrears K0 and balance K7.

- **Once per day, by a unique key**
  - **What it is:** `unique (account_id, charge_date)` on the movements table. Running the charge twice for a day
    finds the first charge and does nothing.
  - **Why we need it here:** the scheduled charge, a demo button and a restart can all run it on the same day.

- **Self-invocation and transactions**
  - **What it is:** Spring adds `@Transactional` through a proxy around the bean. A method calling another method
    of the same object skips the proxy, so that call has no transaction (and `for update` locks nothing).
  - **Why we need it here:** `chargeAll` charges each account in its own transaction with a `TransactionTemplate`.

## Where it sits in the architecture

`platform/elevysim`, its own Spring Boot app and database (ARCHITECTURE section 6). Msika never imports it and
never reads its database (AGENTS.md rule 6); it will call the HTTP API through a `LevyAuthorityPort` (M-24).

| Endpoint | What it does |
|---|---|
| `POST /v1/levy-accounts` | open an account (council, market, trader, daily levy) |
| `GET /v1/levy-accounts/{id}` | balance, arrears, last charged, paid to |
| `GET /v1/levy-accounts?market=&trader=` | find one; `?council=` lists |
| `GET /v1/levy-accounts/{id}/movements` | every change |
| `POST /v1/levy-accounts/{id}/top-ups` | credit, with `Idempotency-Key` |
| `POST /v1/admin/run-daily-charge` | charge every account for a day (also scheduled at 06:00 Lusaka) |

## Step by step

1. Migration `V2__levy_accounts.sql`: `levy_account` (balance and arrears, never both) and append-only
   `levy_movement` with the once-per-day and once-per-key unique constraints.
2. `LevyService`: open, top-up (arrears first, idempotent), charge (locks the row), charge all, and "paid to".
3. `LevyController` and the daily schedule.

## How we know it works

`LevyApiTest`: a charge with enough balance; a charge into arrears (and the movements after each step); a top-up
clearing arrears in two steps; the same key twice is one top-up and another amount is 409; the charge runs once
per day; 20 top-ups and 20 charges at once lose nothing; validation; movements cannot be updated or deleted.

## Rebuild it yourself

1. Write `charge(balance, arrears, levy)` and `topUp(balance, arrears, amount)` as pure functions with tests.
2. Put them behind an API with a table that makes "once per day" impossible to break.
3. Prove with a concurrent test that a lock is needed: remove `for update` and watch it fail.

## Mistakes to avoid

- Letting a top-up go to the balance while arrears are owed.
- Charging by "has it run today?" in memory instead of a unique constraint.
- Calling a `@Transactional` method on `this` and expecting a transaction.

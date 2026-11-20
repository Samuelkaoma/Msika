# M-12: zamsim wallets and transfers

Card: `M-12` · Branch: `feature/M-12-zamsim-core` · Depends on: M-04

## What we are building, in one paragraph

`zamsim` is our stand-in for Zamtel Money, which we cannot reach yet. This card gives it a real core:
wallets, an agent's cash-in, wallet-to-wallet transfers, a transaction record with a status, and
statements. It has its **own double-entry ledger**, so money is never created or destroyed by accident.
It is a separate service with its own database and shares no code with Msika, so Msika can only ever
talk to it the way it would talk to the real thing.

## New ideas in this lesson

### Why a simulator, and why it must be strict
If the fake were sloppy, Msika would quietly come to rely on behaviour the real Zamtel will never have.
So `zamsim` follows the rules a real payments system must: balances never go negative, a repeated request
does not pay twice, and every movement is traceable. If Msika works against this, it is much closer to
working against the real thing.

### Double-entry bookkeeping
- **What it is:** every money movement is recorded as (at least) two lines that add up to **zero**: one
  wallet loses 450, another gains 450. Money is never "added" to one place without being taken from
  another.
- **Why:** it makes cheating and bugs visible. If the lines of any transaction do not add up to zero,
  something is wrong, and you can check that with one query.
- **Tiny example:** cash-in of K450 writes `-45000` to the agent's wallet and `+45000` to the trader's.

### The issuer wallet: where money enters and leaves
- Mobile money has to start somewhere: an agent hands real cash to the company and gets e-money back.
  We model that with one special **ISSUER** wallet that holds *minus* the total of all e-money.
- `fund` moves money issuer to a wallet (money enters); `withdraw` moves it back (money leaves).
- So the whole system, issuer included, always adds up to **exactly zero**, and the total of the real
  wallets only changes through fund and withdraw. Only the issuer may be negative.

### Money is a whole number of ngwee, again
`amountNgwee` is a `long`, never a decimal (AGENTS.md rule 1, applied here too). A real API would send
"450.00"; our adapter in Msika (M-14) will convert at the edge.

### Locking, so two payments cannot both spend the same money
- **The problem (a race):** a wallet holds K10. Two payments of K8 arrive at the same instant. Both read
  "K10, enough", both pay, and the wallet is at minus K6.
- **The fix:** `select ... for update` locks the wallet rows until the transaction ends, so the second
  payment waits, then sees K2 and fails cleanly.
- **Deadlocks:** if payment 1 locks wallet A then B, while payment 2 locks B then A, each waits for the
  other forever. The rule that prevents it: **always lock in the same order** (lowest id first). A test
  fires 200 transfers in opposite directions between two wallets and none deadlocks.

### A failed payment is still a record
Not enough money is not an HTTP error. The request was understood, so the answer is `202` with a
transaction whose `status` is `FAILED` and `failureReason` is `INSUFFICIENT_FUNDS`. It moves nothing and
writes no ledger lines. A wrong request (zero amount, same wallet, an agent paying from a trader's wallet)
is a real `400`.

### Idempotency (rule 5), done properly from the start
Every write needs an `Idempotency-Key`. The first thing a request does is *claim* the key with
`INSERT ... ON CONFLICT DO NOTHING`:
- It won: carry on and do the payment.
- It lost: the key is taken, so return the **first** result (even if that was a failure, and even if the
  wallet could afford it now), or a `409` if this is a *different* request using the same key.

We use `ON CONFLICT DO NOTHING` rather than catching a duplicate-key error because of the bug we found in
M-11: a duplicate-key error aborts the whole database transaction, so you cannot recover inside it. Eight
threads sending the same key give exactly one transaction, fifteen rounds in a row.

### The database enforces the rules too
Even if the Java code were wrong, Postgres refuses:
- a **negative balance** (`check (balance_ngwee >= 0 or type = 'ISSUER')`);
- a **second issuer** (a unique index);
- a transaction whose ledger lines do **not add up to zero**. This is a *deferred constraint trigger*: it
  runs when the transaction **commits**, once both lines exist, not after each line. A test inserts a
  lone debit line and sees "is unbalanced" at commit, and that nothing is left behind;
- any `UPDATE`, `DELETE` or `TRUNCATE` of a ledger line (append-only).

### Statements
`GET /v1/statements?wallet=&from=&to=` returns a wallet's lines oldest first, each with the signed amount,
the **running balance after it**, and the counterparty. Dates are Africa/Lusaka days. Msika's nightly
reconciliation (M-16) will compare this with its own ledger.

## Testing a money system: check the invariants from the raw ledger

The tests do not trust what the service says. They ask the database:

| Invariant | How it is checked |
|---|---|
| Nothing created or destroyed | the sum of all balances before and after, with and without the issuer |
| Every transaction balances | one query for any transaction whose lines do not sum to zero |
| Every balance is exactly its ledger | each wallet's balance equals the sum of its lines, and equals its last line's running balance |
| Never negative | every balance read back |

Then they are run hard:
- **1,000 random transfers** (seeded, so the same every run), chosen so many must fail for lack of money.
  We assert plenty succeeded **and** plenty failed, so both paths really ran.
- The same, from **8 threads at once**, in both directions.
- **50 threads spending one 1,000-ngwee balance in 100-ngwee payments**: exactly 10 succeed.
- 200 opposite transfers: no deadlock.

And we **sabotaged the service** to be sure the tests can fail: making it credit double failed 12 tests;
skipping the funds check failed 6 (and the database's own rule stopped negative balances too).

## Where it sits in the architecture

```
zamsim (separate Spring Boot app, port 8081, own database `zamsim`, own login)
   wallet/  Wallets (data), WalletService (money movements), WalletController (/v1/...), errors
   db       V2: wallet, transaction, idempotency_record, ledger_line + the database rules
```
It imports nothing from Msika (ArchUnit from M-05 already stops the reverse). Msika will reach it only
through `PaymentsPort` (M-14).

## Step by step

1. Branched from M-11, marked the card ◐.
2. **Migration `V2`:** the tables, the issuer row, and the database rules above.
3. **`WalletService`:** one careful path for every movement (claim key, lock in order, check funds, update
   balances, write two lines), with `cashIn`, `transfer`, `fund` and `withdraw` as thin wrappers.
4. **API** under `/v1`: wallets, cash-in, transfers, admin fund and withdraw, transactions, statements.
5. **Tests:** 12 API tests, 5 conservation and concurrency tests, 9 database-rule tests.
6. **Sabotaged the service** twice to prove the tests can fail.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/zamsim/.../db/migration/V2__wallets_and_transactions.sql` | New | Tables and the database's own rules |
| `platform/zamsim/.../wallet/*` | New | Domain, service, API, errors |
| `platform/zamsim/src/test/.../*` | New | `ZamsimIntegrationTest` base, `WalletApiTest`, `MoneyConservationTest`, `LedgerRulesTest`; health test moved onto the base |

## How we know it works

| Done item | Proof |
|---|---|
| Balances never go negative | `aBalanceCanNeverBeOverspent` (50 threads, exactly 10 succeed); `negativeBalanceRefused` (the database says no); every conservation test reads every balance back |
| Money is conserved across 1,000 random transfers | `thousandRandomTransfersConserveMoney` and `concurrentRandomTransfersConserveMoney` |
| A repeated key returns the first result | `repeatedKeyReturnsTheFirstResult`, `failedResultIsRepeated`, `sameKeyDifferentRequest` (409), `concurrentSameKey` (8 threads, 15 rounds) |

## Rebuild it yourself

1. Make `wallet` and `ledger_line` tables. Move money with two inserts that sum to zero.
2. Add a `check (balance >= 0)` and try to overspend.
3. Run two transfers from two sessions at once without `for update`, and watch the balance go wrong. Add
   `for update` and watch it fixed.
4. Make two sessions lock two rows in opposite orders and cause a deadlock. Fix it by ordering.
5. Add a deferred constraint trigger that checks a transaction's lines sum to zero.

## Mistakes to avoid

- **Reading a balance, then updating it later.** Lock it first (`for update`).
- **Locking in a different order in different code paths.** Deadlock.
- **Catching a duplicate-key error to recover.** The transaction is already aborted. Use `ON CONFLICT`.
- **Trusting only the service's own answers in a test.** Check the raw ledger.
- **A random test that is not seeded.** You cannot reproduce a failure.
- **A conservation test where nothing ever fails.** Make sure the failure path really runs.

## Not done here (on purpose)

- **Callbacks, delays, duplicate and out-of-order messages, request-to-pay**: M-13. Today every movement
  settles at once and answers `202` with its final status. M-13 makes the result arrive by callback.
- **Authentication.** zamsim has none: it is a simulator that only runs on a private network next to Msika.
- **The sim console and fault injection settings**: M-13 and M-20.

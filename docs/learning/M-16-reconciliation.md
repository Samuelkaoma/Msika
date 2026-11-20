# M-16: Nightly reconciliation

Card: `M-16` · Branch: `feature/M-16-reconciliation` · Depends on: M-15

## What we are building, in one paragraph

Msika learns about money from callbacks, and callbacks can be lost, late or wrong. Every night a job asks
the payments system (zamsim) for the statements of each council's trader and merchant wallets and
compares them with Msika's ledger, payment by payment. Every disagreement is listed: money the payments
system moved that Msika never recorded, an entry Msika has that the payments system never settled, and
the same payment with two different amounts. A council admin can read the list, or run it again by hand.

## New ideas in this lesson

- **Reconciliation**
  - **What it is:** comparing two independent records of the same events and listing where they differ.
    Banks do it every day between their books and the bank statement.
  - **Why we need it here:** M-14 proved Msika copes with faults, but proof in a test is not proof in
    production. A nightly comparison catches what slipped through, and a council can trust our figures
    because someone checks them against the payments system every night.
  - **Tiny example:**
    ```java
    Set<String> theirs = Set.of("tx1", "tx2");
    Set<String> ours = Set.of("tx2", "tx3");
    // missing in ours: tx1; missing in theirs: tx3
    ```

- **A pure function at the centre**
  - **What it is:** `Reconciler.compare(...)` is handed both sides and returns the differences. It reads
    no database and calls no network.
  - **Why we need it here:** every awkward case (midnight, late callbacks, someone else's payment) can be
    tested in milliseconds, and the service around it only fetches and stores.
  - **Tiny example:** `compare(day, expected, ourEntries, ourEntriesByKey)` gives a `Result`.

- **Classifying a statement line**
  - **What it is:** a wallet statement shows every movement on a wallet. Only some are Msika's business.
  - **Why we need it here:** the reconciler must use the same rules as the callback handler (M-14, M-15),
    or it would report "missing" payments that Msika rightly ignores, such as a trader paying a friend.

## Where it sits in the architecture

```
ReconciliationJob (02:30 Lusaka)       POST /api/v1/councils/{id}/reconciliation/{date}
            \                                      /
             ReconciliationService (msika-api)
             1. wallets of the council      (one transaction, RLS)
             2. PaymentsPort.statement()    (no transaction open: a network call)
             3. Reconciler.compare()        (msika-core, pure)
                + store the report          (one transaction, RLS)
```

- `PaymentsPort` gained `statement(wallet, from, to)` (AGENTS.md rule 6: outside systems only through
  ports). `StatementLine` is a core record, so the core never sees zamsim's JSON.
- The report tables `reconciliation_run` and `reconciliation_difference` have `council_id` and RLS
  (rule 4). They are a report about the ledger, not the ledger, so re-running a day replaces its report.
  The ledger itself is never touched (rule 2).

## Step by step

1. Added `StatementLine` and `PaymentsPort.statement` in `msika-core`, then implemented it in
   `ZamsimPaymentsAdapter` against zamsim's `GET /v1/statements`. A statement that hits zamsim's 5,000-line
   limit is refused, because a cut-short statement would make real payments look missing.
2. Wrote `Reconciler`: `expected(...)` turns statement lines into the payments Msika should have, with the
   same three rules the callbacks use; `compare(...)` lists the differences.
3. Decided what "the day" means. Msika's business date can move forward (a late callback for a sealed day
   lands on the next open day), so both sides are compared by **when the money moved** in the council's time
   zone. The statements are fetched for the day before and after too, so a payment stamped at 23:59:59 on one
   side and 00:00:01 on the other is not reported.
4. Migration `V9__reconciliation.sql` for the report, with checks that each side's amount is present
   exactly when that side has the payment.
5. `ReconciliationService` (fetch, compare, store), `ReconciliationJob` (nightly, per council, yesterday and
   the day before) and `ReconciliationController` (read, or run by hand).

## Files touched

| File | What changed | Why |
|---|---|---|
| `msika-core/.../payments/StatementLine.java` | new | a settled movement, in core terms |
| `msika-core/.../payments/PaymentsPort.java` | `statement(...)` | the port the reconciliation reads through |
| `msika-core/.../reconciliation/Reconciler.java`, `Difference.java` | new | the pure comparison |
| `msika-api/.../payments/ZamsimPaymentsAdapter.java` | `statement(...)` | calls zamsim |
| `msika-api/.../reconciliation/*` | new | service, nightly job, endpoints |
| `msika-api/.../db/migration/V9__reconciliation.sql` | new | the stored report, with RLS |

## How we know it works

- `ReconcilerTest` plants each kind of difference on its own, all three together, money that is not
  Msika's business, a late callback on a later business date, a payment across midnight (with and without
  an amount difference), reversals and declared sales, and a payment between two of the council's own
  wallets (counted once).
- `ReconciliationApiTest` plants all three kinds through the real database and API, checks an officer can
  read the report but not run it, another council cannot read it, a re-run replaces the report, a payments
  outage stores nothing and keeps the old report, and the nightly job reconciles yesterday.
- `DepositsUnderFaultsTest` (from M-14) now also reconciles its 500 deposits against the real zamsim
  statements, with every fault on, and expects no differences.

## Rebuild it yourself

1. Write a function that takes two lists of `(id, amount)` and returns three lists. Test it first.
2. Add "a payment can be stamped on the next day" and make your test pass without false alarms.
3. Store the result in a table with `council_id` and an RLS policy; make running it twice safe.

## Mistakes to avoid

- Comparing by business date. A late callback lands on the next open day and would show as missing.
- Keeping a database transaction open while calling the payments system.
- Reporting every movement on a wallet. A trader paying a friend is not Msika's business; the rules must be
  the same as the callback handler's.
- Trusting a statement that was cut off at the server's line limit.

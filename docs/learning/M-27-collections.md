# M-27: Collections and occupancy

Card: `M-27` · Branch: `feature/M-27-collections` · Depends on: M-26, M-24

## What we are building, in one paragraph

The dashboard's pages now show real figures: for each market, how many stalls are vacant, allocated, suspended and
active on a day; collections per day and market, with deposited cash, verified digital sales and declared sales in
separate columns next to levy charged and paid; who is in levy arrears; each agent's deposits by outcome alongside
the nightly reconciliation; and the daily seals with a button that checks them all against the ledger.

## New ideas in this lesson

- **Figures worked out, never stored**
  - `ReportService` runs one query per page against the ledger and the register. Nothing is cached, so a figure on
    the screen can always be checked with SQL, which is exactly what the test does.
- **Provenance in the interface** (AGENTS.md rule 3)
  - Deposited, digital and declared are never added into one "sales" number: they are not equally sure.
- **A reversal counts against what it reverses**
  - `coalesce(reversed.kind, entry.kind)` puts a reversed deposit's minus amount in the deposit column.
- **Verify as a read**
  - Checking seals changes nothing, so it is a `GET`, and officers (read-only) can do it.

## How we know it works

- `ReportApiTest` seeds two markets with stalls in every state, deposits, sales, a declared sale, a reversed deposit,
  a levy payment, levy charges over two days and an agent's deposits in every status. Each report is compared with a
  query written straight against the tables. The seal check passes after closing the days.
- `pages.test.tsx` (web): totals per column, the requested days, arrears, flags on disputes and reconciliation
  differences, and the verify button's two outcomes.
- By hand, against the real msika-api: Collections showed the demo's K450 deposit; "Verify all seals" checked 3 days.

## Mistakes to avoid

- Storing daily totals: they drift from the ledger and nobody notices.
- Seeding a test with dates before the council existed: Msika moves such entries to the council's first day.

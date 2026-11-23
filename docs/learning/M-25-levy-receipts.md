# M-25: Levy receipts and arrears

Card: `M-25` · Branch: `feature/M-25-levy-receipts` · Depends on: M-24

## What we are building, in one paragraph

A trader can now ask `LEVY` or `BAL` and hear what the levy authority itself says: the daily levy, the day their
levy is paid to, and any arrears. Every Sunday evening, a trader whose levy was paid through Msika that week gets one
receipt listing the days paid and the amount. The figures come from eLevy (elevysim in the demo), because that is
the record a council enforces.

## New ideas in this lesson

- **Read from the system of record**
  - Msika keeps its own levy charges, but the authority's account is what the council acts on. Replies show the
    authority's figures; the test also checks Msika's own unpaid days add up to the same arrears.
- **Lazy opening**
  - A trader's eLevy account is opened the first time it is needed (first sweep, or first `LEVY`/`BAL`), by
    `LevyAccounts.ensure`, outside any transaction, and linked in `levy_account_link`.
- **Once per week, by a unique key**
  - `levy_receipt (council, trader, week_ending)` is claimed before the text is queued, in one transaction, so a
    second run in the same week sends nothing.
- **Testing against the real simulator**
  - The test starts the real elevysim jar as a separate program on a free port (like M-14 does with zamsim) and
    reads its account over HTTP to compare.

## How we know it works

`LevyWeekApiTest` (real elevysim): a seeded week of K5 days, K450 deposited on day 2 and K12 on day 4.
- `repliesMatchElevysim`: eLevy shows arrears K15, paid to 4 Dec; `LEVY` replies "levy K5 a day at SWP-A-1. Paid to
  4 Dec. Arrears K15."; `BAL` replies "wallet K4,000.00. Levy paid to 4 Dec. Arrears K15."; Msika's unpaid days
  agree.
- `weeklyReceipt`: "levy paid 1 Dec to 4 Dec at SWP-A-1: K20. Arrears K15. Thank you.", once.
- `noReceiptAfterStop`.

## Mistakes to avoid

- Showing Msika's own arrears when the authority says something else: the trader would be told one thing and
  charged another. (Both are compared in the test.)
- Calling the authority inside a database transaction.
- Sending the receipt and then recording it: a crash in between would send it twice. Claim first, in the same
  transaction as the queued text.

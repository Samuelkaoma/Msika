# Market levy: rules and amounts

Card M-23 · Owners: MP (research), WM (figures) · Status: **real amounts NOT YET RESEARCHED**

## 1. What is still to do (people's work)

The real daily levy for Lusaka markets, per stall class, with sources, has not been found yet. This needs MP and WM:
council by-laws or fee schedules, a call or visit to the market office, and the date each amount started. Until it
is done, **every amount in this file and in the demo data is made up** and must not be shown as a real council's
levy.

For each market, record:

| Field | Example | Source to cite |
|---|---|---|
| Market and council | (name) | fee schedule or by-law, with its date |
| Stall classes | open table, lock-up shop, corner, kiosk | the same |
| Daily amount per class | K… | the same, or the market master's written statement |
| Trading days | every day, or closed on some days | market office |
| Who collects today, and how | cash, eLevy, both | market office |

## 2. How Msika applies a rule (built in card M-23)

- A rule is: market, stall class, amount per trading day, and the first date it applies. Rules are never edited; a
  change is a new rule with a later start (M-65 adds the approval and notice period).
- Every trading day, each stall held **when the day began** is charged the rule in force that day for its market
  and class. A stall allocated during the day is first charged the next day. Suspended stalls are not charged.
- A held stall with no rule is not charged; the run lists it so the council can add a rule.
- Running the charge again for a day changes nothing (one charge per stall per day).

## 3. Worked example (made-up amounts)

This table was worked out by hand and is checked by `LevyChargeApiTest.handWorkedTable`.

Rules for the market `LVY` (made up):

| Class | Amount | From |
|---|---|---|
| STANDARD | K5.00 | 1 Dec 2026 |
| CORNER | K8.00 | 1 Dec 2026 |
| TABLE | K2.00 | 1 Dec 2026 |
| STANDARD | K6.00 | 3 Dec 2026 |

Stalls:

| Stall | Class | Held |
|---|---|---|
| LVY-A-1 | STANDARD | since November |
| LVY-A-2 | CORNER | since November |
| LVY-A-3 | TABLE | since November |
| LVY-A-4 | STANDARD | vacant |
| LVY-A-5 | STANDARD | allocated on 2 Dec at 10:00 |
| LVY-A-6 | KIOSK | since November (no rule for KIOSK) |

Charges:

| Day | A-1 | A-2 | A-3 | A-4 | A-5 | A-6 | Total |
|---|---|---|---|---|---|---|---|
| 30 Nov | no rule yet | no rule yet | no rule yet | vacant | not held | no rule | K0 |
| 1 Dec | K5 | K8 | K2 | vacant | not held | no rule | K15 |
| 2 Dec | K5 | K8 | K2 | vacant | held from 10:00, so not yet | no rule | K15 |
| 3 Dec | K6 | K8 | K2 | vacant | K6 | no rule | K22 |

Three days: K52.

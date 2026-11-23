# M-23: Levy rules

Card: `M-23` · Branch: `feature/M-23-levy-rules` · Depends on: M-07

## What we are building, in one paragraph

Each council sets a daily levy per stall class in each market. Msika needs those rules, and a job that every
morning works out what every held stall owes for the day. This card builds both. Finding the real amounts for
Lusaka markets, with sources, is research for Mapalo and Wanipa; until then the amounts are made up and say so
(`docs/research/levy.md`).

## New ideas in this lesson

- **Rules with a start date, never edited**
  - **What it is:** a change in levy is a new rule with a later `valid_from`. The rule in force on a day is the
    latest one that started on or before it.
  - **Why we need it here:** the levy charged on any past day can always be worked out again, and a dispute about
    last month is settled by last month's rule, not today's.
- **"Held when the day began"**
  - **What it is:** a stall is charged to whoever held it at midnight (council time) of the trading day. Allocated
    at 10:00? First charged tomorrow.
  - **Why we need it here:** a rule people can check by hand, and that does not depend on when the job runs.
- **A worked example as the test**
  - **What it is:** the research note has a table of charges worked out by hand; the test sets up the same market and
    checks Msika gets the same numbers.

## Where it sits in the architecture

`msika-core` `zm.msika.core.levy`: `LevyRule` and `LevyPlan` (pure: held stalls + rules → charges and stalls with
no rule). `msika-api`: migration `V12` (`levy_rule`, append-only by trigger; `levy_charge`, one row per stall per
day; both with RLS), `LevyChargeService`, `LevyController` (rules, run, list) and the 05:15 job that charges today
and the two days before.

## How we know it works

- `LevyPlanTest`: the rule in force across a rule change; charges and stalls without a rule.
- `LevyChargeApiTest.handWorkedTable`: the table in `docs/research/levy.md` §3, day by day, K52 over three days.
- `idempotent`, `access` (officer 403, duplicate rule 409, other council 403), `rulesAreAppendOnly`.

## Mistakes to avoid

- Editing a rule's amount: past charges could no longer be explained.
- Charging by "allocated now" when re-running an old day: use the allocation at the start of that day.
- Presenting made-up levy amounts as a council's real ones.

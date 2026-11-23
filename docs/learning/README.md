# Msika teaching pack

This is a build-along course. Every time we build something for a card in `docs/ROADMAP.md`, a lesson
is added here that explains what we built, why, and how to rebuild it from nothing.

Audience: someone who wants to understand the system well enough to rebuild it, not just run it.

## How to use it

1. Start with [00-big-picture.md](00-big-picture.md) and [glossary.md](glossary.md).
2. Read lessons in order. They are named after the roadmap card: `M-01-...`, `M-02-...`.
3. Each lesson ends with a **Rebuild it yourself** section. Try it before you read our code.
4. To see what changed, open the lesson's **Files touched** list, then look at the same files in the
   Source Control panel in VS Code (or run `git diff`).

## Lesson index

| Card | Lesson | Status |
|---|---|---|
| none | [00-big-picture.md](00-big-picture.md) | written |
| M-01 | [M-01-monorepo-skeleton.md](M-01-monorepo-skeleton.md) | written |
| M-02 | [M-02-local-gate.md](M-02-local-gate.md) | written |
| M-03 | [M-03-ci.md](M-03-ci.md) | written |
| M-04 | [M-04-local-stack.md](M-04-local-stack.md) | written |
| M-05 | [M-05-architecture-tests.md](M-05-architecture-tests.md) | written |
| M-06 | [M-06-ledger.md](M-06-ledger.md) | written |
| M-07 | [M-07-councils-markets-stalls.md](M-07-councils-markets-stalls.md) | written |
| M-08 | [M-08-row-level-security.md](M-08-row-level-security.md) | written |
| M-09 | [M-09-council-users-and-security.md](M-09-council-users-and-security.md) | written |
| M-10 | [M-10-traders-agents-plates.md](M-10-traders-agents-plates.md) | written |
| M-11 | [M-11-recording-and-day-close.md](M-11-recording-and-day-close.md) | written |
| M-12 | [M-12-zamsim-wallets.md](M-12-zamsim-wallets.md) | written |
| M-13 | [M-13-callbacks-and-faults.md](M-13-callbacks-and-faults.md) | written |
| M-14 | [M-14-payments-adapter.md](M-14-payments-adapter.md) | written |
| M-15 | [M-15-deposits.md](M-15-deposits.md) | written |
| M-16 | [M-16-reconciliation.md](M-16-reconciliation.md) | written |
| M-17 | [M-17-sms-parser.md](M-17-sms-parser.md) | written |
| M-18 | [M-18-sms-inbound.md](M-18-sms-inbound.md) | written |
| M-19 | [M-19-sms-commands.md](M-19-sms-commands.md) | written |
| M-20 | [M-20-sim-console.md](M-20-sim-console.md) | written |
| M-22 | [M-22-elevysim.md](M-22-elevysim.md) | written |
| M-23 | [M-23-levy-rules.md](M-23-levy-rules.md) | written |
| M-24 | [M-24-levy-sweep.md](M-24-levy-sweep.md) | written |
| M-25 | [M-25-levy-receipts.md](M-25-levy-receipts.md) | written |
| M-26 | [M-26-dashboard-shell.md](M-26-dashboard-shell.md) | written |
| M-27 | [M-27-collections.md](M-27-collections.md) | written |
| M-48 | [M-48-market-plans.md](M-48-market-plans.md) | written |
| M-49 | [M-49-map-status.md](M-49-map-status.md) | written |
| M-50 | [M-50-market-map.md](M-50-market-map.md) | written |
| R-01 | [R-01-review-fixes.md](R-01-review-fixes.md) | written (fixes from the first review, not a card) |

Lessons are added as cards are built. Use [_template.md](_template.md) for new ones.

## Rules for this pack

- One lesson per card, written in the same branch as the code, so the lesson and code always match.
- Explain each new idea the first time it appears (an API, a migration, a port, row-level security),
  in plain words, with a small example, before showing our real code.
- No real names, phone numbers, keys or secrets (the repo is public).
- If a lesson and the code disagree, the code is right and the lesson is a bug. Fix the lesson.

# Decisions log

Short records of decisions that shape Msika and would be expensive to reverse. Each says what we
decided, why, and what it costs us. Written so a new team member can see the reasoning without
asking.

| No. | Decision | Status |
|---|---|---|
| [0001](0001-monorepo.md) | One repository (monorepo) | Accepted |
| [0002](0002-simulators-as-separate-services.md) | Simulators are separate services behind ports | Accepted |
| [0003](0003-money-as-long-ngwee.md) | Money is a `long` number of ngwee | Accepted |
| [0004](0004-append-only-ledger.md) | The ledger is append-only | Accepted |
| [0005](0005-row-level-security-for-tenancy.md) | Row-level security keeps councils apart | Accepted |

## How to add one

Copy the shape of an existing record, give it the next number, and add a row above. Do not edit an
accepted decision to change its meaning. Write a new record that supersedes it and mark the old one
"Superseded by NNNN".

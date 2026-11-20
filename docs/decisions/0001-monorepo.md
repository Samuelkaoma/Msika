# 0001: One repository (monorepo)

Status: Accepted

## Context
Msika is a Java platform (ledger, core, API), two simulators, two web apps, and later a Python ML
service and a mobile app. They change together: a new ledger field touches the API, the dashboard and
the tests. The team is five people and a single set of checks is easier to keep honest than several.

## Decision
Everything lives in one git repository, `Msika`, with `platform/` (Java), `apps/` (web), `ml/`,
`infra/`, `scripts/` and `docs/`. One pull request can change several parts, and one `scripts/check`
covers all of them.

## Consequences
- One change can be reviewed and merged as a unit. No version juggling between repositories.
- The repository is public, so nothing secret may ever be committed (AGENTS.md rule 9).
- Builds get slower as it grows. We accept that for now. The fast check keeps day-to-day work quick.
- The module boundaries inside the repository must be enforced by tests, because the repository
  itself no longer separates them. That is what the ArchUnit tests are for.

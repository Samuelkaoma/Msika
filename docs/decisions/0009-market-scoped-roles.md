# 0009: Several roles per user, scoped to markets, with the matrix in code

Status: Accepted (card M-61)

## Context
Each user had one role, and "may change things" meant "may change anything in the council". A market master should
act only in their own markets, an auditor should read everything and change nothing, and the person who finds an
offence should not handle the money from it (COUNCIL-ADMIN section 3).

## Decision
- A user holds any number of rows in `user_role`, each naming a role and, for market-scoped roles, the markets.
- What a role may do is a matrix in code (`Permissions`). The `role_permission` table is re-seeded from it on every
  start, so it is a copy to query and never the source.
- What an endpoint needs is one central list (`EndpointPermissions`), with how to find the market the action is in.
  An interceptor refuses any endpoint that is not listed, and a test fails the build for one.
- The separation rules are checked in the use case for a clear message, and again by a database trigger.
- Role rows are revoked, not deleted, so it stays known who could do what and when.
- Roles are read on every request and are not in the token, so a revoked role stops at once.

## Consequences
- Adding an endpoint means adding one line to the list.
- Reading is council-wide for every council role; only changes are scoped to markets.
- Some permission checks cost one small query to find the market a stall or deposit is in.
- The operator can still read council data until support access (M-70).

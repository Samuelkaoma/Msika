# M-61: Roles, market scopes and separation of duties

Card: `M-61` · Branch: `feature/M-61-roles` · Depends on: M-09

## What we are building, in one paragraph

Until now a council user had one role and it counted everywhere. Now a user can hold several roles, and some roles
only count in the markets they were given for: the market master of market A can allocate a stall there and gets 403
in market B. An auditor can read everything and change nothing. Nobody changes their own roles.

## New ideas in this lesson

- **Permission matrix**: a table of role against action, kept in code (`Permissions`). Roles never appear in
  controllers; controllers need a *permission*, and roles give permissions.
- **Scope**: the same role can be held for some markets and not others (`RoleGrant` carries the market ids).
- **Default deny with a central list**: `EndpointPermissions` says what each endpoint needs. `PermissionInterceptor`
  refuses anything not listed. A test asks Spring for every endpoint and compares it with the list, so a forgotten
  endpoint fails the build and not the council.
- **Interceptor**: code Spring runs after it has chosen the controller method and before it calls it. By then the
  path is parsed, so we know the stall or deposit and can look up its market.
- **Separation of duties**: enforcement officer and revenue officer cannot be one person. The use case says so
  politely; the trigger `user_role_rules` says so to anyone who goes around it.
- **Two locks on the same door**: every rule that matters is checked in Java (for a good message) and in Postgres
  (for certainty).
- **Roles are not in the token**: they are re-read on each request, so a revoked role stops working at once.

## The migration, step by step

`V17` creates `user_role` (with row-level security), copies each user's old role into it, and only then drops the
old column. The old roles were all council-wide, so no markets are needed. An operator has no
council and no `user_role` row: a user with no council is an operator.

## How we know it works

- `RolesApiTest`: market scope, the auditor, own roles, separation of duties, several roles adding up, every endpoint
  mapped, the matrix copied on start, and the migration run from V16 in a fresh database.
- `PermissionsTest` and `UserUseCasesTest`: the matrix and the rules without a database.

## Mistakes to avoid

- Checking a role name in a controller. Ask for a permission.
- Forgetting the market: a `PATH`, `STALL`, `PLAN`, `DEPOSIT` or `REGISTRATION` action needs one to be found.
- Editing `role_permission` by hand. It is overwritten at the next start.

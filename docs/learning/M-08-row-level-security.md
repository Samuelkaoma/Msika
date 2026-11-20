# M-08: Row-level security

Card: `M-08` · Branch: `feature/M-08-rls` · Depends on: M-07

## What we are building, in one paragraph

A guarantee that one council can never see another council's data, enforced by the database itself.
Until now every query had to remember `WHERE council_id = ?`. One forgotten clause would leak a
council's traders to another council. With row-level security (RLS), Postgres adds that filter to
every query automatically, and a query with no filter at all simply returns the caller's own rows.

## New ideas in this lesson

### Multi-tenancy
- **What it is:** one system serving several customers (*tenants*) whose data must stay apart. Here
  the tenant is a council.
- **Three common designs:** a separate database per tenant (strong but heavy), a separate schema per
  tenant, or shared tables with a `council_id` column. We use the third, which is simple to run, and
  we add RLS to make it safe.

### Row-level security (RLS)
- **What it is:** a Postgres feature. You attach a *policy* to a table, and Postgres applies it to
  every query on that table, whatever the SQL says.
- **Tiny example:**
  ```sql
  alter table stall enable row level security;
  create policy council_isolation on stall
      using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
      with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);
  ```
- **`using`** decides which existing rows you can see, update or delete.
  **`with check`** decides which rows you may insert, or what you may change a row into. Together
  they mean a council can neither read nor write another council's rows.

### Telling the database who is asking
- Postgres does not know our users. We tell it, per transaction, with a *session setting*:
  `select set_config('app.council_id', '<uuid>', true)`. The third argument `true` means "local to this
  transaction": when the transaction ends, the setting is gone.
- **Why local matters:** the app reuses a small pool of database connections. A setting that outlived
  its transaction would carry council A's identity into council B's next request. That is the classic
  way multi-tenant systems leak. One test runs 20 alternating requests to prove it does not happen.
- **Fail closed:** if the setting is missing, `current_setting(..., true)` returns nothing, the
  policy's comparison is never true, and the query returns **no rows**. Forgetting to set the council
  gives an empty answer, never someone else's data.

### Who RLS does not apply to
- The table's **owner**, and any role with **`BYPASSRLS`**, skip RLS. That is why M-04 made two roles:
  `msika_owner` runs migrations, and `msika_app` (what the app uses) owns nothing and has no
  `BYPASSRLS`. If the app connected as the owner, none of this would work, silently. `DatabaseRolesTest`
  guards that.
- We do not use `FORCE ROW LEVEL SECURITY`, which would also filter the owner. Migrations and seed
  scripts run as the owner and would silently touch no rows.

### `TenantContext`
- The Java side of it, in `msika-api`: `use(councilId)` sets the setting on the current transaction,
  `inCouncil(councilId, work)` opens a transaction and runs the work as that council, and
  `forEachCouncil(job)` runs a scheduled job once per council, each with only that council visible.
  There is deliberately no way to see every council's rows at once.
- `use` refuses to run outside a transaction, because the setting would only last one statement and
  the next query would quietly see nothing.

## Where it sits in the architecture

```
request ---> CouncilAdminService (@Transactional)
                 tenant.use(councilId)   -> set_config('app.council_id', ..., true)
                 use case -> JDBC repository -> SQL ...
                                              Postgres adds:  AND council_id = current council
```
The rows are filtered twice on purpose: the repositories still say `where council_id = :councilId`,
and RLS enforces it again underneath. The application filter is for clarity; RLS is the safety net.

The council comes from the URL for now. In M-09 it comes from the signed-in user.

## Step by step

1. Branched from M-07, marked the card ◐.
2. **Migration `V3__row_level_security.sql`:** enable RLS and a policy on `market`, `trader`, `stall`
   and `stall_allocation`. The `council` table has no `council_id` (it is the tenant) and no policy.
3. **`TenantContext`**, plus `listIds()` on the council repository so jobs can loop over councils.
4. **`CouncilAdminService`** calls `tenant.use(councilId)` first in every method.
5. **The M-07 tests that touched tables directly** now run `inCouncil(...)`, like the app does. They
   broke immediately when RLS came on, which is the feature working.
6. **`RowLevelSecurityTest`**, ten tests, all with queries that have no council filter.
7. **Proved the tests can fail.** We planted a migration that created a table with a `council_id`
   and no policy and switched RLS off on `stall`. Six tests failed, including the coverage test. We
   removed it and they passed again.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-api/.../db/migration/V3__row_level_security.sql` | New | The policies |
| `platform/msika-api/.../tenancy/TenantContext.java` | New | Sets the council per transaction; per-council jobs |
| `platform/msika-api/.../admin/CouncilAdminService.java` | Sets the council in every method | Use RLS |
| `platform/msika-core/.../CouncilRepository.java` (+ JDBC adapter, fake) | `listIds()` | Jobs loop over councils |
| `platform/msika-api/src/test/.../tenancy/RowLevelSecurityTest.java` | New | Proof |
| `platform/msika-api/src/test/.../admin/CouncilAdminApiTest.java` | Direct DB access now sets the council | RLS applies to tests too |

## How we know it works

| Done item | Proof |
|---|---|
| Council A's user sees none of council B's rows | `userOfCouncilASeesOnlyCouncilA` (no `WHERE`, all four tables) and `joinsAreFilteredToo` |
| A council A job sees none of council B's rows | `jobsSeeOneCouncilAtATime` via `forEachCouncil` |
| A hand-written query without `WHERE` sees none of B's rows | Every query in the test is written without a filter |
| A test fails if any table with `council_id` has no policy | `everyCouncilTableHasAPolicy`. Checked it can fail with a planted table |
| (extra) no council set means no rows; nothing leaks between pooled connections; cannot insert or move rows into another council; `UPDATE`/`DELETE` with no `WHERE` is confined | `noContextSeesNothing`, `contextDoesNotLeakBetweenTransactions`, `cannotInsertIntoAnotherCouncil`, `cannotUpdateARowIntoAnotherCouncil`, `writesAreConfinedToTheCouncil` |

## Rebuild it yourself

1. Make a table with a `council_id`, two rows for two councils, and `select *` as the app role. See both.
2. Enable RLS and add a policy. Select again. Now you see none.
3. In a transaction run `select set_config('app.council_id', '<uuid>', true)` and select again.
4. Try to insert a row for the other council and read the error.
5. Connect as the table owner and notice RLS does not apply. That is why we have two roles.

## Mistakes to avoid

- **Connecting the app as the table owner or a `BYPASSRLS` role.** RLS silently does nothing.
- **Setting the council outside a transaction, or with a non-local setting.** It leaks across pooled
  connections or vanishes after one statement.
- **Adding a table with a `council_id` and forgetting the policy.** The coverage test fails the build.
- **Editing a migration to add a policy later.** Add a new migration (`V4`, ...).
- **Thinking RLS replaces access control.** It separates councils. Who within a council may do what is
  the sign-in and roles work in M-09.

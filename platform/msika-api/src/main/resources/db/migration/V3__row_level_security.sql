-- Row-level security for every table that holds council data (card M-08, AGENTS.md rule 4).
--
-- A row is visible and writable only when its council_id equals the council the current transaction
-- was opened for. The application sets that with:
--     select set_config('app.council_id', '<uuid>', true)      -- true = local to this transaction
-- (see TenantContext). If it is not set, current_setting returns NULL or an empty string, nullif
-- turns the empty string into NULL, and "council_id = NULL" is never true, so NO rows are visible.
-- Forgetting the setting returns nothing; it never returns another council's rows.
--
-- `using` decides which existing rows a query can see, update or delete. `with check` decides which
-- rows may be inserted or what an update may change a row into, so one council cannot write a row
-- into another council.
--
-- The `council` table has no council_id column (it is the tenant itself) and is managed by
-- operators, so it has no policy. ARCHITECTURE.md section 5 says the same.
--
-- Row-level security is not applied to a table's owner or to roles with BYPASSRLS. The app connects
-- as msika_app, which is neither (checked by DatabaseRolesTest). We deliberately do not use FORCE
-- ROW LEVEL SECURITY: migrations and seed scripts run as the owner and would otherwise silently
-- touch no rows.

alter table market           enable row level security;
alter table trader           enable row level security;
alter table stall            enable row level security;
alter table stall_allocation enable row level security;

create policy council_isolation on market
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

create policy council_isolation on trader
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

create policy council_isolation on stall
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

create policy council_isolation on stall_allocation
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

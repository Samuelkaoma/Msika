-- The ledger and the daily seals (card M-11).
--
-- The ledger only ever grows (AGENTS.md rule 2). Three layers keep it that way:
--   1. only the RecordEntry use case writes it;
--   2. the database refuses UPDATE, DELETE and TRUNCATE (triggers, and no privilege for the app role),
--      and refuses a new entry in a day that is already sealed;
--   3. every day is sealed with a signed Merkle root chained to the previous day, so a change made
--      by someone who gets round layers 1 and 2 is still detected by `scripts/verify-seal`.

create table ledger_entry (
    -- Assigned by the database, in the order entries are recorded. The seal hashes a day's entries in
    -- this order, and a reversal points at the id of the entry it cancels.
    id                 bigint generated always as identity primary key,
    council_id         uuid        not null references council (id),
    stall_id           uuid        not null,
    kind               text        not null check (kind in (
                           'DEPOSIT', 'MERCHANT_SALE', 'SUPPLIER_PAYMENT', 'DECLARED_SALE',
                           'LEVY_PAYMENT', 'LOAN_REPAYMENT', 'REVERSAL')),
    provenance         text        not null check (provenance in ('VERIFIED', 'DEPOSITED', 'DECLARED')),
    amount_ngwee       bigint      not null,
    business_date      date        not null,
    occurred_at        timestamptz not null,
    idempotency_key    text        not null check (length(idempotency_key) between 1 and 200),
    reverses_entry_id  bigint,

    -- The same input processed twice is one entry (AGENTS.md rule 5).
    unique (council_id, idempotency_key),
    unique (id, council_id),

    foreign key (stall_id, council_id) references stall (id, council_id),
    foreign key (reverses_entry_id, council_id) references ledger_entry (id, council_id),

    -- Mirror of LedgerEntry's own rules, so a bug elsewhere cannot store an impossible entry.
    check ((kind = 'REVERSAL') = (reverses_entry_id is not null)),
    check (case when kind = 'REVERSAL' then amount_ngwee < 0 else amount_ngwee > 0 end),
    check (case kind
               when 'DEPOSIT'          then provenance = 'DEPOSITED'
               when 'MERCHANT_SALE'    then provenance = 'VERIFIED'
               when 'SUPPLIER_PAYMENT' then provenance = 'VERIFIED'
               when 'DECLARED_SALE'    then provenance = 'DECLARED'
               when 'LEVY_PAYMENT'     then provenance = 'VERIFIED'
               when 'LOAN_REPAYMENT'   then provenance = 'VERIFIED'
               else true
           end)
);

-- An entry can be reversed at most once.
create unique index ledger_entry_reversed_once on ledger_entry (reverses_entry_id)
    where reverses_entry_id is not null;

create index ledger_entry_day_idx on ledger_entry (council_id, business_date, id);

create table daily_seal (
    council_id        uuid        not null references council (id),
    business_date     date        not null,
    entry_count       integer     not null check (entry_count >= 0),
    merkle_root       bytea       not null check (length(merkle_root) = 32),
    prev_seal_hash    bytea       not null check (length(prev_seal_hash) = 32),
    algorithm         text        not null,
    signature         bytea       not null,
    key_id            text        not null,
    sealed_at         timestamptz not null,
    primary key (council_id, business_date)
);

alter table ledger_entry enable row level security;
alter table daily_seal   enable row level security;

create policy council_isolation on ledger_entry
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

create policy council_isolation on daily_seal
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- Layer 2a: no privilege to change history.
revoke update, delete, truncate on ledger_entry from msika_app;
revoke update, delete, truncate on daily_seal   from msika_app;

-- Layer 2b: triggers, which also stop the table's owner. (A superuser can still switch a trigger off.
-- That is exactly what layer 3, the seal, exists to catch.)
create function refuse_ledger_change() returns trigger
language plpgsql as $$
begin
    raise exception '% is append-only: % is not allowed', tg_table_name, tg_op
        using errcode = 'integrity_constraint_violation';
end
$$;

create trigger ledger_entry_append_only before update or delete on ledger_entry
    for each row execute function refuse_ledger_change();
create trigger ledger_entry_no_truncate before truncate on ledger_entry
    for each statement execute function refuse_ledger_change();
create trigger daily_seal_append_only before update or delete on daily_seal
    for each row execute function refuse_ledger_change();
create trigger daily_seal_no_truncate before truncate on daily_seal
    for each statement execute function refuse_ledger_change();

-- A sealed day is closed: no entry may be added to a day that is sealed, or to any earlier day.
-- RecordEntry moves such an entry to the next open day before it gets here; this is the backstop.
create function refuse_entry_in_sealed_day() returns trigger
language plpgsql as $$
begin
    if exists (select 1 from daily_seal s
               where s.council_id = new.council_id and s.business_date >= new.business_date) then
        raise exception 'business date % is already sealed for this council', new.business_date
            using errcode = 'integrity_constraint_violation';
    end if;
    return new;
end
$$;

create trigger ledger_entry_sealed_day before insert on ledger_entry
    for each row execute function refuse_entry_in_sealed_day();

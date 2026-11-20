-- What the SMS commands need (card M-19).

-- REG SOW-B-114: a person asks to be registered at a stall. The council approves or rejects it; the trader then
-- accepts the terms with YES. (Card M-64 moves these requests into the approvals inbox.)
create table registration_request (
    id             uuid        primary key,
    council_id     uuid        not null references council (id),
    stall_id       uuid        not null,
    msisdn         text        not null check (msisdn ~ '^\+260[79][0-9]{8}$'),
    status         text        not null check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    trader_id      uuid,
    created_at     timestamptz not null,
    decided_at     timestamptz,
    decided_by     text        check (length(decided_by) <= 100),
    reason         text        check (length(reason) <= 200),

    foreign key (stall_id, council_id) references stall (id, council_id),
    foreign key (trader_id, council_id) references trader (id, council_id),
    check ((status = 'PENDING') = (decided_at is null)),
    check (status <> 'APPROVED' or trader_id is not null)
);

-- Sending REG twice for the same stall is one request (AGENTS.md rule 5).
create unique index registration_request_pending on registration_request (council_id, stall_id, msisdn)
    where status = 'PENDING';

alter table registration_request enable row level security;

create policy council_isolation on registration_request
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- STOP and START: whether the trader wants the non-essential messages (daily summaries, weekly receipts).
alter table trader add column daily_summaries boolean not null default true;

-- SALE 120: the reference in the reply, unique per council per day like a deposit's.
create table declared_sale (
    ledger_entry_id  bigint      primary key,
    council_id       uuid        not null references council (id),
    business_date    date        not null,
    reference        text        not null check (reference ~ '^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$'),
    foreign key (ledger_entry_id, council_id) references ledger_entry (id, council_id),
    unique (council_id, business_date, reference)
);

alter table declared_sale enable row level security;

create policy council_isolation on declared_sale
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- REG from a number nobody has registered names a stall but not a council. Like sms_find_sender (V10), this narrow
-- function returns only which councils have a stall with ONE code the caller already has.
create function sms_find_stall(p_code text)
returns table (council_id uuid, stall_id uuid)
language sql stable security definer set search_path = public
as $$
    select s.council_id, s.id from stall s where s.code = p_code
$$;

revoke all on function sms_find_stall(text) from public;
grant execute on function sms_find_stall(text) to msika_app;

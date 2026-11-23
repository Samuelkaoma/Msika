-- Levy rules and the daily levy charge (card M-23, ARCHITECTURE.md section 4.3).

-- What a stall of one class in one market pays per trading day, from a date on. Rules are never edited: a change is
-- a new rule with a later start, so the levy of any past day can be worked out again.
create table levy_rule (
    id             uuid        primary key,
    council_id     uuid        not null references council (id),
    market_id      uuid        not null,
    stall_class    text        not null check (stall_class ~ '^[A-Z0-9_]{1,20}$'),
    amount_ngwee   bigint      not null check (amount_ngwee > 0 and amount_ngwee <= 10000000),
    valid_from     date        not null,
    created_at     timestamptz not null,
    created_by     text        not null check (length(created_by) between 1 and 100),
    foreign key (market_id, council_id) references market (id, council_id),
    unique (council_id, market_id, stall_class, valid_from)
);

create trigger levy_rule_append_only before update or delete on levy_rule
    for each row execute function refuse_ledger_change();

-- One day's levy owed by one stall: charged to whoever held the stall when the day began. One row per stall per
-- day, so running the charge again changes nothing. M-24 links each charge to the payment that settles it.
create table levy_charge (
    id                         uuid        primary key,
    council_id                 uuid        not null references council (id),
    stall_id                   uuid        not null,
    trader_id                  uuid        not null,
    business_date              date        not null,
    amount_ngwee               bigint      not null check (amount_ngwee > 0),
    rule_id                    uuid        not null references levy_rule (id),
    settled_by_instruction_id  uuid,
    created_at                 timestamptz not null,
    foreign key (stall_id, council_id) references stall (id, council_id),
    foreign key (trader_id, council_id) references trader (id, council_id),
    unique (council_id, stall_id, business_date)
);

create index levy_charge_day_idx on levy_charge (council_id, business_date);
create index levy_charge_trader_idx on levy_charge (council_id, trader_id, business_date);

alter table levy_rule   enable row level security;
alter table levy_charge enable row level security;

create policy council_isolation on levy_rule
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

create policy council_isolation on levy_charge
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

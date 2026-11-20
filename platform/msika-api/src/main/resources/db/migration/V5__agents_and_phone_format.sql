-- Deposit agents, and a database check that every stored phone number is E.164 (card M-10).

-- A Zambian mobile number in E.164 form: +260, then nine digits starting with 7 or 9.
-- The application normalises numbers before storing them (PhoneNumber.parse); this constraint is the
-- second line of defence, so a bug elsewhere cannot store "0977123739" or a number from abroad.
alter table trader
    add constraint trader_msisdn_e164 check (msisdn ~ '^\+260[79][0-9]{8}$');

create table agent (
    id            uuid primary key,
    council_id    uuid        not null references council (id),
    msisdn        text        not null check (msisdn ~ '^\+260[79][0-9]{8}$'),
    display_name  text        not null,
    active        boolean     not null default true,
    created_at    timestamptz not null,
    unique (council_id, msisdn),
    unique (id, council_id)
);

alter table agent enable row level security;

create policy council_isolation on agent
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

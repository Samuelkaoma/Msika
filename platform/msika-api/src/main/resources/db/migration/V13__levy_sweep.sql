-- The levy sweep (card M-24, ARCHITECTURE.md section 4.3): when a deposit is confirmed, the trader's unpaid levy is
-- paid from their wallet to the council's collection wallet, and the levy authority (eLevy, simulated by elevysim)
-- is told.

-- A payment request can now be a levy payment.
alter table payment_request drop constraint payment_request_purpose_check;
alter table payment_request add constraint payment_request_purpose_check check (purpose in ('DEPOSIT', 'LEVY'));

-- The council's collection wallet: one per council, with no stall and no agent.
alter table payments_wallet drop constraint payments_wallet_check;
alter table payments_wallet drop constraint payments_wallet_role_check;
alter table payments_wallet add constraint payments_wallet_role_check
    check (role in ('TRADER', 'MERCHANT', 'AGENT', 'SUPPLIER', 'COUNCIL'));
alter table payments_wallet add constraint payments_wallet_owner_check check (case role
    when 'TRADER'   then stall_id is not null and agent_id is null
    when 'MERCHANT' then stall_id is not null and agent_id is null
    when 'AGENT'    then agent_id is not null and stall_id is null
    else stall_id is null and agent_id is null
end);
create unique index payments_wallet_council on payments_wallet (council_id) where role = 'COUNCIL';

-- One sweep: the levy days it pays, the transfer that pays them, and the top-up that tells the authority.
--   PENDING  saved with the confirmed deposit; the transfer has not been sent yet
--   SENT     the transfer was sent; its outcome comes by callback
--   MOVED    the money moved (LEVY_PAYMENT is on the ledger); the authority has not been told yet
--   DONE     the authority credited the trader's levy account
--   FAILED   the transfer did not happen; the levy days are unpaid again
create table payment_instruction (
    id                  uuid        primary key,
    council_id          uuid        not null references council (id),
    purpose             text        not null check (purpose in ('LEVY')),
    -- The deposit that triggered it. One sweep per deposit, however often the confirmation arrives.
    deposit_id          uuid        not null unique,
    stall_id            uuid        not null,
    trader_id           uuid        not null,
    payment_request_id  uuid        not null unique references payment_request (id),
    amount_ngwee        bigint      not null check (amount_ngwee > 0),
    status              text        not null check (status in ('PENDING', 'SENT', 'MOVED', 'DONE', 'FAILED')),
    attempts            integer     not null default 0,
    last_error          text        check (length(last_error) <= 300),
    levy_account_id     text,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    foreign key (deposit_id, council_id) references deposit (id, council_id),
    foreign key (stall_id, council_id) references stall (id, council_id),
    foreign key (trader_id, council_id) references trader (id, council_id),
    unique (id, council_id)
);

create index payment_instruction_open_idx on payment_instruction (council_id, created_at)
    where status in ('PENDING', 'SENT', 'MOVED');

alter table levy_charge add constraint levy_charge_instruction_fk
    foreign key (settled_by_instruction_id, council_id) references payment_instruction (id, council_id);

-- The authority's account for each trader at each market, once opened.
create table levy_account_link (
    council_id   uuid        not null references council (id),
    trader_id    uuid        not null,
    market_id    uuid        not null,
    account_id   text        not null check (length(account_id) between 1 and 100),
    created_at   timestamptz not null,
    primary key (council_id, trader_id, market_id),
    foreign key (trader_id, council_id) references trader (id, council_id),
    foreign key (market_id, council_id) references market (id, council_id)
);

alter table payment_instruction enable row level security;
alter table levy_account_link   enable row level security;

create policy council_isolation on payment_instruction
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

create policy council_isolation on levy_account_link
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- Deposits and the registry of payments-system wallets (card M-15).

-- ---------------------------------------------------------------------------------------------------------
-- Which wallet in the payments system belongs to which stall, agent or supplier.
-- Wallet ids are the payments system's own and are never interpreted. One wallet belongs to one council.
-- ---------------------------------------------------------------------------------------------------------
create table payments_wallet (
    wallet_id   text        primary key check (length(wallet_id) between 1 and 100),
    council_id  uuid        not null references council (id),
    role        text        not null check (role in ('TRADER', 'MERCHANT', 'AGENT', 'SUPPLIER')),
    stall_id    uuid,
    agent_id    uuid,
    created_at  timestamptz not null default now(),

    foreign key (stall_id, council_id) references stall (id, council_id),
    foreign key (agent_id, council_id) references agent (id, council_id),

    -- A wallet has the owner its role says, and no other.
    check (case role
               when 'TRADER'   then stall_id is not null and agent_id is null
               when 'MERCHANT' then stall_id is not null and agent_id is null
               when 'AGENT'    then agent_id is not null and stall_id is null
               else stall_id is null and agent_id is null
           end)
);

-- A stall has at most one wallet in each role, and an agent at most one wallet.
create unique index payments_wallet_stall_role on payments_wallet (council_id, stall_id, role) where stall_id is not null;
create unique index payments_wallet_agent on payments_wallet (council_id, agent_id) where agent_id is not null;

alter table payments_wallet enable row level security;

create policy council_isolation on payments_wallet
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- A payment callback names wallets and not councils, so Msika has to learn whose payment it is BEFORE it can
-- open that council's data. This narrow function is the only way across the row-level security wall: it
-- returns the owner of ONE wallet whose id the caller already knows, and nothing else (the same idea as the
-- sign-in lookups in V4).
create function payments_find_wallet(p_wallet_id text)
returns table (wallet_id text, council_id uuid, role text, stall_id uuid, agent_id uuid)
language sql stable security definer set search_path = public
as $$
    select w.wallet_id, w.council_id, w.role, w.stall_id, w.agent_id
    from payments_wallet w
    where w.wallet_id = p_wallet_id
$$;

revoke all on function payments_find_wallet(text) from public;
grant execute on function payments_find_wallet(text) to msika_app;

-- ---------------------------------------------------------------------------------------------------------
-- Deposits: an agent took a trader's closing-time cash and asked for it to be put in the trader's wallet.
-- ---------------------------------------------------------------------------------------------------------
create table deposit (
    id                  uuid        primary key,
    council_id          uuid        not null references council (id),
    stall_id            uuid        not null,
    agent_id            uuid        not null,
    amount_ngwee        bigint      not null check (amount_ngwee > 0),
    status              text        not null check (status in ('PENDING', 'CONFIRMED', 'FAILED', 'CANCELLED', 'DISPUTED')),
    reference           text        not null check (reference ~ '^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$'),
    business_date       date        not null,
    payment_request_id  uuid        not null,
    ledger_entry_id     bigint,
    status_reason       text        check (length(status_reason) <= 200),
    idempotency_key     text        not null check (length(idempotency_key) between 1 and 100),
    created_at          timestamptz not null,
    updated_at          timestamptz not null,

    foreign key (stall_id, council_id) references stall (id, council_id),
    foreign key (agent_id, council_id) references agent (id, council_id),
    foreign key (payment_request_id) references payment_request (id),
    foreign key (ledger_entry_id, council_id) references ledger_entry (id, council_id),

    -- The reference is how a trader names a deposit by SMS, so it is unique per council per day.
    unique (council_id, business_date, reference),
    -- An agent sending the same command twice makes one deposit.
    unique (council_id, agent_id, idempotency_key),
    -- A payment request belongs to one deposit.
    unique (payment_request_id),
    unique (id, council_id),

    -- A confirmed deposit has its ledger entry; a failed or pending one never does.
    check (status not in ('PENDING', 'FAILED') or ledger_entry_id is null),
    check (status <> 'CONFIRMED' or ledger_entry_id is not null),
    -- FAILED, CANCELLED and DISPUTED say why.
    check (status not in ('FAILED', 'CANCELLED', 'DISPUTED') or status_reason is not null)
);

create index deposit_status_idx on deposit (council_id, status, created_at desc);

alter table deposit enable row level security;

create policy council_isolation on deposit
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- Councils, markets, stalls, traders and stall allocations (card M-07).
--
-- Every table except `council` has a council_id. Row-level security policies for them arrive in
-- the next card (M-08); until then they must not be used in production.
--
-- Each council-owned table also has UNIQUE (id, council_id), and the tables that point at it use a
-- composite foreign key (x_id, council_id). That makes it impossible for the database to hold, for
-- example, a stall in council A whose market belongs to council B.

create table council (
    id          uuid primary key,
    name        text        not null,
    timezone    text        not null,
    created_at  timestamptz not null
);

create table market (
    id              uuid primary key,
    council_id      uuid        not null references council (id),
    code            text        not null,
    name            text        not null,
    location        text        not null default '',
    day_close_time  time        not null,
    created_at      timestamptz not null,
    unique (council_id, code),
    unique (id, council_id)
);

-- Minimal for now: traders are registered in card M-10, which adds what it needs.
create table trader (
    id                     uuid primary key,
    council_id             uuid        not null references council (id),
    msisdn                 text        not null,
    display_name           text        not null,
    language               text        not null default 'en',
    consent_levy_sweep_at  timestamptz,
    consent_data_at        timestamptz,
    created_at             timestamptz not null default now(),
    unique (council_id, msisdn),
    unique (id, council_id)
);

create table stall (
    id           uuid primary key,
    council_id   uuid        not null references council (id),
    market_id    uuid        not null,
    code         text        not null,
    stall_class  text        not null,
    status       text        not null check (status in ('VACANT', 'ALLOCATED', 'SUSPENDED')),
    qr_token     text        not null unique,
    created_at   timestamptz not null default now(),
    unique (council_id, code),
    unique (id, council_id),
    foreign key (market_id, council_id) references market (id, council_id)
);

create index stall_market_idx on stall (council_id, market_id);

create table stall_allocation (
    id          uuid primary key,
    council_id  uuid        not null references council (id),
    stall_id    uuid        not null,
    trader_id   uuid        not null,
    valid_from  timestamptz not null,
    valid_to    timestamptz,
    check (valid_to is null or valid_to >= valid_from),
    foreign key (stall_id, council_id)  references stall (id, council_id),
    foreign key (trader_id, council_id) references trader (id, council_id)
);

-- A stall has at most one active allocation at a time, whatever the application does.
create unique index stall_allocation_one_active on stall_allocation (stall_id) where valid_to is null;
create index stall_allocation_trader_idx on stall_allocation (council_id, trader_id);

-- Simulated eLevy (card M-22): a prepaid levy account per trader per market. Each trading day one day's levy is
-- taken from the balance; whatever the balance cannot cover becomes arrears. A top-up pays arrears first.
--
-- Money is whole ngwee (bigint). An account never holds a balance and arrears at the same time.

create table levy_account (
    id                 uuid        primary key,
    council_ref        text        not null check (length(council_ref) between 1 and 100),
    market_ref         text        not null check (length(market_ref) between 1 and 100),
    trader_ref         text        not null check (length(trader_ref) between 1 and 100),
    daily_levy_ngwee   bigint      not null check (daily_levy_ngwee > 0),
    balance_ngwee      bigint      not null default 0 check (balance_ngwee >= 0),
    arrears_ngwee      bigint      not null default 0 check (arrears_ngwee >= 0),
    last_charged_on    date,
    created_at         timestamptz not null,
    unique (market_ref, trader_ref),
    check (balance_ngwee = 0 or arrears_ngwee = 0)
);

-- Every change to an account, never edited or deleted.
create table levy_movement (
    id                 bigint      generated always as identity primary key,
    account_id         uuid        not null references levy_account (id),
    kind               text        not null check (kind in ('TOP_UP', 'CHARGE')),
    amount_ngwee       bigint      not null check (amount_ngwee > 0),
    balance_after      bigint      not null check (balance_after >= 0),
    arrears_after      bigint      not null check (arrears_after >= 0),
    -- The trading day a charge is for. One charge per account per day, however often the charge runs.
    charge_date        date,
    -- The caller's key for a top-up. The same key twice is one top-up.
    idempotency_key    text        check (length(idempotency_key) between 1 and 200),
    reference          text        check (length(reference) <= 200),
    created_at         timestamptz not null,
    check ((kind = 'CHARGE') = (charge_date is not null)),
    check ((kind = 'TOP_UP') = (idempotency_key is not null)),
    unique (account_id, charge_date),
    unique (account_id, idempotency_key)
);

create index levy_movement_account_idx on levy_movement (account_id, id);

create function levy_movement_append_only() returns trigger
language plpgsql as $$
begin
    raise exception 'levy_movement is append-only: % is not allowed', tg_op;
end
$$;

create trigger levy_movement_no_change before update or delete on levy_movement
    for each row execute function levy_movement_append_only();

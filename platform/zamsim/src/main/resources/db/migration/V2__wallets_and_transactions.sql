-- zamsim's own double-entry ledger (card M-12). Everything here is simulated Zamtel Money.
--
-- Rules the database itself enforces, whatever the application does:
--   * a wallet balance is never negative (except the ISSUER, which holds the opposite of all e-money);
--   * every transaction's ledger lines add up to exactly zero (checked when the transaction commits);
--   * ledger lines are never changed or removed.
-- So the total of all wallets, ISSUER included, is always zero, and the total of all real wallets only
-- changes when money is funded in or withdrawn out through the ISSUER.

create table wallet (
    id              uuid primary key,
    type            text        not null check (type in (
                        'ISSUER', 'TRADER', 'AGENT', 'MERCHANT', 'COUNCIL', 'LENDER', 'SUPPLIER')),
    name            text        not null,
    msisdn          text,
    balance_ngwee   bigint      not null default 0,
    created_at      timestamptz not null,
    check (balance_ngwee >= 0 or type = 'ISSUER')
);

create unique index wallet_msisdn_unique on wallet (msisdn) where msisdn is not null;

-- There is exactly one ISSUER.
create unique index wallet_one_issuer on wallet ((true)) where type = 'ISSUER';

-- The one ISSUER wallet: it holds minus the total of all e-money in circulation.
insert into wallet (id, type, name, created_at)
values ('00000000-0000-0000-0000-000000000001', 'ISSUER', 'Issuer (cash backing)', now());

create table transaction (
    id               uuid primary key,
    kind             text        not null check (kind in ('CASH_IN', 'TRANSFER', 'FUND', 'WITHDRAW')),
    status           text        not null check (status in ('SUCCEEDED', 'FAILED')),
    from_wallet_id   uuid        not null references wallet (id),
    to_wallet_id     uuid        not null references wallet (id),
    amount_ngwee     bigint      not null check (amount_ngwee > 0),
    reference        text,
    failure_reason   text,
    created_at       timestamptz not null,
    check (from_wallet_id <> to_wallet_id),
    check ((status = 'FAILED') = (failure_reason is not null))
);

-- One row per idempotency key. The fingerprint says what the first request was, so the same key with
-- a different request can be refused instead of silently returning the wrong answer.
create table idempotency_record (
    idempotency_key  text        primary key check (length(idempotency_key) between 1 and 200),
    fingerprint      text        not null,
    transaction_id   uuid        references transaction (id),
    created_at       timestamptz not null
);

create table ledger_line (
    id                   bigint generated always as identity primary key,
    transaction_id       uuid        not null references transaction (id),
    wallet_id            uuid        not null references wallet (id),
    amount_ngwee         bigint      not null check (amount_ngwee <> 0),
    balance_after_ngwee  bigint      not null,
    created_at           timestamptz not null
);

create index ledger_line_wallet_idx on ledger_line (wallet_id, id);
create index ledger_line_transaction_idx on ledger_line (transaction_id);

-- Double entry: when a transaction commits, its lines must add up to exactly zero.
create function check_transaction_balanced() returns trigger
language plpgsql as $$
declare
    total bigint;
begin
    select sum(amount_ngwee) into total from ledger_line where transaction_id = new.transaction_id;
    if total <> 0 then
        raise exception 'transaction % is unbalanced: its ledger lines add up to %, not 0',
            new.transaction_id, total using errcode = 'integrity_constraint_violation';
    end if;
    return null;
end
$$;

create constraint trigger ledger_line_balanced after insert on ledger_line
    deferrable initially deferred
    for each row execute function check_transaction_balanced();

-- The ledger only grows.
create function refuse_ledger_line_change() returns trigger
language plpgsql as $$
begin
    raise exception 'ledger_line is append-only: % is not allowed', tg_op
        using errcode = 'integrity_constraint_violation';
end
$$;

create trigger ledger_line_append_only before update or delete on ledger_line
    for each row execute function refuse_ledger_line_change();
create trigger ledger_line_no_truncate before truncate on ledger_line
    for each statement execute function refuse_ledger_line_change();

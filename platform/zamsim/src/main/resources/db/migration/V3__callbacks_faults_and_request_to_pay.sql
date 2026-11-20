-- Callbacks, fault injection and request-to-pay (card M-13).

-- Request-to-pay: a merchant asks a customer to pay, and the payment waits (PENDING) until the customer
-- approves it in the sim console. A PENDING transaction has moved nothing and has no ledger lines yet.
alter table transaction drop constraint transaction_kind_check;
alter table transaction add constraint transaction_kind_check
    check (kind in ('CASH_IN', 'TRANSFER', 'FUND', 'WITHDRAW', 'REQUEST_TO_PAY'));

alter table transaction drop constraint transaction_status_check;
alter table transaction add constraint transaction_status_check
    check (status in ('PENDING', 'SUCCEEDED', 'FAILED'));

alter table transaction add column completed_at timestamptz;
alter table transaction add column expires_at timestamptz;

-- Everything that already exists was settled the moment it was made.
update transaction set completed_at = created_at where completed_at is null;

-- A transaction is complete exactly when it is no longer pending.
alter table transaction add constraint transaction_completed_when_not_pending
    check ((status = 'PENDING') = (completed_at is null));

-- Fault injection: how unreliable zamsim should pretend to be. One row, edited through the API.
-- (These are probabilities and times, not money, so double precision is fine.)
create table fault_settings (
    id                        integer primary key check (id = 1),
    failure_rate              double precision not null default 0 check (failure_rate between 0 and 1),
    callback_delay_ms         integer          not null default 0 check (callback_delay_ms >= 0),
    duplicate_callback_rate   double precision not null default 0 check (duplicate_callback_rate between 0 and 1),
    reorder_rate              double precision not null default 0 check (reorder_rate between 0 and 1),
    reorder_extra_delay_ms    integer          not null default 2000 check (reorder_extra_delay_ms >= 0)
);

insert into fault_settings (id) values (1);

-- The outbox of callbacks to Msika. A callback is written in the same database transaction as the
-- payment it reports, so it exists exactly when the payment settled, and a restart loses none.
-- One callback can have several deliveries (a duplicate is a second delivery with the same callback_id).
create table callback_delivery (
    id               bigint generated always as identity primary key,
    callback_id      uuid        not null,
    transaction_id   uuid        not null references transaction (id),
    payload          text        not null,
    status           text        not null check (status in ('PENDING', 'DELIVERED', 'DEAD')),
    attempts         integer     not null default 0,
    next_attempt_at  timestamptz not null,
    last_http_status integer,
    last_error       text,
    created_at       timestamptz not null,
    delivered_at     timestamptz
);

create index callback_delivery_due_idx on callback_delivery (next_attempt_at, id) where status = 'PENDING';
create index callback_delivery_transaction_idx on callback_delivery (transaction_id);

-- Payment requests (card M-14): what Msika asked the payments system to do, and what became of it.
--
-- A row is saved BEFORE the payments system is called, so a lost call or a lost callback still leaves a
-- record of what was asked. The ledger entry is written separately, by RecordEntry, when the callback arrives.

create table payment_request (
    id                      uuid        primary key,
    council_id              uuid        not null references council (id),
    purpose                 text        not null check (purpose in ('DEPOSIT')),
    stall_id                uuid        not null,
    amount_ngwee            bigint      not null check (amount_ngwee > 0),
    status                  text        not null check (status in ('SENT', 'SUCCEEDED', 'FAILED')),
    payments_transaction_id text,
    failure_reason          text,
    created_at              timestamptz not null,
    completed_at            timestamptz,

    foreign key (stall_id, council_id) references stall (id, council_id),

    -- An outcome has a completion time and a SENT request does not.
    check ((status = 'SENT') = (completed_at is null)),
    check (status <> 'FAILED' or failure_reason is not null)
);

-- The payments system's transaction id belongs to at most one request.
create unique index payment_request_txn_unique on payment_request (payments_transaction_id)
    where payments_transaction_id is not null;

create index payment_request_sent_idx on payment_request (council_id, created_at) where status = 'SENT';

alter table payment_request enable row level security;

create policy council_isolation on payment_request
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

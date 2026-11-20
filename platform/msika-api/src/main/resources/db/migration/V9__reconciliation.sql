-- Nightly reconciliation (card M-16): each council's day on Msika's ledger compared with the payments system.
--
-- These rows are a report about the ledger, not part of it. Running the reconciliation for a day again replaces
-- that day's report, because the newer comparison is the truer one (a late callback may have filled a gap).

create table reconciliation_run (
    council_id          uuid        not null references council (id),
    business_date       date        not null,
    ran_at              timestamptz not null,
    ran_by              text        not null check (length(ran_by) between 1 and 100),
    payments_count      integer     not null check (payments_count >= 0),
    msika_count         integer     not null check (msika_count >= 0),
    difference_count    integer     not null check (difference_count >= 0),
    primary key (council_id, business_date)
);

create table reconciliation_difference (
    id                       bigint      generated always as identity primary key,
    council_id               uuid        not null,
    business_date            date        not null,
    kind                     text        not null check (kind in ('MISSING_IN_MSIKA', 'MISSING_IN_PAYMENTS', 'AMOUNT_DIFFERS')),
    payments_transaction_id  text        not null check (length(payments_transaction_id) between 1 and 200),
    entry_kind               text        not null,
    stall_id                 uuid,
    ledger_entry_id          bigint,
    msika_amount_ngwee       bigint,
    payments_amount_ngwee    bigint,

    foreign key (council_id, business_date) references reconciliation_run (council_id, business_date),
    foreign key (stall_id, council_id) references stall (id, council_id),
    foreign key (ledger_entry_id, council_id) references ledger_entry (id, council_id),

    -- Each side's amount is there exactly when that side has the payment.
    check ((kind = 'MISSING_IN_MSIKA') = (msika_amount_ngwee is null)),
    check ((kind = 'MISSING_IN_PAYMENTS') = (payments_amount_ngwee is null)),
    check ((kind = 'MISSING_IN_MSIKA') = (ledger_entry_id is null)),

    unique (council_id, business_date, payments_transaction_id, kind)
);

create index reconciliation_difference_day_idx on reconciliation_difference (council_id, business_date);

alter table reconciliation_run        enable row level security;
alter table reconciliation_difference enable row level security;

create policy council_isolation on reconciliation_run
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

create policy council_isolation on reconciliation_difference
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

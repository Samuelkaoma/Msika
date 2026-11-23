-- The weekly levy receipt (card M-25): one text per trader per week, however often the job runs.
create table levy_receipt (
    council_id   uuid        not null references council (id),
    trader_id    uuid        not null,
    week_ending  date        not null,
    sent_at      timestamptz not null,
    primary key (council_id, trader_id, week_ending),
    foreign key (trader_id, council_id) references trader (id, council_id)
);

alter table levy_receipt enable row level security;

create policy council_isolation on levy_receipt
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

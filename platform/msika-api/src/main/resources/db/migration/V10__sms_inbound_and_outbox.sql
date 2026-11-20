-- The SMS channel (card M-18): every text received, and every text to send.
--
-- A message belongs to the council of the trader or agent who sent it (or is sent to). A message from a number
-- nobody has registered belongs to no council yet: its council_id is null. Row-level security hides every
-- council's messages from every other council as usual (AGENTS.md rule 4), and the unassigned messages are
-- visible only to a transaction that has said it is handling unassigned messages (app.sms_unassigned = 'on'),
-- which only the SMS code does. No transaction ever sees two councils' messages at once.

create table sms_inbound (
    id                  uuid        primary key,
    council_id          uuid        references council (id),
    -- The gateway's own id for the message. The same message delivered twice is stored once (rule 5).
    gateway_message_id  text        not null unique check (length(gateway_message_id) between 1 and 200),
    from_msisdn         text        not null check (from_msisdn ~ '^\+260[79][0-9]{8}$'),
    body                text        not null check (length(body) <= 1600),
    received_at         timestamptz not null,
    stored_at           timestamptz not null,
    status              text        not null check (status in ('RECEIVED', 'PROCESSED', 'RATE_LIMITED', 'FAILED')),
    -- What the message was understood as (DEP, SALE ...), or why it was not (an error code).
    outcome             text        check (length(outcome) <= 60),
    processed_at        timestamptz,
    check ((status = 'RECEIVED') = (processed_at is null))
);

create index sms_inbound_waiting_idx on sms_inbound (stored_at) where status = 'RECEIVED';
create index sms_inbound_sender_idx on sms_inbound (from_msisdn, stored_at);

create table sms_outbound (
    id                  uuid        primary key,
    council_id          uuid        references council (id),
    to_msisdn           text        not null check (to_msisdn ~ '^\+260[79][0-9]{8}$'),
    body                text        not null check (length(body) between 1 and 160),
    template            text        not null check (length(template) between 1 and 60),
    -- The inbound message this answers, if it is a reply.
    in_reply_to         uuid        references sms_inbound (id),
    status              text        not null check (status in ('PENDING', 'SENT', 'FAILED')),
    attempts            integer     not null default 0 check (attempts >= 0),
    next_attempt_at     timestamptz not null,
    last_error          text        check (length(last_error) <= 300),
    gateway_reference   text        check (length(gateway_reference) <= 200),
    created_at          timestamptz not null,
    sent_at             timestamptz,
    check ((status = 'SENT') = (sent_at is not null))
);

create index sms_outbound_due_idx on sms_outbound (next_attempt_at) where status = 'PENDING';

alter table sms_inbound  enable row level security;
alter table sms_outbound enable row level security;

create policy council_isolation on sms_inbound
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid
                or (council_id is null and current_setting('app.sms_unassigned', true) = 'on'))
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid
                or (council_id is null and current_setting('app.sms_unassigned', true) = 'on'));

create policy council_isolation on sms_outbound
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid
                or (council_id is null and current_setting('app.sms_unassigned', true) = 'on'))
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid
                or (council_id is null and current_setting('app.sms_unassigned', true) = 'on'));

-- A text names a phone number and not a council, so Msika has to learn who sent it BEFORE it can open that
-- council's data. Like payments_find_wallet (V8), this narrow function is the only way across the wall: it
-- returns which councils have a trader or an agent with ONE phone number the caller already has, and nothing
-- else about them.
create function sms_find_sender(p_msisdn text)
returns table (council_id uuid, trader_id uuid, agent_id uuid)
language sql stable security definer set search_path = public
as $$
    select coalesce(t.council_id, a.council_id), t.id, a.id
    from (select id, council_id from trader where msisdn = p_msisdn) t
    full join (select id, council_id from agent where msisdn = p_msisdn and active) a
        on a.council_id = t.council_id
$$;

revoke all on function sms_find_sender(text) from public;
grant execute on function sms_find_sender(text) to msika_app;

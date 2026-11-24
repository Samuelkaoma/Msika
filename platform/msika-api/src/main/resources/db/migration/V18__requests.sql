-- The request engine (card M-62, COUNCIL-ADMIN.md sections 4, 8 and 9).
create table request_policy (
    council_id         uuid    not null references council (id),
    kind               text    not null,
    decider_roles      jsonb   not null,
    approvals_required integer not null check (approvals_required between 1 and 4),
    deadline_days      integer not null check (deadline_days > 0),
    bulk_allowed       boolean not null,
    sample_rate        integer not null check (sample_rate >= 0),
    primary key (council_id, kind),
    check (jsonb_array_length(decider_roles) = approvals_required)
);

create table request (
    id              uuid        primary key,
    council_id      uuid        not null references council (id),
    kind            text        not null,
    subject_type    text        not null check (subject_type in ('STALL', 'AGENT')),
    subject_id      uuid        not null,
    market_id       uuid,
    raised_by_type  text        not null check (raised_by_type in ('USER', 'TRADER', 'AGENT', 'SYSTEM')),
    raised_by_id    text        not null,
    reason          text        not null check (length(reason) between 1 and 500),
    proposed_effect jsonb       not null,
    effect_words    text        not null,
    status          text        not null check (status in ('OPEN', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    -- The policy the request was raised under. Tightening a policy later does not change requests already open.
    policy          jsonb       not null,
    due_at          timestamptz not null,
    escalated_at    timestamptz,
    last_problem    text,
    idempotency_key text        not null check (length(idempotency_key) between 1 and 100),
    created_at      timestamptz not null,
    unique (id, council_id),
    unique (council_id, raised_by_id, idempotency_key),
    foreign key (market_id, council_id) references market (id, council_id),
    check ((subject_type = 'STALL') = (market_id is not null))
);
create unique index request_one_open on request (council_id, kind, subject_id) where status = 'OPEN';
create index request_open_due on request (council_id, due_at) where status = 'OPEN';

create table request_evidence (
    id            uuid primary key,
    council_id    uuid not null references council (id),
    request_id    uuid not null,
    evidence_type text not null check (length(evidence_type) between 1 and 40),
    evidence_id   text not null check (length(evidence_id) between 1 and 100),
    note          text not null default '' check (length(note) <= 500),
    foreign key (request_id, council_id) references request (id, council_id)
);
create index request_evidence_request on request_evidence (request_id);

create table request_decision (
    id                   uuid        primary key,
    council_id           uuid        not null references council (id),
    request_id           uuid        not null,
    decided_by           uuid        not null,
    decision             text        not null check (decision in ('APPROVE', 'REJECT')),
    reason               text        check (length(reason) between 1 and 500),
    decided_at           timestamptz not null,
    corrects_decision_id uuid        references request_decision (id),
    foreign key (request_id, council_id) references request (id, council_id),
    foreign key (decided_by, council_id) references council_user (id, council_id),
    check (decision = 'APPROVE' or reason is not null)
);
create unique index request_decision_one_approval on request_decision (request_id, decided_by) where decision = 'APPROVE';

-- Who opened a request's page: the sample for bulk approval is counted from it.
create table request_view (
    council_id uuid        not null references council (id),
    request_id uuid        not null,
    user_id    uuid        not null,
    at         timestamptz not null,
    primary key (request_id, user_id),
    foreign key (request_id, council_id) references request (id, council_id)
);

-- A user going on leave hands their open items to a colleague. Append-only: the latest row counts.
create table request_handover (
    id           bigint generated always as identity primary key,
    council_id   uuid        not null references council (id),
    request_id   uuid        not null,
    from_user_id uuid        not null,
    to_user_id   uuid        not null,
    at           timestamptz not null,
    foreign key (request_id, council_id) references request (id, council_id),
    foreign key (from_user_id, council_id) references council_user (id, council_id),
    foreign key (to_user_id, council_id) references council_user (id, council_id),
    check (from_user_id <> to_user_id)
);

do $$
declare t text;
begin
    foreach t in array array['request_policy', 'request', 'request_evidence', 'request_decision', 'request_view', 'request_handover'] loop
        execute format('alter table %I enable row level security', t);
        execute format($p$create policy council_isolation on %I
            using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
            with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid)$p$, t);
    end loop;
end
$$;

create function request_append_only() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only: % is not allowed', tg_table_name, tg_op using errcode = 'integrity_constraint_violation';
end
$$;
create trigger request_decision_append_only before update or delete on request_decision
    for each row execute function request_append_only();
create trigger request_evidence_append_only before update or delete on request_evidence
    for each row execute function request_append_only();
create trigger request_handover_append_only before update or delete on request_handover
    for each row execute function request_append_only();
create trigger request_never_deleted before delete on request
    for each row execute function request_append_only();

-- Nobody decides their own request (rule 2), a closed request takes no decisions, and a second approval says why.
create function request_decision_rules() returns trigger language plpgsql as $$
declare r request;
begin
    select * into r from request where id = new.request_id for update;
    if r.status <> 'OPEN' then
        raise exception 'the request is not open' using errcode = 'integrity_constraint_violation';
    end if;
    if r.raised_by_type = 'USER' and r.raised_by_id = new.decided_by::text then
        raise exception 'the person who raised a request cannot decide it' using errcode = 'integrity_constraint_violation';
    end if;
    if new.decision = 'APPROVE' and new.reason is null and exists (
            select 1 from request_decision d where d.request_id = new.request_id and d.decision = 'APPROVE') then
        raise exception 'a second approval needs a reason' using errcode = 'integrity_constraint_violation';
    end if;
    return new;
end
$$;
create trigger request_decision_rules before insert on request_decision
    for each row execute function request_decision_rules();

-- What was asked for never changes, a closed request stays closed, and it is approved only with enough approvals.
create function request_rules() returns trigger language plpgsql as $$
begin
    if (new.id, new.council_id, new.kind, new.subject_type, new.subject_id, new.market_id, new.raised_by_type,
        new.raised_by_id, new.reason, new.proposed_effect, new.effect_words, new.policy, new.due_at,
        new.idempotency_key, new.created_at) is distinct from
       (old.id, old.council_id, old.kind, old.subject_type, old.subject_id, old.market_id, old.raised_by_type,
        old.raised_by_id, old.reason, old.proposed_effect, old.effect_words, old.policy, old.due_at,
        old.idempotency_key, old.created_at) then
        raise exception 'what a request asks for cannot be changed' using errcode = 'integrity_constraint_violation';
    end if;
    if old.status <> 'OPEN' and new.status <> old.status then
        raise exception 'a closed request stays closed' using errcode = 'integrity_constraint_violation';
    end if;
    if new.status = 'APPROVED' and old.status = 'OPEN'
       and (select count(*) from request_decision d where d.request_id = new.id and d.decision = 'APPROVE')
           < jsonb_array_length(new.policy -> 'steps') then
        raise exception 'the request does not have all its approvals' using errcode = 'integrity_constraint_violation';
    end if;
    return new;
end
$$;
create trigger request_rules before update on request for each row execute function request_rules();

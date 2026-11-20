-- Council users and the audit log (card M-09).

create table council_user (
    id             uuid primary key,
    council_id     uuid references council (id),
    email          text        not null,
    password_hash  text        not null,
    role           text        not null check (role in ('MSIKA_OPERATOR', 'COUNCIL_ADMIN', 'COUNCIL_OFFICER')),
    active         boolean     not null default true,
    created_at     timestamptz not null,
    -- An operator belongs to no council; every other role belongs to exactly one.
    check ((role = 'MSIKA_OPERATOR') = (council_id is null))
);

-- Emails are unique across all councils, ignoring case, because sign-in does not say which council.
create unique index council_user_email_unique on council_user (lower(email));

alter table council_user enable row level security;

create policy council_isolation on council_user
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- Why the functions below exist.
--
-- Sign-in has an email but does not know the council, and every later request has a user id but we
-- must read the user before we can trust which council it is for. Row-level security hides every
-- row until a council is set, so these two lookups cannot go through the normal policy.
--
-- Each function is SECURITY DEFINER: it runs with the rights of its owner (msika_owner, who is
-- exempt from the policy), returns at most one user, and can read nothing else. msika_app may only
-- call them; it still cannot read council_user directly outside its own council. Operators (who
-- have no council and are invisible to the policy) are created outside the API, by
-- auth_create_operator, which refuses to touch an email that already exists.

create function auth_find_user_by_email(p_email text)
returns table (id uuid, council_id uuid, email text, password_hash text, role text, active boolean, created_at timestamptz)
language sql stable security definer set search_path = public
as $$
    select u.id, u.council_id, u.email, u.password_hash, u.role, u.active, u.created_at
    from council_user u
    where lower(u.email) = lower(p_email)
$$;

create function auth_find_user_by_id(p_id uuid)
returns table (id uuid, council_id uuid, email text, password_hash text, role text, active boolean, created_at timestamptz)
language sql stable security definer set search_path = public
as $$
    select u.id, u.council_id, u.email, u.password_hash, u.role, u.active, u.created_at
    from council_user u
    where u.id = p_id
$$;

create function auth_create_operator(p_id uuid, p_email text, p_password_hash text, p_created_at timestamptz)
returns boolean
language plpgsql security definer set search_path = public
as $$
begin
    insert into council_user (id, council_id, email, password_hash, role, active, created_at)
    values (p_id, null, lower(p_email), p_password_hash, 'MSIKA_OPERATOR', true, p_created_at)
    on conflict do nothing;
    return found;
end
$$;

revoke all on function auth_find_user_by_email(text) from public;
revoke all on function auth_find_user_by_id(uuid) from public;
revoke all on function auth_create_operator(uuid, text, text, timestamptz) from public;
grant execute on function auth_find_user_by_email(text) to msika_app;
grant execute on function auth_find_user_by_id(uuid) to msika_app;
grant execute on function auth_create_operator(uuid, text, text, timestamptz) to msika_app;

-- Who changed what. Append only: the app role may add and read rows, never change or remove them.
create table audit_log (
    id          uuid primary key,
    council_id  uuid        not null references council (id),
    actor       text        not null,
    action      text        not null,
    target      text        not null,
    at          timestamptz not null
);

create index audit_log_council_time_idx on audit_log (council_id, at);

alter table audit_log enable row level security;

create policy council_isolation on audit_log
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

revoke update, delete, truncate on audit_log from msika_app;

-- Several roles per user, scoped to markets (card M-61, COUNCIL-ADMIN.md sections 3 and 9).
alter table council_user add constraint council_user_id_council_unique unique (id, council_id);

create table user_role (
    id          uuid        primary key,
    council_id  uuid        not null references council (id),
    user_id     uuid        not null,
    role        text        not null check (role in ('COUNCIL_ADMIN', 'MARKET_MASTER', 'REVENUE_OFFICER',
                                'ENFORCEMENT_OFFICER', 'COUNCIL_OFFICER', 'AUDITOR')),
    market_ids  uuid[],
    granted_by  text        not null check (length(granted_by) between 1 and 100),
    granted_at  timestamptz not null,
    revoked_by  text,
    revoked_at  timestamptz,
    foreign key (user_id, council_id) references council_user (id, council_id),
    check ((role in ('MARKET_MASTER', 'ENFORCEMENT_OFFICER')) = (market_ids is not null)),
    check (market_ids is null or cardinality(market_ids) > 0),
    check ((revoked_at is null) = (revoked_by is null))
);
create unique index user_role_held_once on user_role (user_id, role) where revoked_at is null;

alter table user_role enable row level security;
create policy council_isolation on user_role
    using      (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- Every existing user keeps exactly the role they had.
insert into user_role (id, council_id, user_id, role, granted_by, granted_at)
select gen_random_uuid(), council_id, id, role, 'system:migration', now() from council_user where council_id is not null;

-- Separation of duties (section 3.3), refused here as well as in the code.
create function user_role_rules() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'a role grant is never deleted, only revoked' using errcode = 'integrity_constraint_violation';
    end if;
    if tg_op = 'UPDATE' then
        if old.revoked_at is not null or new.revoked_at is null
           or (new.id, new.council_id, new.user_id, new.role, new.market_ids, new.granted_by, new.granted_at)
              is distinct from
              (old.id, old.council_id, old.user_id, old.role, old.market_ids, old.granted_by, old.granted_at) then
            raise exception 'a role grant can only be revoked, once' using errcode = 'integrity_constraint_violation';
        end if;
        if new.revoked_by = new.user_id::text then
            raise exception 'a user cannot change their own roles' using errcode = 'integrity_constraint_violation';
        end if;
        return new;
    end if;
    if new.granted_by = new.user_id::text then
        raise exception 'a user cannot change their own roles' using errcode = 'integrity_constraint_violation';
    end if;
    -- Two grants to one user are checked one after the other.
    perform 1 from council_user where id = new.user_id for update;
    if exists (select 1 from user_role r where r.user_id = new.user_id and r.revoked_at is null
               and ((new.role = 'ENFORCEMENT_OFFICER' and r.role = 'REVENUE_OFFICER')
                 or (new.role = 'REVENUE_OFFICER' and r.role = 'ENFORCEMENT_OFFICER'))) then
        raise exception 'one person cannot be both enforcement officer and revenue officer'
            using errcode = 'integrity_constraint_violation';
    end if;
    if new.market_ids is not null and exists (select 1 from unnest(new.market_ids) m
               where not exists (select 1 from market k where k.id = m and k.council_id = new.council_id)) then
        raise exception 'a role names a market of another council' using errcode = 'integrity_constraint_violation';
    end if;
    return new;
end
$$;
create trigger user_role_rules before insert or update or delete on user_role
    for each row execute function user_role_rules();

-- The single role column goes. An operator is the user with no council.
drop function auth_find_user_by_email(text);
drop function auth_find_user_by_id(uuid);
alter table council_user drop column role;

create function auth_find_user_by_email(p_email text)
returns table (id uuid, council_id uuid, email text, password_hash text, active boolean, created_at timestamptz)
language sql stable security definer set search_path = public
as $$ select u.id, u.council_id, u.email, u.password_hash, u.active, u.created_at from council_user u
      where lower(u.email) = lower(p_email) $$;

create function auth_find_user_by_id(p_id uuid)
returns table (id uuid, council_id uuid, email text, password_hash text, active boolean, created_at timestamptz)
language sql stable security definer set search_path = public
as $$ select u.id, u.council_id, u.email, u.password_hash, u.active, u.created_at from council_user u where u.id = p_id $$;

-- One user's current roles, for the same reason the two functions above exist (see V4).
create function auth_find_roles(p_user_id uuid) returns table (role text, market_ids uuid[])
language sql stable security definer set search_path = public
as $$ select r.role, r.market_ids from user_role r where r.user_id = p_user_id and r.revoked_at is null $$;

create or replace function auth_create_operator(p_id uuid, p_email text, p_password_hash text, p_created_at timestamptz)
returns boolean language plpgsql security definer set search_path = public as $$
begin
    insert into council_user (id, council_id, email, password_hash, active, created_at)
    values (p_id, null, lower(p_email), p_password_hash, true, p_created_at) on conflict do nothing;
    return found;
end
$$;

revoke all on function auth_find_user_by_email(text) from public;
revoke all on function auth_find_user_by_id(uuid) from public;
revoke all on function auth_find_roles(uuid) from public;
grant execute on function auth_find_user_by_email(text) to msika_app;
grant execute on function auth_find_user_by_id(uuid) to msika_app;
grant execute on function auth_find_roles(uuid) to msika_app;

-- A copy of the matrix in code, for people reading the database. The application never decides from it.
create table role_permission (role text not null, permission text not null, primary key (role, permission));

-- Market plans (card M-48, docs/MARKET-MAP.md sections 4, 5.1 and 10). Lengths are integer millimetres in a flat
-- plane local to each market (decision 0006); polygons are integer[] of x1, y1, x2, y2, ... (counter-clockwise, closed
-- implicitly). Plain Postgres, no PostGIS (decision 0007).

create table market_plan (
    id                  uuid        primary key,
    council_id          uuid        not null references council (id),
    market_id           uuid        not null,
    version             integer     not null check (version > 0),
    status              text        not null check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    unmapped_stall_ids  uuid[]      not null default '{}',
    notes               text        check (length(notes) <= 2000),
    created_at          timestamptz not null,
    created_by          text        not null,
    published_at        timestamptz,
    published_by        text,
    foreign key (market_id, council_id) references market (id, council_id),
    unique (council_id, market_id, version),
    unique (id, council_id),
    check ((status = 'DRAFT') = (published_at is null))
);

-- At most one published plan per market at a time.
create unique index market_plan_current on market_plan (council_id, market_id) where status = 'PUBLISHED';

create table plan_section (
    id          uuid    primary key,
    council_id  uuid    not null,
    plan_id     uuid    not null,
    name        text    not null check (length(name) between 1 and 60),
    polygon_mm  integer[] not null check (cardinality(polygon_mm) >= 6 and cardinality(polygon_mm) % 2 = 0),
    foreign key (plan_id, council_id) references market_plan (id, council_id)
);

create table plan_aisle (
    id          uuid    primary key,
    council_id  uuid    not null,
    plan_id     uuid    not null,
    name        text    not null check (length(name) between 1 and 60),
    path_mm     integer[] not null check (cardinality(path_mm) >= 4 and cardinality(path_mm) % 2 = 0),
    foreign key (plan_id, council_id) references market_plan (id, council_id),
    unique (plan_id, name)
);

create table plan_feature (
    id          uuid    primary key,
    council_id  uuid    not null,
    plan_id     uuid    not null,
    kind        text    not null check (kind in ('GATE', 'TOILET', 'WATER', 'OFFICE', 'DRAIN', 'OTHER')),
    label       text    check (length(label) <= 100),
    shape_mm    integer[] not null check (cardinality(shape_mm) >= 2 and cardinality(shape_mm) % 2 = 0),
    foreign key (plan_id, council_id) references market_plan (id, council_id)
);

create table stall_footprint (
    id              uuid    primary key,
    council_id      uuid    not null,
    plan_id         uuid    not null,
    stall_id        uuid    not null,
    polygon_mm      integer[] not null check (cardinality(polygon_mm) >= 6 and cardinality(polygon_mm) % 2 = 0),
    provenance      text    not null check (provenance in ('GENERATED', 'TRACED', 'SURVEYED', 'STATED')),
    uncertainty_mm  integer check (uncertainty_mm >= 0),
    aisle           text    check (length(aisle) <= 60),
    side            text    check (side in ('L', 'R')),
    position        integer check (position > 0),
    foreign key (plan_id, council_id) references market_plan (id, council_id),
    foreign key (stall_id, council_id) references stall (id, council_id),
    -- A generated footprint was not measured, so it has no uncertainty; a measured one always does.
    check ((provenance = 'GENERATED') = (uncertainty_mm is null)),
    -- Unique per stall and per address are checked by the publish checks, which list every clash at once;
    -- a draft may hold clashes while it is being fixed.
    check ((aisle is null) = (side is null) and (side is null) = (position is null))
);

create index stall_footprint_plan_idx on stall_footprint (council_id, plan_id);

-- How a market's numbers run, for the layout generator (section 5.1).
create table market_layout_config (
    council_id       uuid    not null,
    market_id        uuid    not null,
    stalls_per_side  integer not null check (stalls_per_side between 1 and 500),
    stall_width_mm   integer not null check (stall_width_mm between 500 and 50000),
    stall_depth_mm   integer not null check (stall_depth_mm between 500 and 50000),
    aisle_width_mm   integer not null check (aisle_width_mm between 500 and 50000),
    section_gap_mm   integer not null check (section_gap_mm between 0 and 100000),
    primary key (council_id, market_id),
    foreign key (market_id, council_id) references market (id, council_id)
);

-- A published plan never changes (MARKET-MAP rule 6). The only changes allowed to a plan row are publishing a draft
-- and retiring a published plan; a draft may be deleted; its contents may change only while it is a draft.
create function refuse_published_plan_change() returns trigger
language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        if old.status <> 'DRAFT' then
            raise exception 'market plan % is % and cannot be deleted', old.id, old.status
                using errcode = 'integrity_constraint_violation';
        end if;
        return old;
    end if;
    if old.status = 'DRAFT' then
        return new;
    end if;
    if old.status = 'PUBLISHED' and new.status = 'RETIRED'
       and (new.id, new.market_id, new.version, new.unmapped_stall_ids, new.notes, new.published_at, new.published_by)
           is not distinct from (old.id, old.market_id, old.version, old.unmapped_stall_ids, old.notes, old.published_at,
                                 old.published_by) then
        return new;
    end if;
    raise exception 'market plan % is % and cannot be changed: start a new draft', old.id, old.status
        using errcode = 'integrity_constraint_violation';
end
$$;

create trigger market_plan_frozen before update or delete on market_plan
    for each row execute function refuse_published_plan_change();

create function refuse_published_content_change() returns trigger
language plpgsql as $$
declare
    p uuid := coalesce(new.plan_id, old.plan_id);
    s text;
begin
    select status into s from market_plan where id = p;
    if s is distinct from 'DRAFT' then
        raise exception 'market plan % is % and its contents cannot be changed', p, s
            using errcode = 'integrity_constraint_violation';
    end if;
    return coalesce(new, old);
end
$$;

create trigger plan_section_frozen before insert or update or delete on plan_section
    for each row execute function refuse_published_content_change();
create trigger plan_aisle_frozen before insert or update or delete on plan_aisle
    for each row execute function refuse_published_content_change();
create trigger plan_feature_frozen before insert or update or delete on plan_feature
    for each row execute function refuse_published_content_change();
create trigger stall_footprint_frozen before insert or update or delete on stall_footprint
    for each row execute function refuse_published_content_change();

alter table market_plan          enable row level security;
alter table plan_section         enable row level security;
alter table plan_aisle           enable row level security;
alter table plan_feature         enable row level security;
alter table stall_footprint      enable row level security;
alter table market_layout_config enable row level security;

create policy council_isolation on market_plan
    using (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);
create policy council_isolation on plan_section
    using (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);
create policy council_isolation on plan_aisle
    using (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);
create policy council_isolation on plan_feature
    using (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);
create policy council_isolation on stall_footprint
    using (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);
create policy council_isolation on market_layout_config
    using (council_id = nullif(current_setting('app.council_id', true), '')::uuid)
    with check (council_id = nullif(current_setting('app.council_id', true), '')::uuid);

-- Additive v1 snapshot protocol. Keep old installed clients compatible.
-- Auth owns passwords and email verification; client profile.role is never trusted.
create table public.ddlr_cloud_state (
  user_id uuid primary key references auth.users(id) on delete cascade,
  revision bigint not null default 0 check (revision >= 0),
  payload jsonb,
  updated_at timestamptz not null default now()
);
create table public.ddlr_cloud_history (
  user_id uuid not null references auth.users(id) on delete cascade,
  revision bigint not null,
  payload jsonb not null,
  saved_at timestamptz not null default now(),
  primary key (user_id, revision)
);
alter table public.ddlr_cloud_state enable row level security;
alter table public.ddlr_cloud_history enable row level security;
revoke all on public.ddlr_cloud_state, public.ddlr_cloud_history from anon, authenticated;
grant select on public.ddlr_cloud_state, public.ddlr_cloud_history to authenticated;
create policy ddlr_read_own on public.ddlr_cloud_state for select to authenticated
  using ((select auth.uid()) = user_id);
create policy ddlr_history_read_own on public.ddlr_cloud_history for select to authenticated
  using ((select auth.uid()) = user_id);

-- A single transaction locks the user's row and checks its revision. Concurrent
-- devices cannot overwrite an unseen update. The UID always comes from Auth.
create function public.ddlr_save_state(expected_revision bigint, new_payload jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  owner_id uuid := auth.uid();
  current_row public.ddlr_cloud_state%rowtype;
begin
  if owner_id is null then raise exception 'DDLR_UNAUTHENTICATED' using errcode = '42501'; end if;
  if expected_revision is null or expected_revision < 0 or new_payload is null
     or jsonb_typeof(new_payload) is distinct from 'object'
     or new_payload->'schema' is distinct from '1'::jsonb
     or jsonb_typeof(new_payload->'name') is distinct from 'string'
     or length(new_payload->>'name') > 24
     or jsonb_typeof(new_payload->'tasks') is distinct from 'array'
     or not (new_payload ? 'timetable')
     or (new_payload - array['schema','name','tasks','timetable']) <> '{}'::jsonb
     or octet_length(new_payload::text) > 1048576 then
    raise exception 'DDLR_INVALID_PAYLOAD' using errcode = '22023';
  end if;
  if jsonb_array_length(new_payload->'tasks') > 5000 then
    raise exception 'DDLR_TOO_MANY_TASKS' using errcode = '22023';
  end if;
  insert into public.ddlr_cloud_state(user_id) values (owner_id) on conflict do nothing;
  select * into current_row from public.ddlr_cloud_state where user_id = owner_id for update;
  if current_row.revision <> expected_revision then
    raise exception 'DDLR_CONFLICT' using errcode = 'P0001';
  end if;
  if current_row.payload is not null then
    insert into public.ddlr_cloud_history(user_id,revision,payload)
      values(owner_id,current_row.revision,current_row.payload);
    delete from public.ddlr_cloud_history where user_id = owner_id and revision <= current_row.revision - 5;
  end if;
  update public.ddlr_cloud_state set payload = new_payload,
    revision = current_row.revision + 1, updated_at = now() where user_id = owner_id
    returning * into current_row;
  return jsonb_build_object('revision',current_row.revision,'payload',current_row.payload,'updated_at',current_row.updated_at);
end;
$$;
revoke all on function public.ddlr_save_state(bigint,jsonb) from public, anon;
grant execute on function public.ddlr_save_state(bigint,jsonb) to authenticated;

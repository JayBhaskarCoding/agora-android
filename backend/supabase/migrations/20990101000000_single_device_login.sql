-- =============================================================
-- Single-device login policy
-- Adds public.profiles.current_device_id, which the Android app
-- claims on login. When a second device logs in it overwrites the
-- column; the first device observes the change (Realtime, with a
-- polling fallback) and force-logs-out.
--
-- Run with:  supabase db push
-- or paste into the Supabase SQL editor.
-- =============================================================

-- 1) The session-claim column (text UUID generated & stored on-device).
alter table public.profiles
  add column if not exists current_device_id text;

comment on column public.profiles.current_device_id is
  'Device id of the single device allowed to hold a session for this user. Overwritten on every login.';

-- 2) Optional backfill from the legacy active_session_id column (no-op
--    if that column was never added / never populated).
do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_schema = 'public'
      and table_name   = 'profiles'
      and column_name  = 'active_session_id'
  ) then
    execute $backfill$
      update public.profiles
         set current_device_id = active_session_id
       where current_device_id is null
         and active_session_id is not null
    $backfill$;
  end if;
end $$;

-- 3) Realtime: allow postgres_changes subscriptions on profiles.
--    (Error 42710 "already member" is safe to ignore if it exists.)
do $$
begin
  alter publication supabase_realtime add table public.profiles;
exception
  when duplicate_object then null;
end $$;

-- 4) Row Level Security: a user may only read/update their own row.
--    Skips creation when a policy with the same name already exists;
--    rename in pg_policies if your names differ but the semantics match.
do $$
begin
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename  = 'profiles'
      and policyname = 'profiles_select_own'
  ) then
    create policy profiles_select_own
      on public.profiles
      for select
      to authenticated
      using (auth.uid() = id);
  end if;

  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename  = 'profiles'
      and policyname = 'profiles_update_own'
  ) then
    create policy profiles_update_own
      on public.profiles
      for update
      to authenticated
      using (auth.uid() = id)
      with check (auth.uid() = id);
  end if;
end $$;

-- 5) Index is not required (rows are matched by primary key id),
--    but useful for the "which device is active" debugging queries.
create index if not exists profiles_current_device_id_idx
  on public.profiles (current_device_id);

-- =============================================================
-- Optional hardening (Supabase Auth side, NOT required by the app):
-- revoke other refresh tokens when the device changes, so a stolen
-- refresh token on Device A cannot mint new JWTs after Device B logs
-- in. Requires the pgjwt/supabase_auth schema helpers; run manually
-- only if you want token-level enforcement in addition to the
-- app-level enforcement above:
--
--   select supabase_auth.admin.sign_out(<user_id>);  -- global, via SQL
-- =============================================================

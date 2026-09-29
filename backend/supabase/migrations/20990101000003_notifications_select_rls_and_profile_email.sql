-- =============================================================
-- Notification delivery + username-login email resolution fixes
--
-- 1) NOTIFICATIONS SELECT RLS — fixes "Realtime connected but no
--    events received". public.notifications has RLS enabled with only
--    an INSERT policy. Supabase Realtime (WALRUS) enforces SELECT RLS
--    for postgres_changes subscribers, so the Android client — even
--    though its websocket connects and the channel reports SUBSCRIBED
--    for `recipient_id=eq.<uid>` INSERTs — receives zero events until
--    the recipient can SELECT their own rows.
--
-- 2) PROFILES.EMAIL AT SIGNUP — username sign-in resolves
--    handle -> email through public.profiles (Supabase signInWith(Email)
--    requires an email). Rows created by the handle_new_user trigger
--    have email = NULL, so every account created after the trigger ran
--    (Google sign-ups especially) is invisible to the lookup. Persist
--    new.email in the trigger and backfill existing rows from auth.users.
--
-- Run with:  supabase db push
-- or paste into the Supabase SQL editor.
-- =============================================================

-- 1) Recipients can read their own notifications (required for Realtime delivery).
do $$
begin
  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename  = 'notifications'
      and policyname = 'notifications_select_own'
  ) then
    create policy notifications_select_own
      on public.notifications
      for select
      to authenticated
      using (auth.uid() = recipient_id);
  end if;
end $$;

-- Belt-and-braces: notifications must be in the realtime publication.
-- (Already present via 20260928163754_remote_schema.sql; error 42710
-- "already member" is caught and ignored so this stays idempotent.)
do $$
begin
  alter publication supabase_realtime add table public.notifications;
exception
  when duplicate_object then null;
end $$;

-- 2a) Persist the signup email on the auto-created profiles row so the
--     handle -> email login lookup works for every future account.
CREATE OR REPLACE FUNCTION public.handle_new_user()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path TO ''
  AS $function$
begin
  insert into public.profiles (id, first_name, last_name, handle, email)
  values (
    new.id,
    new.raw_user_meta_data ->> 'first_name',
    new.raw_user_meta_data ->> 'last_name',
    new.raw_user_meta_data ->> 'handle',
    new.email
  );
  return new;
end;
$function$;

-- 2b) Backfill missing emails for accounts created before 2a) existed.
update public.profiles p
   set email = u.email
  from auth.users u
 where u.id = p.id
   and p.email is null
   and u.email is not null;

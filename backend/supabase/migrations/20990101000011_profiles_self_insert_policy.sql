-- 20990101000011_profiles_self_insert_policy.sql
--
-- WHY
-- ---
-- `public.profiles` had SELECT and UPDATE policies but no INSERT policy at all.
-- The only thing that ever created a row was the SECURITY DEFINER trigger
-- `handle_new_user()`, so when that trigger did not produce a row for an account
-- (an account created before the trigger existed, a failed insert, a row removed
-- by `delete_abandoned_user()`), the client had no way to repair it:
--
--   * `update` matches zero rows and reports SUCCESS — the silent no-op that let
--     onboarding "succeed" without persisting anything;
--   * `upsert` / `insert` was rejected outright by RLS (401/403, or 0 rows with
--     no error depending on the Postgrest path used).
--
-- The client now verifies every onboarding write and falls back to an INSERT when
-- the row is missing (AuthViewModel.persistProfileRow). This policy is what makes
-- that fallback legal.
--
-- SCOPE
-- -----
-- `WITH CHECK (auth.uid() = id)` — an authenticated user may create exactly one
-- row: their own. It cannot create rows for other users, cannot set someone
-- else's id, and is meaningless for `anon` (no JWT -> auth.uid() is NULL).
-- Deliberately `TO authenticated`, not `TO PUBLIC`: an anonymous request must
-- never be able to insert a profile.
--
-- Idempotent: safe to re-run.

drop policy if exists "Users can insert their own profile" on public.profiles;

create policy "Users can insert their own profile"
  on public.profiles
  for insert
  to authenticated
  with check (auth.uid() = id);

-- Sanity: the trigger must remain the primary creator. This policy is a repair
-- path, not a replacement — if the trigger is missing, rows will now appear only
-- after the user finishes onboarding, and `first_name`/`handle` will be NULL until
-- then (which is exactly the placeholder shape OnboardingRouting expects).
comment on policy "Users can insert their own profile" on public.profiles is
  'Repair path for a missing profile row; a user may only insert their own id.';

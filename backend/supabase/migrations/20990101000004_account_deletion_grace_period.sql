-- Account deletion with a 3-day grace period.
--
-- Flow:
--   1. The Android client asks GoTrue for a magic link addressed to the user's
--      registered email with redirect_to = agora://auth-callback?flow=delete-account
--      (⚠ add that URL to Dashboard → Authentication → URL Configuration →
--      Redirect URLs, otherwise GoTrue falls back to the Site URL).
--   2. The user opens the link; GoTrue verifies the token and deep-links back
--      into the app, which imports the refreshed session (proof of inbox
--      ownership) and then calls public.request_account_deletion().
--   3. request_account_deletion() stamps profiles.deletion_scheduled_at =
--      now() + 3 days. The user can undo at any time through
--      public.cancel_account_deletion() ("Revert Changes").
--   4. A daily pg_cron job webhooks the process-deletions Edge Function, which
--      removes storage media and calls auth.admin.deleteUser for every due
--      account. FK cascades (profiles → auth.users; posts/comments/likes/
--      notifications/reports → profiles) erase the rest of the row graph.
--
-- Edge case: logging out during the window does NOT cancel anything — the
-- stamp lives in the database and the cron job executes regardless of session
-- state.

ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS deletion_scheduled_at timestamptz NULL;

COMMENT ON COLUMN public.profiles.deletion_scheduled_at IS
  'When non-null, the account is permanently deleted at this instant by the '
  'process-deletions Edge Function (3-day grace period, user-revertible).';

-- ─────────────────────────────────────────────────────────────────────────────
-- RPCs — SECURITY DEFINER so the stamp can only ever be moved by the
-- authenticated owner's own session (RLS-independent, uid-scoped).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION public.request_account_deletion()
  RETURNS void
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, pg_temp
  AS $function$
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Not authenticated';
  END IF;

  UPDATE public.profiles
     SET deletion_scheduled_at = now() + interval '3 days'
   WHERE id = auth.uid();
END;
$function$;

CREATE OR REPLACE FUNCTION public.cancel_account_deletion()
  RETURNS void
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, pg_temp
  AS $function$
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Not authenticated';
  END IF;

  UPDATE public.profiles
     SET deletion_scheduled_at = NULL
   WHERE id = auth.uid();
END;
$function$;

REVOKE ALL ON FUNCTION public.request_account_deletion() FROM public, anon;
REVOKE ALL ON FUNCTION public.cancel_account_deletion() FROM public, anon;
GRANT EXECUTE ON FUNCTION public.request_account_deletion() TO authenticated;
GRANT EXECUTE ON FUNCTION public.cancel_account_deletion() TO authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- Daily cron → Edge Function webhook.
--
-- auth.users cannot be deleted from plain SQL with the postgres role, so the
-- heavy lifting lives in the process-deletions Edge Function (service-role
-- admin API). The job authenticates with the same Vault-stored
-- 'webhook_secret' the post/push webhooks already use:
--   SELECT vault.create_secret('<WEBHOOK_SECRET>', 'webhook_secret', ...);
--
-- Hosted Supabase: pg_cron and pg_net must be enabled in
-- Dashboard → Database → Extensions (the guarded blocks below warn instead of
-- failing the migration when privileges are missing).
-- ─────────────────────────────────────────────────────────────────────────────

DO $ext$
BEGIN
  CREATE EXTENSION IF NOT EXISTS pg_cron;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE WARNING 'pg_cron could not be created here — enable it in Dashboard → Database → Extensions, then re-run this migration.';
END;
$ext$;

DO $ext$
BEGIN
  CREATE EXTENSION IF NOT EXISTS pg_net WITH SCHEMA extensions;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE WARNING 'pg_net could not be created here — enable it in Dashboard → Database → Extensions, then re-run this migration.';
END;
$ext$;

-- Idempotent (re)schedule: 03:17 UTC daily.
DO $cron$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
    PERFORM cron.unschedule(jobname)
      FROM cron.job
     WHERE jobname = 'agora-process-account-deletions';

    PERFORM cron.schedule(
      'agora-process-account-deletions',
      '17 3 * * *',
      $job$
        SELECT net.http_post(
          url := 'https://sepvcatdqrnzjuvxabzh.supabase.co/functions/v1/process-deletions',
          headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'x-webhook-secret', (
              SELECT decrypted_secret
                FROM vault.decrypted_secrets
               WHERE name = 'webhook_secret'
               LIMIT 1
            )
          ),
          body := jsonb_build_object(
            'type', 'CRON',
            'job', 'agora-process-account-deletions'
          )
        );
      $job$
    );
  ELSE
    RAISE WARNING 'pg_cron not installed — deletion job NOT scheduled. Enable pg_cron and re-run.';
  END IF;
END;
$cron$;

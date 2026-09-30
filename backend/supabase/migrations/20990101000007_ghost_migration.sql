-- 🌟 GHOST MIGRATION — soft-closed accounts become "ghosts":
--   * their posts stay live under the "Removed User" alias,
--   * their likes/comments are removed (with post counters decremented),
--   * their real email is detached from auth.users (replaced by an
--     irreversible dummy under @ghost.agora) so the same person can
--     register a brand-new account with it later,
--   * profiles.ghost_email_hash (SHA-256 of the normalized email) preserves
--     the link needed by the registration / login intercepts WITHOUT storing
--     the plaintext email.
--
-- Companion pieces:
--   * functions/process-deletions/index.ts — soft branch now calls
--     close_account_ghost() and detaches the auth email;
--   * RegisterScreen + AuthViewModel — check_closed_account_email() gate with
--     wipe_historic_ghost_data() on "Proceed";
--   * signIn() — check_closed_account_login() pre-login intercept.

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

-- 1) Ghost link column ------------------------------------------------------

ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS ghost_email_hash text NULL;

COMMENT ON COLUMN public.profiles.ghost_email_hash IS
  'SHA-256 hex of lower(btrim(email)) captured when the account was soft-closed. '
  'Lets check_closed_account_email()/check_closed_account_login() recognize a '
  'previously-closed email without storing it in plaintext. NULL for active accounts.';

CREATE UNIQUE INDEX IF NOT EXISTS profiles_ghost_email_hash_key
  ON public.profiles (ghost_email_hash)
  WHERE ghost_email_hash IS NOT NULL;

-- 2) close_account_ghost — the whole DB-side soft close, atomically ----------
--    Called ONLY by the process-deletions edge function (service_role).
--    Deletes the ghost's likes/comments (decrementing the denormalized posts
--    counters), anonymizes the profile, flags it closed, stores the ghost hash
--    and clears the deletion schedule. The handle stays — locked forever.

CREATE OR REPLACE FUNCTION public.close_account_ghost(p_uid uuid, p_email_hash text)
  RETURNS void
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, pg_temp
  AS $function$
BEGIN
  -- Comments: decrement survivors' counters, then remove.
  UPDATE public.posts pt
     SET comments = GREATEST(0, pt.comments - c.cnt)
    FROM (
      SELECT post_id, count(*)::int AS cnt
        FROM public.comments
       WHERE user_id = p_uid
       GROUP BY post_id
    ) c
   WHERE c.post_id = pt.id;

  DELETE FROM public.comments WHERE user_id = p_uid;

  -- Likes: same treatment.
  UPDATE public.posts pt
     SET likes = GREATEST(0, pt.likes - l.cnt)
    FROM (
      SELECT post_id, count(*)::int AS cnt
        FROM public.post_likes
       WHERE user_id = p_uid
       GROUP BY post_id
    ) l
   WHERE l.post_id = pt.id;

  DELETE FROM public.post_likes WHERE user_id = p_uid;

  -- Anonymize the profile. The username (handle) is deliberately KEPT — it
  -- stays locked to this ghost so nobody can impersonate the old identity.
  UPDATE public.profiles
     SET status              = 'closed',
         ghost_email_hash    = p_email_hash,
         first_name          = NULL,
         last_name           = NULL,
         avatar_url          = NULL,
         gender              = NULL,
         dob                 = NULL,
         email               = NULL,
         fcm_token           = NULL,
         current_device_id   = NULL,
         active_session_id   = NULL,
         deletion_scheduled_at = NULL,
         deletion_mode       = NULL
   WHERE id = p_uid;
END;
$function$;

REVOKE ALL ON FUNCTION public.close_account_ghost(uuid, text) FROM public, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.close_account_ghost(uuid, text) TO service_role;

-- 3) check_closed_account_email — registration intercept ---------------------
--    True when the email once belonged to a soft-closed (ghost) account whose
--    posts were kept. Callable pre-auth: registration happens while anon.

CREATE OR REPLACE FUNCTION public.check_closed_account_email(p_email text)
  RETURNS boolean
  LANGUAGE sql
  STABLE
  SECURITY DEFINER
  SET search_path = public, pg_temp
  AS $function$
  SELECT EXISTS (
    SELECT 1
      FROM public.profiles
     WHERE status = 'closed'
       AND ghost_email_hash IS NOT NULL
       AND ghost_email_hash = encode(
             extensions.digest(lower(btrim(coalesce(p_email, ''))), 'sha256'),
             'hex')
  );
$function$;

REVOKE ALL ON FUNCTION public.check_closed_account_email(text) FROM public;
GRANT EXECUTE ON FUNCTION public.check_closed_account_email(text) TO anon, authenticated;

-- 4) check_closed_account_login — login intercept -----------------------------
--    True when the identifier is either the locked username of a closed
--    profile or the ghost email hash of one. Callable pre-auth.

CREATE OR REPLACE FUNCTION public.check_closed_account_login(p_identifier text)
  RETURNS boolean
  LANGUAGE sql
  STABLE
  SECURITY DEFINER
  SET search_path = public, pg_temp
  AS $function$
  SELECT EXISTS (
    SELECT 1
      FROM public.profiles
     WHERE status = 'closed'
       AND (
            (ghost_email_hash IS NOT NULL
             AND ghost_email_hash = encode(
                   extensions.digest(lower(btrim(coalesce(p_identifier, ''))), 'sha256'),
                   'hex'))
         OR lower(btrim(coalesce(handle, '')))
              = lower(btrim(ltrim(coalesce(p_identifier, ''), '@')))
       )
  );
$function$;

REVOKE ALL ON FUNCTION public.check_closed_account_login(text) FROM public;
GRANT EXECUTE ON FUNCTION public.check_closed_account_login(text) TO anon, authenticated;

-- 5) wipe_historic_ghost_data — the "Proceed" hammer --------------------------
--    Fired from registration when the user accepts that continuing with a
--    previously-closed email permanently deletes its kept posts. Removing the
--    profile row cascades through posts → comments/post_likes/notifications/
--    reports (see 20260928163754_remote_schema.sql FKs), freeing the handle
--    and the ghost_email_hash for good.
--
--    The banned auth.users row (already detached to a @ghost.agora dummy by
--    process-deletions) is intentionally left alone: client-triggered SQL must
--    never write to the auth schema, and the row can neither log in nor block
--    the real email. Storage objects under {uid}/ in the post-media buckets do
--    not cascade — they are inert orphans (dead links) after the wipe.

CREATE OR REPLACE FUNCTION public.wipe_historic_ghost_data(p_email text)
  RETURNS void
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, pg_temp
  AS $function$
DECLARE
  gid uuid;
BEGIN
  SELECT id INTO gid
    FROM public.profiles
   WHERE status = 'closed'
     AND ghost_email_hash IS NOT NULL
     AND ghost_email_hash = encode(
           extensions.digest(lower(btrim(coalesce(p_email, ''))), 'sha256'),
           'hex')
   LIMIT 1;

  -- Nothing to wipe (never closed, or already wiped) — idempotent success.
  IF gid IS NULL THEN
    RETURN;
  END IF;

  DELETE FROM public.profiles WHERE id = gid;
END;
$function$;

REVOKE ALL ON FUNCTION public.wipe_historic_ghost_data(text) FROM public;
GRANT EXECUTE ON FUNCTION public.wipe_historic_ghost_data(text) TO anon, authenticated;

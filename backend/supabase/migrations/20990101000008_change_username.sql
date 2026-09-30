-- 🌟 CHANGE USERNAME — server-side guardrails for handle updates.
--
-- Android flow (AccountDetailsScreen "Change Username"):
--   1. purely LOCAL email gate — typed email must exactly match the session's
--      cached email (no network round trip),
--   2. update_username(p_new_username) below — normalizes and re-validates
--      server-side, then updates public.profiles.handle under its UNIQUE
--      constraint. 23505 is re-raised as a clean "Username already taken"
--      which the client maps to a friendly error.
--
-- Data propagation is inherently relational: posts/comments/post_likes/
-- notifications/reports all reference profiles by user_id FK and every read
-- joins profiles live (e.g. the feed's profiles!fk_posts_user_id(*) embed),
-- so the new @handle appears on ALL past and future interactions at once —
-- nothing to denormalize or backfill.

-- The base schema already declares profiles_handle_key UNIQUE
-- (20260928163754_remote_schema.sql). Re-assert defensively in case the
-- constraint was ever dropped on an existing deployment.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'profiles_handle_key'
       AND conrelid = 'public.profiles'::regclass
  ) THEN
    ALTER TABLE public.profiles
      ADD CONSTRAINT profiles_handle_key UNIQUE (handle);
  END IF;
END $$;

CREATE OR REPLACE FUNCTION public.update_username(p_new_username text)
  RETURNS void
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, pg_temp
  AS $function$
DECLARE
  uid uuid := auth.uid();
  normalized text;
BEGIN
  IF uid IS NULL THEN
    RAISE EXCEPTION 'Not authenticated';
  END IF;

  -- Normalize exactly like the client: trim whitespace, strip a leading '@',
  -- force lowercase.
  normalized := lower(ltrim(btrim(coalesce(p_new_username, '')), '@'));

  -- Server-side mirror of the local validation: 3-20 characters, lowercase
  -- alphanumeric plus underscore (matches the existing 'user_xxxxxxxx'
  -- temp-handle format created at signup).
  IF normalized !~ '^[a-z0-9_]{3,20}$' THEN
    RAISE EXCEPTION 'Username must be 3-20 characters: lowercase letters, numbers or underscore';
  END IF;

  -- Closed (ghost) accounts keep their locked username forever. They are
  -- banned so they cannot authenticate anyway — belt and braces.
  IF EXISTS (
    SELECT 1 FROM public.profiles WHERE id = uid AND status = 'closed'
  ) THEN
    RAISE EXCEPTION 'This account is closed';
  END IF;

  BEGIN
    UPDATE public.profiles
       SET handle = normalized
     WHERE id = uid;
  EXCEPTION
    WHEN unique_violation THEN
      -- Re-raise with SQLSTATE 23505 preserved so the Android client can
      -- map e.code == "23505" to "Username already taken."
      RAISE EXCEPTION 'Username already taken' USING ERRCODE = '23505';
  END;
END;
$function$;

REVOKE ALL ON FUNCTION public.update_username(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.update_username(text) TO authenticated;

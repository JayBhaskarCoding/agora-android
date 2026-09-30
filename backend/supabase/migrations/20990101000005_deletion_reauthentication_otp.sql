-- Account-deletion verification via Supabase's native reauthentication OTP.
--
-- Supersedes the magic-link round trip described in migration …000004: the
-- Android client now calls auth.reauthenticate() (GoTrue emails the
-- Reauthentication template's 6-digit {{ .Token }}), the user types the code
-- into the shared OTP card, and the client posts it to
-- public.verify_deletion_otp(p_otp) below — which verifies AND stamps
-- profiles.deletion_scheduled_at = now() + 3 days atomically.
-- request_account_deletion() from …000004 remains for admin/compat use.
--
-- Why a DB-side verifier?
--   GoTrue stores the reauthentication OTP as hex(sha224(email || otp)) in
--   auth.users.reauthentication_token (see internal/crypto/crypto.go
--   GenerateTokenHash), but the /verify endpoint accepts no reauthentication
--   type, and PUT /user consumes the nonce ONLY as part of a password update
--   (and rejects it outright for SSO-managed users). There is therefore no
--   public auth API that standalone-verifies a reauthentication code, so this
--   SECURITY DEFINER function recomputes the hash exactly like GoTrue does,
--   enforces the OTP lifetime, and performs the sensitive write itself.
--   Reading auth.users relies on the standard Supabase grants for postgres.

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

CREATE OR REPLACE FUNCTION public.verify_deletion_otp(p_otp text)
  RETURNS boolean
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, extensions, pg_temp
  AS $function$
DECLARE
  uid uuid := auth.uid();
  user_email text;
  stored_hash text;
  sent_at timestamptz;
BEGIN
  IF uid IS NULL THEN
    RAISE EXCEPTION 'Not authenticated';
  END IF;

  IF p_otp IS NULL OR btrim(p_otp) = '' THEN
    RETURN false;
  END IF;

  SELECT u.email, u.reauthentication_token, u.reauthentication_sent_at
    INTO user_email, stored_hash, sent_at
    FROM auth.users u
   WHERE u.id = uid;

  IF user_email IS NULL OR stored_hash IS NULL OR stored_hash = '' OR sent_at IS NULL THEN
    -- No reauthentication code was ever requested (or it was already cleared).
    RETURN false;
  END IF;

  -- GoTrue's default OTP lifetime is one hour (config.Mailer.OtpExp = 3600s).
  IF sent_at < now() - interval '1 hour' THEN
    RETURN false;
  END IF;

  -- crypto.GenerateTokenHash(email, otp) == hex(sha224(email || otp))
  IF encode(extensions.digest(user_email || btrim(p_otp), 'sha224'), 'hex')
     IS DISTINCT FROM stored_hash THEN
    RETURN false;
  END IF;

  UPDATE public.profiles
     SET deletion_scheduled_at = now() + interval '3 days'
   WHERE id = uid;

  RETURN true;
END;
$function$;

REVOKE ALL ON FUNCTION public.verify_deletion_otp(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.verify_deletion_otp(text) TO authenticated;

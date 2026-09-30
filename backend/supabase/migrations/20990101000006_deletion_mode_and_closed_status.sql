-- Deletion MODE (soft close vs hard erase) + account status for client masking.
--
-- Flow after this migration:
--   request_account_deletion()  -> stamps reauthentication_otp_hash/sent_at
--   verify_deletion_otp(otp)    -> stamps deletion_scheduled_at = NOW() + 3 days
--   choose_deletion_mode(bool)  -> records 'soft' or 'hard' (requires the stamp)
--   process-deletions (pg_cron) -> soft: anonymize profile, ban auth user, keep
--                                  posts under a "Removed User" alias.
--                                  hard (or unset): erase everything, as before.

ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS deletion_mode text NULL,
  ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'active';

COMMENT ON COLUMN public.profiles.deletion_mode IS
  'Chosen after reauthentication: ''soft'' = close the account but keep posts '
  'under a Removed User alias; ''hard'' = erase everything. NULL until chosen.';
COMMENT ON COLUMN public.profiles.status IS
  '''active'' or ''closed''. Closed accounts are soft-deleted: profile PII wiped, '
  'handle locked, posts kept, auth user banned. Clients must mask closed authors '
  'as "Removed User" with a placeholder avatar while keeping the @username.';

ALTER TABLE public.profiles DROP CONSTRAINT IF EXISTS profiles_deletion_mode_check;
ALTER TABLE public.profiles ADD CONSTRAINT profiles_deletion_mode_check
  CHECK (deletion_mode IS NULL OR deletion_mode IN ('soft', 'hard'));

ALTER TABLE public.profiles DROP CONSTRAINT IF EXISTS profiles_status_check;
ALTER TABLE public.profiles ADD CONSTRAINT profiles_status_check
  CHECK (status IN ('active', 'closed'));

-- Step 3 of the deletion flow: after verify_deletion_otp() has stamped the
-- 3-day deadline, the user picks what the scheduled job will actually do.
CREATE OR REPLACE FUNCTION public.choose_deletion_mode(p_is_soft boolean)
  RETURNS void
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public, pg_temp
  AS $function$
DECLARE
  uid uuid := auth.uid();
BEGIN
  IF uid IS NULL THEN
    RAISE EXCEPTION 'Not authenticated';
  END IF;

  -- Only reachable once identity was re-verified and the deadline stamped.
  IF NOT EXISTS (
    SELECT 1 FROM public.profiles
     WHERE id = uid AND deletion_scheduled_at IS NOT NULL
  ) THEN
    RAISE EXCEPTION 'Deletion has not been verified';
  END IF;

  UPDATE public.profiles
     SET deletion_mode = CASE WHEN p_is_soft THEN 'soft' ELSE 'hard' END
   WHERE id = uid;
END;
$function$;

REVOKE ALL ON FUNCTION public.choose_deletion_mode(boolean) FROM public, anon;
GRANT EXECUTE ON FUNCTION public.choose_deletion_mode(boolean) TO authenticated;

-- Revert Changes now clears the chosen mode alongside the deadline, so a later
-- re-request starts from a clean slate.
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
     SET deletion_scheduled_at = NULL,
         deletion_mode = NULL
   WHERE id = auth.uid();
END;
$function$;

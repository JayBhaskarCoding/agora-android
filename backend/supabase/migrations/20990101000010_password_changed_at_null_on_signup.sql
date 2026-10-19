-- 🌟 PASSWORD LAST UPDATED — a new account has NEVER changed its password.
--
-- public.profiles.password_changed_at used to be stamped by the Android
-- onboarding save immediately after setting the signup credential. The
-- Security card then rendered "Just now" for an account that had never gone
-- through Change Password or Reset Password.
--
-- The client no longer writes this column during signup/onboarding. This
-- migration enforces the same invariant server-side:
--   1. remove any column default that could seed now() on INSERT,
--   2. force NULL on INSERT for every profile-creation path
--      (handle_new_user trigger, dashboard automation, client upsert),
--   3. repair legacy rows whose timestamp was initialised to the auth
--      account's exact creation instant.
--
-- UPDATEs from the two explicit flows (Change Password → AuthViewModel
-- updatePassword / submitNewPassword) are deliberately untouched, so those
-- flows remain the only writers.

ALTER TABLE public.profiles
  ALTER COLUMN password_changed_at DROP DEFAULT;

CREATE OR REPLACE FUNCTION public.force_password_changed_at_null_on_insert()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  AS $function$
BEGIN
  -- An account cannot have changed/reset a password before the profile row
  -- exists. Ignore anything a client or a future default tried to seed.
  NEW.password_changed_at := NULL;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS profiles_password_changed_at_null_on_insert ON public.profiles;
CREATE TRIGGER profiles_password_changed_at_null_on_insert
  BEFORE INSERT ON public.profiles
  FOR EACH ROW
  EXECUTE FUNCTION public.force_password_changed_at_null_on_insert();

COMMENT ON COLUMN public.profiles.password_changed_at IS
  'When the user last completed an explicit Change Password or Reset Password flow. NULL = never changed (always NULL for a new account).';

-- One-time cleanup: the old client stamped the column during onboarding, and
-- some deployments initialised it to the auth row's created_at. Exact equality
-- with auth.users.created_at is a safe signature of initialization — a real
-- password change always happens strictly later.
UPDATE public.profiles p
   SET password_changed_at = NULL
  FROM auth.users u
 WHERE p.id = u.id
   AND p.password_changed_at IS NOT NULL
   AND u.created_at IS NOT NULL
   AND p.password_changed_at = u.created_at;

# Email OTP registration and missing-email diagnosis

## Bug and fix

The registration button previously called password `/auth/v1/signup` with a
random password, then unconditionally set `awaitingOtp = true`. With email
confirmation disabled, signup creates an authenticated user **without sending
mail**. Repeating signup after clearing local app data does not remove that
server-side user; signup also is not a reliable code request for existing users.
The old SIGNUP resend endpoint cannot send a new signup confirmation to an
already-confirmed account.

Registration now explicitly requests `/auth/v1/otp` using `signInWith(OTP)`.
It creates a user if needed and supports existing users (including accounts
created by the old flow). Resend uses the same endpoint with `createUser = false`.
Verification uses `OtpType.Email.EMAIL`, covering both signup and magic-link
email tokens. Only a successful request opens the OTP card; only successful
verification establishes the session. Profile completeness determines onboarding
versus home. Back and the change-email button clear the local challenge without
deleting the server-side account. Concurrent sends/verifications are blocked.

## REQUIRED hosted Supabase checks

Changing `backend/supabase/config.toml` does **not** update the hosted project.
The templates in this repository configure the local Supabase stack only.

In the **same hosted project as the APK's SUPABASE_URL**:

1. Under Authentication → Providers/Sign In → Email, enable email signups and
   Confirm email. The app uses six-digit codes: set email OTP length to **6**.
2. Under Authentication → Email Templates, configure **both Confirm signup and
   Magic Link** to display `{{ .Token }}`. Copy the corresponding HTML files in
   `backend/supabase/templates/`. Existing confirmed accounts use Magic Link;
   new/unconfirmed accounts use Confirm signup. A link-only template won't show
   a code usable on this screen.
3. Verify custom SMTP is enabled and points to the intended Resend SMTP service:
   correct host/port, SMTP credentials, verified sender domain and From address.
   Keep credentials in the dashboard, not source control. Also inspect whether a
   Send Email hook overrides SMTP. If Supabase isn't using Resend, Resend's logs
   won't show these sends.
4. Check email send limits and minimum resend interval. Clearing the app's data
   does not reset server rate limits. Resending issues a new code; use the newest
   email. The UI surfaces backend failures rather than bypassing verification.

## Diagnose one controlled attempt

Record the UTC timestamp and project, then tap Send Verification Code once.
Email delivery may take longer than two or three seconds.

- Device Logcat: `adb logcat -s AuthDiagnostics` shows `[emailOtp] requesting`
  with the project host, `request accepted`, or `request failed`. It does not log
  the email address or OTP on the success/request lines. Review/redact any
  exception details before sharing logs.
- Supabase Authentication logs should show `/otp` (and `/verify` after entering
  the code), not just `/signup`. Inspect the matching status/error, especially
  SMTP errors, disabled signups, rate limits, and hook failures.
- If no matching Auth request exists, check the APK's project URL and device
  connectivity. If Auth accepted it but Resend has no event, inspect the hosted
  SMTP/hook routing. If Resend reports delivery, inspect spam/filtering, address
  spelling and mailbox delivery. Acceptance alone does not prove inbox delivery.

No Supabase log attachment was accessible in the reported conversation; hosted
configuration and delivery could not be independently verified from this repo.

## Regression checks

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` with the documented
Android SDK, JDK, local properties and Firebase configuration available.
`EmailVerificationFlowTest` covers request/resend, failure state, verification
retry, and cancellation/change of address without contacting production.

On a device against a configured test project:

- New address → email contains six digits → verify → create profile.
- Existing account from old signup → code email → verify → resume create profile.
- Complete account after clearing local data → code → verify → home.
- Resend → use latest code; rejected/expired code leaves the card visible.
- SMTP/rate-limit/network failure → actionable error, no newly opened OTP card.
- Back/change email → email form, no navigation to create profile without proof.
- Returning from Gmail without entering a code → remains on the OTP screen.

## Registration UX and password security

- The registration OTP card counts down from 60 seconds using `LaunchedEffect`
  and a monotonic deadline retained in the ViewModel. Resend is disabled for the
  full interval. Background time counts; rotation does not restart the timer.
  A successful resend resets the deadline; a failed resend leaves it expired
  so the user can retry. The ViewModel also enforces the interval independently
  of the button, and Supabase must enforce server-side rate limits.
- Separate bottom actions: **Use a different email** cancels only the local
  challenge and restores the registration form; **Back to Log In** also clears
  the registration route. Neither action verifies the email or deletes an account.
- Password creation is in registration's post-OTP **Secure Account** step
  (`OnboardingFlowScreen`). The email-only request step remains passwordless.
  The animated checklist/progress indicator shows all five requirements: eight
  characters, ASCII uppercase, ASCII lowercase, digit, and a supported punctuation
  symbol (spaces do not count as symbols). The submit button and ViewModel mutation
  boundary both use the same `PasswordPolicy`. Password input is disabled while
  saving to avoid submitting a different value than the visible one.
- Local Supabase config now requires 8 characters and
  `lower_upper_letters_digits_symbols`, with a 60-second email send interval.
  **Apply these settings separately in the hosted Supabase Authentication
  configuration**; a Git push does not deploy hosted auth settings. Client-side
  validation is UX/defense-in-depth, not a substitute for server enforcement.

Additional checks: rotate/background the OTP screen at 30s and confirm it doesn't
restart; verify the exact countdown copy and enabled copy at 0s; successfully
resend and confirm 60s restarts; fail a resend and confirm retry remains available;
test each navigation action and Android Back; type passwords missing each individual
rule and confirm checklist updates and submit stays disabled. Complete registration
with a valid password and confirm login using that password. Unit tests in
`PasswordPolicyTest` and `OtpCooldownTest` cover rule boundaries and countdown math.

## Password change and recovery flows

`AuthViewModel.startPasswordReset()` opens one screen in one of two modes
(`PasswordResetRouting`, unit-tested):

- **CHANGE** — a signed-in user tapped *Change Password* in Account Details.
  The session JWT already proves identity, so the flow opens directly on the
  new-password step and calls `updateUser { password = … }`. No emailed code is
  requested. The bottom action reads **Cancel**.
- **RECOVERY** — a signed-out user tapped *Forgot password?* on Login. The flow
  runs email → 6-digit code → new password. Verification calls
  `verifyEmailOtp(type = OtpType.Email.RECOVERY, email, token)`, which mints the
  session that authorises `updateUser`. The bottom action reads **Back to Log In**.

Both modes share the flagship styling (dark glassmorphic panel, purple pill CTA)
and the same password policy as registration: the animated
`PasswordStrengthChecklist` sits directly under the new-password field, and the
submit button stays disabled until all five rules pass. The ViewModel re-validates
with `PasswordPolicy` before mutating credentials.

The reset code countdown reuses `rememberOtpResendSeconds` and the ViewModel's
monotonic `passwordResetResendAvailableAt`, exactly like the registration card:
60 seconds, disabled resend, copy reading `Send again in Ns` while running and
`Send a new code` when finished. A successful resend restarts the window; a failed
resend leaves the button usable. Requests, verification and updates are blocked
while `isPasswordResetBusy` is true, and the ViewModel rejects a resend inside the
cooldown even if the UI is bypassed.

Manual checks: change a password while signed in (completes without email, then
sign out and sign in with the new password); sign out and use *Forgot password?*
(recovery email shows a 6-digit code, wrong code shows an error, correct code
leads to the new-password step); confirm the countdown copy at 60s and 0s, that
resend is disabled while counting and re-enabled after; rotate the screen mid
countdown; verify the disabled CTA until all five password rules turn green; and
confirm Cancel/Back to Log In return to the expected screen.

## Recovery routing — the OTP step cannot be skipped

The reset flow is no longer one composable switching on an enum. It is a real
`NavHost` graph (`ui/PasswordResetScreen.kt`) whose three destinations cannot
resolve out of order:

```
password_reset/email ──(code accepted by Supabase)──▶ password_reset/otp
                     ──(verifyEmailOtp(RECOVERY) ok)──▶ password_reset/new_password
```

**Root cause of the reported bypass.** The entry point inferred "change" versus
"reset" from `supabaseClient.auth.currentUserOrNull()`. Any leftover session —
including the temporary recovery session minted by an *earlier* verification —
made the Login screen's *Forgot password?* tap resolve as an authenticated
change and open the new-password step with no code at all.

Fixes:

- **Explicit entry points.** `startChangePassword()` (Account Details) requires a
  live session and otherwise degrades to recovery; `startPasswordRecovery()`
  (Login) ALWAYS starts at the email step, whatever session exists.
- **Ordered verification.** `verifyPasswordResetOtp()` refuses unless a code was
  accepted for the address (`isRecoveryCodeRequested`), and
  `submitNewPassword()` refuses unless identity is proven: a live session
  (CHANGE) or `isRecoverySessionVerified`, which is set only after
  `verifyEmailOtp(type = OtpType.Email.RECOVERY)` returns successfully.
- **Destination guards.** Each route re-checks its precondition on composition
  and bounces to the owning step instead of rendering, so a programmatic
  navigate (or a future deep link) still cannot reach the new-password screen.
- **Back stack.** Back walks the steps in reverse (`OTP → EMAIL` reverts the
  request state) and only exits the flow from the first step; forward moves
  never leave a completed step on the stack.
- **Return to Login.** Completing a recovery update calls `signOut()` to end the
  temporary session, pops the reset destinations, and drops the user back on
  the Login screen. A signed-in *Change Password* keeps its session and returns
  to Account Details.
- Deleted the unreferenced `ResetPasswordViewModel`, whose `verifyOtp()` advanced
  to the new-password step without calling Supabase — a bypass waiting to be
  wired up.

Manual checks: from Login, tap *Forgot password?* with a valid session in
storage and confirm the email step still appears; enter a wrong 6-digit code and
confirm the flow stays on the code step; kill and reopen the app mid-recovery and
confirm it does not resume on the new-password step; complete a recovery and
confirm the app lands on Login with no session; from Account Details confirm
*Change Password* opens the new-password step directly, keeps the session, and
returns to Account Details.

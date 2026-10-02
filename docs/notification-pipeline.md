# Agora push notification pipeline — audit, fixes and runbook

Scope: `backend/supabase/functions/**` (Supabase Edge Functions), triggered by
database webhooks on `public.notifications` and `public.posts`.
Firebase project: **`agora-application`** · FCM endpoint:
`https://fcm.googleapis.com/v1/projects/agora-application/messages:send`

---

## 1. Audit findings

The pipeline stopped dispatching pushes after the `FIREBASE_SERVICE_ACCOUNT`
secret was rotated. The old code (both `push-notification` and
`broadcast-post`) did this:

```typescript
const serviceAccount = JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT")!);
const jwtClient = new JWT({
  email: serviceAccount.client_email,
  key: serviceAccount.private_key.replace(/\\n/g, "\n"),
  scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
});
const tokens = await jwtClient.authorize();     // ← errors were opaque
// …
await fetch(fcmUrl, { … });                      // ← response never inspected
```

| # | Finding | Impact after a rotation | Status |
|---|---|---|---|
| 1 | `private_key` normalisation handled **exactly one** escaping shape (`\n` → newline). Double-escaped (`\\n`), base64-enveloped, quote-wrapped or whitespace-mangled keys threw an opaque crypto error. | Silent total outage. | ✅ `normalizePrivateKey()` handles every known shape and reports the rest with a remediation. |
| 2 | No validation of `project_id` / `private_key_id` / `client_email`. | A key from the wrong project was accepted locally and only failed at Google with `PERMISSION_DENIED`. | ✅ Hard validation against `agora-application` (overridable with `FIREBASE_EXPECTED_PROJECT_ID`). |
| 3 | `google-auth-library@9.0.0` (npm) swallowed Google's token response. | `400 invalid_grant` produced "something is wrong with the key" with no status, no body, no hint. | ✅ Token exchange rewritten with WebCrypto; the **exact status and raw body** are logged and attached to `GoogleOAuthError`. |
| 4 | FCM responses were never classified — `broadcast-post` didn't even read the response. | `401/403/404` were indistinguishable from a transient hiccup. | ✅ `FcmError` carries status + code + body + remediation; logged on every failure. |
| 5 | Stale device tokens were never removed. | Dead tokens failed on every send forever. | ✅ `UNREGISTERED` / `INVALID_ARGUMENT` purge the token from `fcm_tokens` and `profiles.fcm_token`. |
| 6 | Only `profiles.fcm_token` (one device per user) was read. | Multi-device users silently missed pushes. | ✅ `resolveRecipientTokens()` merges `profiles.fcm_token` with `fcm_tokens` when that table exists. |
| 7 | ~40 kB npm dependency per function. | Slower cold starts, larger attack surface. | ✅ Removed — zero dependencies, Deno or Node 18+. |

---

## 2. Architecture

```
INSERT notifications ──► push-notification (Edge Function)
                              │
                              ├─ resolveRecipientTokens()      profiles.fcm_token  +  fcm_tokens (optional)
                              ├─ loadServiceAccountCredentials()  parse → normalise → validate
                              ├─ getAccessToken()              oauth2.googleapis.com/token   (cached per worker)
                              ├─ sendPushMessage()             fcm.googleapis.com/v1/…/messages:send
                              │        └─ 401 → mint a fresh token, retry once
                              └─ deactivateStaleToken()        404/400 → fcm_tokens.active=false → profiles.fcm_token=NULL

INSERT posts ──────────► broadcast-post (Edge Function)        same credential + send path, topic `new_posts`
```

| File | Responsibility |
|---|---|
| `functions/_shared/firebase-service-account.ts` | Env loading, JSON/base64 decoding, PEM normalisation, project/key validation, log-safe summaries |
| `functions/_shared/google-oauth.ts` | WebCrypto JWS (RS256) signing, token exchange with full error logging, per-worker token cache |
| `functions/_shared/fcm.ts` | FCM v1 send, error taxonomy (`FcmError`), 401 retry-with-fresh-token |
| `functions/_shared/deliver-notification.ts` | Recipient-level orchestration: resolve → send → purge (unit-tested end to end) |
| `functions/_shared/token-cleanup.ts` | `resolveRecipientTokens()` and `deactivateStaleToken()` |
| `functions/push-notification/index.ts` | HTTP adapter: webhook auth, payload parse, status mapping |
| `functions/broadcast-post/index.ts` | Topic fan-out for new posts |
| `scripts/check-firebase-service-account.ts` | Offline + live credential checker (see §5) |

---

## 3. Private key handling

`normalizePrivateKey()` accepts **all** of these and returns an identical,
well-formed PKCS#8 PEM (verified by unit test — same SHA-256 fingerprint):

| Input shape | Source |
|---|---|
| real newlines in the JSON | `supabase secrets set --env-file` from the raw JSON |
| `-----BEGIN PRIVATE KEY-----\nMIIE…\n-----END…` | the ideal single-line secret |
| `…-----\\nMIIE…\\n-----…` | double-escaped by a shell / CI / secret manager |
| CRLF, trailing spaces, spaces instead of newlines | YAML block scalars, copy-paste |
| `"…"` / `'…'` wrapped | some `.env` writers |
| base64 / base64url of the whole JSON | vault exports |

It rejects, with an actionable hint: non-PEM values, encrypted keys, PKCS#1
(`BEGIN RSA PRIVATE KEY`), truncated bodies (< 512 base64 chars), and bodies
containing non-base64 characters. Every error message is key-material-free.

Validation performed on every load:

* `type == "service_account"` and all of `project_id`, `private_key_id`,
  `private_key`, `client_email` present;
* **`project_id == "agora-application"`** — mismatch is a hard error (the FCM
  URL and the key must be the same project);
* **`client_email`** must be a `@*.iam.gserviceaccount.com` service account and
  is warned about when it isn't in `@agora-application.iam.gserviceaccount.com`;
* **`private_key_id`** is logged (it is not secret) and warned about when it
  does not look like a Google key id;
* the PEM is imported by WebCrypto before use — a bad key fails at request time
  with "could not be parsed by WebCrypto" + the mangled-PEM hint.

Each successful load logs a log-safe summary:

```
[push-notification] service account loaded {
  notificationId: "…", project_id: "agora-application",
  client_email: "firebase-adminsdk-ab12x@agora-application.iam.gserviceaccount.com",
  private_key_id: "a1b2…5678", private_key_fingerprint: "b43f406e5c5e", …
}
```

`private_key_fingerprint` is the first 6 bytes of the SHA-256 of the DER key —
compare it against `openssl pkcs8 -in key.pem -outform DER | sha256sum` to prove
which key is actually deployed.

---

## 4. Error taxonomy

### OAuth (`https://oauth2.googleapis.com/token`)

Every non-2xx logs `status`, `error`, `error_description` and the **raw body**,
then throws `GoogleOAuthError` carrying all of it:

```
[google-oauth] token request failed
  status : 400
  error  : invalid_grant (Invalid JWT Signature.)
  body   : {"error":"invalid_grant","error_description":"Invalid JWT Signature."}
  hint   : Google rejected the signed assertion. (1) private_key mangled …
```

| Google error | Most likely cause |
|---|---|
| `invalid_grant` | mangled `private_key`, key deleted/disabled during rotation, `client_email` not matching the key, or clock skew |
| `unauthorized_client` / 401 | service account deleted, disabled, or wrong account |
| 403 | service account disabled or IAM Service Account Credentials API not enabled |
| no response | egress/DNS/TLS failure from the Edge Function |

### FCM (`messages:send`)

| Status / code | Meaning | Action taken |
|---|---|---|
| **401 `UNAUTHENTICATED`** | OAuth token rejected — key signing/parsing or wrong project | Retry **once** with a freshly minted token; if it still fails, mark the batch fatal (HTTP 502) |
| **403 `PERMISSION_DENIED`** | Firebase Cloud Messaging API not enabled for `agora-application`, service account lacks a messaging role, or key from another project | Fatal (502). Enable `fcm.googleapis.com` in GCP → APIs & Services → Library; grant `roles/firebase.messagingAdmin` |
| **404 `UNREGISTERED`** | Stale device token | **Purge the token** from `fcm_tokens` (active=false) and `profiles.fcm_token` |
| **400 `INVALID_ARGUMENT`** (about the registration token) | Malformed/dead token | **Purge the token** |
| 400 `INVALID_ARGUMENT` (other) | Payload problem (e.g. non-string `data` values) | Logged as failed, no purge |
| `SENDER_ID_MISMATCH` | Token issued for another Firebase project (debug build / other `google-services.json`) | Purge the token |
| 429 / `RESOURCE_EXHAUSTED` | Quota | Logged as retryable |
| 5xx / `UNAVAILABLE` / `INTERNAL` | Transient | Logged as retryable; the webhook retries |
| `THIRD_PARTY_AUTH_ERROR` | APNs/ platform config invalid | Fatal, flagged as an auth problem |

Sample log line:

```
[fcm] send failed projectId=agora-application url=https://fcm.googleapis.com/v1/projects/agora-application/messages:send
  status      : 403
  code        : PERMISSION_DENIED
  message     : Firebase Cloud Messaging API has not been used in project 123 before or it is disabled.
  staleToken  : false
  authFailure : true
  retryable   : false
  remediation : PERMISSION_DENIED — enable the Firebase Cloud Messaging API (fcm.googleapis.com) …
```

### Database cleanup (`deactivateStaleToken`)

```
UPDATE fcm_tokens SET active = false, updated_at = now() WHERE token = $1;   -- skipped if the table does not exist
                                                                             -- or falls back to DELETE when there is no `active` column
UPDATE profiles SET fcm_token = NULL WHERE id = $1 AND fcm_token = $2;        -- guard: never wipe a newer token
```

*Both statements are best-effort:* failures are collected in `warnings` and
logged, never thrown — a dead token must not turn a delivered notification into
a 500. The `fcm_token = $2` guard is deliberate: the client may have registered
a new token between the send and the cleanup.

> **Schema note.** The current database has no `fcm_tokens` table (the Android
> client writes `profiles.fcm_token` only — `PushNotificationService.onNewToken`).
> The cleanup code tries `fcm_tokens` first and transparently falls back to
> `profiles.fcm_token`, so adding a multi-device table later needs no code
> change. Today only the `profiles` statement does work.

---

## 5. Runbook: rotating the service account

1. **Generate** the key: Firebase Console → Project settings → Service accounts
   → *Generate new private key*. Confirm the project is `agora-application`.
2. **Set the secret as one line** (do not edit the JSON, keep the `\n` escapes):

   ```powershell
   # From backend/ (PowerShell) — writes a temp env file so the JSON is never a CLI argument
   $tmp = New-TemporaryFile
   "FIREBASE_SERVICE_ACCOUNT=$(Get-Content -Raw C:\secure\agora-sa.json)" | Set-Content -Encoding utf8 $tmp
   npx supabase secrets set --project-ref sepvcatdqrnzjuvxabzh --env-file $tmp
   Remove-Item $tmp
   ```

   ```bash
   # macOS / Linux
   printf 'FIREBASE_SERVICE_ACCOUNT=%s\n' "$(cat agora-sa.json)" > /tmp/sa.env
   npx supabase secrets set --project-ref sepvcatdqrnzjuvxabzh --env-file /tmp/sa.env
   rm /tmp/sa.env
   ```

   `backend/setup_agora_config.ps1` does the same (`-FirebaseServiceAccountPath`).
3. **Verify before deploying** (no network needed for the first four steps):

   ```bash
   deno run --allow-env --allow-read \
     backend/scripts/check-firebase-service-account.ts --env-file backend/.env
   # … then, once the secret is deployed, end-to-end against Google:
   deno run --allow-env --allow-net --allow-read \
     backend/scripts/check-firebase-service-account.ts --env-file backend/.env --live --token <device-token>
   ```

   `--live` performs a real OAuth exchange plus an FCM call with
   `validate_only: true` — authenticated and evaluated by Google, but never
   delivered. Exit code `0` = healthy.

   Expected healthy output:

   ```
   ✔ service account parsed, PEM normalised and validated
       project_id             : agora-application
       client_email           : firebase-adminsdk-ab12x@agora-application.iam.gserviceaccount.com
       private_key_id         : a1b2c3d4e5f60718293a4b5c6d7e8f9012345678
       private_key fingerprint: b43f406e5c5e
   ✔ service-account JWS signed (666 chars, alg RS256)
   ✔ OAuth2 bearer token acquired from https://oauth2.googleapis.com/token
   ✔ FCM accepted the message (validate_only) for device token ab12cd34… — the token is registered
   ```

4. **Deploy** the functions:

   ```bash
   cd backend
   npx supabase functions deploy push-notification --project-ref sepvcatdqrnzjuvxabzh
   npx supabase functions deploy broadcast-post   --project-ref sepvcatdqrnzjuvxabzh
   ```

5. **Watch the logs** while sending a test notification:

   ```bash
   npx supabase functions logs push-notification --project-ref sepvcatdqrnzjuvxabzh
   ```

   A healthy send logs `service account loaded` → `service-account JWS` →
   `[fcm] delivered project=agora-application messageId=projects/…/messages/…`.

---

## 6. Tests

```bash
deno test --allow-net --allow-env backend/supabase/functions/_shared/tests/
deno lint  backend/supabase/functions backend/scripts
deno fmt   backend/supabase/functions backend/scripts
```

57 tests, no external dependencies (the assertion helpers are vendored in
`_shared/tests/assert.ts` so the suite runs in air-gapped sandboxes). Coverage:

* private-key normalisation for every mangling shape + rejection cases;
* service-account validation (project, client email, base64, camelCase, quotes);
* JWS signing verified against the matching public key;
* OAuth success, `invalid_grant`, 401/403/5xx, network failure, non-JSON 200,
  token caching and forced refresh;
* FCM 200/400/401/403/404/429/503 classification and the 401 fresh-token retry;
* token cleanup (soft delete, delete fallback, missing table, guard column);
* full `deliverNotification` end-to-end against mock OAuth + FCM servers.

---

## 7. Follow-ups (not done here)

* Add the tests to CI (the repo has no workflow definitions yet).
* Optional multi-device schema: create `fcm_tokens(user_id, token, active,
  updated_at)` and have the Android client register each device. The backend
  already reads and cleans up that table.
* Delete the old key in GCP once the new one is confirmed working
  (IAM → Service accounts → Keys) — rotating is only half of the remediation.

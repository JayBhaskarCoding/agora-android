/**
 * 🌟 OAuth2 token exchange for FCM HTTP v1 (`https://oauth2.googleapis.com/token`).
 *
 * Previously this pipeline used `npm:google-auth-library@9.0.0` inside the
 * Edge Function. That hid the exchange: when JWS signing failed with the
 * rotated key, `authorize()` surfaced a generic error and the actual Google
 * response (status + body) was lost, which is exactly the signal needed to
 * diagnose `invalid_grant` / `unauthorized_client`.
 *
 * This implementation signs the service-account JWS with WebCrypto (available
 * in Deno and Node 18+, zero dependencies ~ instant cold start) and logs the
 * **exact** status code and raw body of every failed token request.
 *
 * RUNTIME: Deno (Supabase Edge Functions) or Node.js 18+.
 */

import {
  pemToDer,
  ServiceAccountConfigError,
  type ServiceAccountCredentials,
} from "./firebase-service-account.ts";

export const DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
export const FIREBASE_MESSAGING_SCOPE =
  "https://www.googleapis.com/auth/firebase.messaging";

/** How long before expiry we refresh (seconds). */
const EXPIRY_SKEW_SECONDS = 60;

export interface AccessToken {
  token: string;
  expiresAt: number; // epoch ms
  fetchedAt: number; // epoch ms
}

export interface TokenRequestOptions {
  /** Overridable for tests / proxies. Defaults to Google's token endpoint. */
  tokenUrl?: string;
  /** Bypass the in-memory cache (used when FCM reports UNAUTHENTICATED). */
  forceRefresh?: boolean;
  scope?: string;
  fetchImpl?: typeof fetch;
  now?: number;
}

/** Thrown when Google rejects the JWS or the token request fails. */
export class GoogleOAuthError extends Error {
  readonly status: number | null;
  readonly body: string;
  readonly googleError?: string;
  readonly googleErrorDescription?: string;
  readonly hint: string;

  constructor(args: {
    message: string;
    status: number | null;
    body: string;
    googleError?: string;
    googleErrorDescription?: string;
    hint: string;
  }) {
    super(args.message);
    this.name = "GoogleOAuthError";
    this.status = args.status;
    this.body = args.body;
    this.googleError = args.googleError;
    this.googleErrorDescription = args.googleErrorDescription;
    this.hint = args.hint;
  }

  /** Compact, log-friendly rendering (never contains key material). */
  toLogString(): string {
    return [
      `[google-oauth] token request failed`,
      `  status : ${this.status ?? "no response (network error)"}`,
      `  error  : ${this.googleError ?? "n/a"}${
        this.googleErrorDescription ? ` (${this.googleErrorDescription})` : ""
      }`,
      `  body   : ${truncate(this.body, 800)}`,
      `  hint   : ${this.hint}`,
    ].join("\n");
  }
}

function truncate(value: string, max: number): string {
  const oneLine = value.replace(/\s+/g, " ").trim();
  return oneLine.length > max
    ? `${oneLine.slice(0, max)}… [${oneLine.length} chars total]`
    : oneLine || "(empty body)";
}

/* -------------------------------------------------------------------------- */
/* JWS signing                                                                */
/* -------------------------------------------------------------------------- */

function base64UrlEncode(bytes: Uint8Array | string): string {
  const raw = typeof bytes === "string"
    ? new TextEncoder().encode(bytes)
    : bytes;
  let binary = "";
  for (const byte of raw) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(
    /=+$/,
    "",
  );
}

async function importPrivateKey(pem: string): Promise<CryptoKey> {
  try {
    return await crypto.subtle.importKey(
      "pkcs8",
      pemToDer(pem),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"],
    );
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    throw new ServiceAccountConfigError(
      `The service-account private key could not be parsed by WebCrypto (${detail}).`,
      "This is the classic mangled-PEM symptom after a secret rotation: re-set FIREBASE_SERVICE_ACCOUNT from the original JSON file as a single line, keeping the \\n escapes inside private_key.",
    );
  }
}

/**
 * Builds and signs the `urn:ietf:params:oauth:grant-type:jwt-bearer` assertion.
 * Exported so it can be unit-tested against the matching public key.
 */
export async function signServiceAccountJwt(
  credentials: ServiceAccountCredentials,
  options: { audience?: string; scope?: string; now?: number } = {},
): Promise<
  {
    assertion: string;
    header: Record<string, string>;
    claims: Record<string, unknown>;
  }
> {
  const now = Math.floor((options.now ?? Date.now()) / 1000);
  const audience = options.audience ?? DEFAULT_TOKEN_URL;
  const scope = options.scope ?? FIREBASE_MESSAGING_SCOPE;

  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: credentials.client_email,
    scope,
    aud: audience,
    iat: now,
    exp: now + 3600,
  };

  const signingInput = `${base64UrlEncode(JSON.stringify(header))}.${
    base64UrlEncode(JSON.stringify(claims))
  }`;
  const key = await importPrivateKey(credentials.private_key);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );

  return {
    assertion: `${signingInput}.${base64UrlEncode(new Uint8Array(signature))}`,
    header,
    claims,
  };
}

/* -------------------------------------------------------------------------- */
/* Token request                                                              */
/* -------------------------------------------------------------------------- */

function hintFor(
  status: number | null,
  googleError?: string,
  description?: string,
): string {
  if (status === null) {
    return "No HTTP response — DNS/TLS/network failure reaching oauth2.googleapis.com from the Edge Function.";
  }
  if (googleError === "invalid_grant") {
    return (
      "Google rejected the signed assertion. In order of likelihood after a rotation: " +
      "(1) the private_key is mangled (wrong newline escaping) — see normalizePrivateKey(); " +
      "(2) the old key was deleted/disabled in GCP so this key id no longer exists; " +
      "(3) client_email does not match the key (mixed credentials from two rotations); " +
      "(4) clock skew on the signer (iat/exp). " +
      `Re-check private_key_id in the secret against IAM → Service accounts → Keys.${
        description ? ` Google said: ${description}` : ""
      }`
    );
  }
  if (googleError === "unauthorized_client" || status === 401) {
    return "The service account is not authorised for this scope (deleted, disabled, or the wrong account). Confirm the client_email still exists in IAM and has the Firebase Messaging scope.";
  }
  if (status === 403) {
    return "Access denied — the service account is disabled or the IAM Service Account Credentials API is not enabled on the project.";
  }
  if (status === 400) {
    return "Malformed token request — check that client_email and the JWT audience are correct.";
  }
  if (status >= 500) {
    return "Google returned a server error — transient. The webhook will be retried; investigate only if it persists.";
  }
  return "Unexpected OAuth response. Log the body above and compare with https://developers.google.com/identity/protocols/oauth2/service-account#error-codes";
}

function parseErrorBody(
  body: string,
): { error?: string; error_description?: string } {
  try {
    const parsed = JSON.parse(body) as {
      error?: string;
      error_description?: string;
    };
    return { error: parsed.error, error_description: parsed.error_description };
  } catch {
    return {};
  }
}

/**
 * Requests an OAuth2 bearer token for FCM HTTP v1.
 *
 * On failure this always logs the exact HTTP status and the raw response body
 * (via {@link GoogleOAuthError.toLogString}) and throws a `GoogleOAuthError`
 * carrying both.
 */
export async function requestAccessToken(
  credentials: ServiceAccountCredentials,
  options: TokenRequestOptions = {},
): Promise<AccessToken> {
  const tokenUrl = options.tokenUrl ?? DEFAULT_TOKEN_URL;
  const doFetch = options.fetchImpl ?? fetch;
  const now = options.now ?? Date.now();

  const { assertion } = await signServiceAccountJwt(credentials, {
    audience: tokenUrl,
    scope: options.scope ?? FIREBASE_MESSAGING_SCOPE,
    now,
  });

  let response: Response;
  let body = "";
  try {
    response = await doFetch(tokenUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        "Accept": "application/json",
      },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }).toString(),
    });
    body = await response.text();
  } catch (error) {
    const detail = error instanceof Error
      ? `${error.name}: ${error.message}`
      : String(error);
    const oauthError = new GoogleOAuthError({
      message: `Network failure calling ${tokenUrl}: ${detail}`,
      status: null,
      body: "",
      hint: hintFor(null),
    });
    console.error(oauthError.toLogString());
    throw oauthError;
  }

  if (!response.ok) {
    const { error, error_description } = parseErrorBody(body);
    const oauthError = new GoogleOAuthError({
      message: `Google OAuth token endpoint returned HTTP ${response.status} (${
        error ?? "no error code"
      })`,
      status: response.status,
      body,
      googleError: error,
      googleErrorDescription: error_description,
      hint: hintFor(response.status, error, error_description),
    });
    console.error(oauthError.toLogString());
    throw oauthError;
  }

  let payload: {
    access_token?: string;
    expires_in?: number;
    token_type?: string;
  };
  try {
    payload = JSON.parse(body) as typeof payload;
  } catch {
    const oauthError = new GoogleOAuthError({
      message:
        "Google OAuth token endpoint returned a non-JSON body with HTTP 200.",
      status: response.status,
      body,
      hint:
        "Unexpected proxy or captive-portal response in front of oauth2.googleapis.com.",
    });
    console.error(oauthError.toLogString());
    throw oauthError;
  }

  if (!payload.access_token) {
    const oauthError = new GoogleOAuthError({
      message:
        "Google OAuth token endpoint returned HTTP 200 without an access_token.",
      status: response.status,
      body,
      hint: "Check the requested scope and the response body above.",
    });
    console.error(oauthError.toLogString());
    throw oauthError;
  }

  const expiresIn =
    typeof payload.expires_in === "number" && payload.expires_in > 0
      ? payload.expires_in
      : 3600;
  return {
    token: payload.access_token,
    expiresAt: Date.now() + (expiresIn - EXPIRY_SKEW_SECONDS) * 1000,
    fetchedAt: Date.now(),
  };
}

/* -------------------------------------------------------------------------- */
/* Caching (per Edge Function worker)                                         */
/* -------------------------------------------------------------------------- */

let cached: { key: string; accessToken: AccessToken } | null = null;

function cacheKey(credentials: ServiceAccountCredentials): string {
  return `${credentials.client_email}:${credentials.private_key_id}`;
}

/**
 * Returns a cached bearer token when possible. Tokens are reused across
 * webhook invocations in the same Edge Function worker.
 */
export async function getAccessToken(
  credentials: ServiceAccountCredentials,
  options: TokenRequestOptions = {},
): Promise<string> {
  const key = cacheKey(credentials);
  const fresh = cached && cached.key === key && !options.forceRefresh &&
    cached.accessToken.expiresAt > Date.now();

  if (fresh && cached) {
    return cached.accessToken.token;
  }

  const accessToken = await requestAccessToken(credentials, options);
  cached = { key, accessToken };
  return accessToken.token;
}

/** Test helper: drop the cached token. */
export function resetAccessTokenCache(): void {
  cached = null;
}

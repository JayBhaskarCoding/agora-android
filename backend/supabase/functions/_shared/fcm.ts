/**
 * 🌟 FCM HTTP v1 send + error taxonomy.
 *
 * `https://fcm.googleapis.com/v1/projects/agora-application/messages:send`
 *
 * Every non-2xx response is converted into an {@link FcmError} that carries the
 * HTTP status, the Google error code (UNREGISTERED, PERMISSION_DENIED, …), the
 * raw body, and a remediation hint, and is logged with all of it attached.
 *
 * The three codes that matter most after a credential rotation:
 *
 *   401 UNAUTHENTICATED  → the OAuth assertion was rejected: mangled
 *                          private_key, wrong/rotated-away key, or wrong
 *                          project. Retry once with a freshly minted token
 *                          (see sendPushMessage), then treat as a config bug.
 *   403 PERMISSION_DENIED → the Firebase Cloud Messaging API is not enabled
 *                          for `agora-application`, or the service account
 *                          lacks a messaging role.
 *   404 UNREGISTERED      → the device token is stale (app uninstalled / token
 *                          rotated client-side). The token must be removed
 *                          from the database — see token-cleanup.ts.
 *
 * RUNTIME: Deno (Supabase Edge Functions) or Node.js 18+.
 */

import {
  FIREBASE_PROJECT_ID,
  type ServiceAccountCredentials,
} from "./firebase-service-account.ts";
import {
  getAccessToken,
  GoogleOAuthError,
  type TokenRequestOptions,
} from "./google-oauth.ts";

/** `{projectId}` is substituted with the Firebase project id. */
export const FCM_SEND_URL_TEMPLATE =
  "https://fcm.googleapis.com/v1/projects/{projectId}/messages:send";

/** FCM error codes whose tokens can never be delivered to again. */
export const STALE_TOKEN_CODES = new Set([
  "UNREGISTERED",
  "NOT_FOUND",
  "SENDER_ID_MISMATCH",
  "INVALID_ARGUMENT", // only when the message is about the registration token
]);

/** FCM error codes that point at credential/IAM problems, not at devices. */
export const AUTH_ERROR_CODES = new Set([
  "UNAUTHENTICATED",
  "PERMISSION_DENIED",
]);

export interface FcmErrorBody {
  code?: string;
  message?: string;
  status?: string;
  details?: unknown[];
}

export class FcmError extends Error {
  /** HTTP status returned by fcm.googleapis.com. */
  readonly status: number;
  /** Google error status, e.g. "UNREGISTERED". */
  readonly code: string;
  /** Human-readable Google message. */
  readonly googleMessage: string;
  /** Raw response body (truncated for logging). */
  readonly body: string;
  readonly details: unknown[];
  readonly remediation: string;
  /** True when the target device token is dead and must be purged. */
  readonly staleToken: boolean;
  /** True when the credential/OAuth setup is at fault. */
  readonly authFailure: boolean;
  /** True when a later retry could succeed (5xx / quota). */
  readonly retryable: boolean;

  constructor(args: {
    status: number;
    code: string;
    googleMessage: string;
    body: string;
    details?: unknown[];
    remediation: string;
    staleToken: boolean;
    authFailure: boolean;
    retryable: boolean;
  }) {
    super(`FCM HTTP ${args.status} ${args.code}: ${args.googleMessage}`);
    this.name = "FcmError";
    this.status = args.status;
    this.code = args.code;
    this.googleMessage = args.googleMessage;
    this.body = args.body;
    this.details = args.details ?? [];
    this.remediation = args.remediation;
    this.staleToken = args.staleToken;
    this.authFailure = args.authFailure;
    this.retryable = args.retryable;
  }

  toLogString(context: Record<string, unknown> = {}): string {
    const contextLine = Object.entries(context)
      .map(([key, value]) => `${key}=${value}`)
      .join(" ");
    return [
      `[fcm] send failed ${contextLine}`,
      `  status      : ${this.status}`,
      `  code        : ${this.code}`,
      `  message     : ${this.googleMessage}`,
      `  body        : ${this.body || "(empty)"}`,
      `  staleToken  : ${this.staleToken}`,
      `  authFailure : ${this.authFailure}`,
      `  retryable   : ${this.retryable}`,
      `  remediation : ${this.remediation}`,
    ].join("\n");
  }
}

function truncate(value: string, max = 800): string {
  const oneLine = value.replace(/\s+/g, " ").trim();
  return oneLine.length > max
    ? `${oneLine.slice(0, max)}… [${oneLine.length} chars total]`
    : oneLine || "(empty body)";
}

function isRegistrationTokenComplaint(message: string): boolean {
  return /registration token|registration-token|instance id|instanceid|invalid.*token|token.*invalid|not a valid fcm/i
    .test(
      message,
    );
}

/**
 * Maps an HTTP status + Google code onto a diagnosis. Exported for tests.
 */
export function classifyFcmFailure(
  status: number,
  code: string,
  message: string,
): {
  staleToken: boolean;
  authFailure: boolean;
  retryable: boolean;
  remediation: string;
} {
  const upper = (code || "").toUpperCase();

  if (status === 401 || upper === "UNAUTHENTICATED") {
    return {
      staleToken: false,
      authFailure: true,
      retryable: true, // one retry with a freshly minted token is worthwhile
      remediation:
        "UNAUTHENTICATED — FCM rejected the OAuth token. The service-account key is almost certainly mangled " +
        "(newline escaping in FIREBASE_SERVICE_ACCOUNT), was deleted/disabled during the rotation, or belongs to " +
        'another project. Verify private_key_id + client_email in the secret against IAM, and confirm project "agora-application".',
    };
  }

  if (status === 403 || upper === "PERMISSION_DENIED") {
    return {
      staleToken: false,
      authFailure: true,
      retryable: false,
      remediation:
        "PERMISSION_DENIED — enable the Firebase Cloud Messaging API (fcm.googleapis.com) for project " +
        '"agora-application" in GCP → APIs & Services → Library, and grant the service account a messaging role ' +
        "(Firebase Admin SDK Administrator Service Agent / roles/firebase.messagingAdmin). " +
        "FCM also returns 403 when the service account is from a different project than the URL's project id.",
    };
  }

  if (status === 404 || upper === "UNREGISTERED" || upper === "NOT_FOUND") {
    return {
      staleToken: true,
      authFailure: false,
      retryable: false,
      remediation:
        "UNREGISTERED — the device token is stale (app uninstalled, token rotated by Firebase, or the app was " +
        "restored onto a new device). Delete or deactivate it in fcm_tokens / profiles.fcm_token so it is never targeted again.",
    };
  }

  if (upper === "SENDER_ID_MISMATCH") {
    return {
      staleToken: true,
      authFailure: false,
      retryable: false,
      remediation:
        "SENDER_ID_MISMATCH — the token was issued for a different Firebase project/sender (e.g. a debug build " +
        "signed with another google-services.json). Purge the token and let the client re-register.",
    };
  }

  if (status === 400 || upper === "INVALID_ARGUMENT") {
    const stale = isRegistrationTokenComplaint(message);
    return {
      staleToken: stale,
      authFailure: false,
      retryable: false,
      remediation: stale
        ? "INVALID_ARGUMENT about the registration token — treat as a stale device token and remove it from the database."
        : "INVALID_ARGUMENT — the message payload is malformed. Check that data values are strings and that the " +
          "notification/data keys are non-empty (empty strings are rejected by FCM v1).",
    };
  }

  if (
    status === 429 || upper === "RESOURCE_EXHAUSTED" ||
    upper === "QUOTA_EXCEEDED"
  ) {
    return {
      staleToken: false,
      authFailure: false,
      retryable: true,
      remediation:
        "Quota/rate limit hit — back off and retry. Check the project's FCM quota if it persists.",
    };
  }

  if (
    status === 503 || upper === "UNAVAILABLE" || status >= 500 ||
    upper === "INTERNAL"
  ) {
    return {
      staleToken: false,
      authFailure: false,
      retryable: true,
      remediation:
        "FCM is unavailable — transient. Let the webhook retry with backoff.",
    };
  }

  if (upper === "THIRD_PARTY_AUTH_ERROR") {
    return {
      staleToken: false,
      authFailure: true,
      retryable: false,
      remediation:
        "THIRD_PARTY_AUTH_ERROR — the APNs certificate / FCM platform config is invalid or expired.",
    };
  }

  return {
    staleToken: false,
    authFailure: false,
    retryable: false,
    remediation: "Unclassified FCM error — inspect the raw body above.",
  };
}

export interface SendFcmOptions {
  accessToken: string;
  /** Firebase project id. Defaults to the credentials' project (agora-application). */
  projectId?: string;
  /** Full SendMessageRequest body: `{ message: {...}, validate_only?: boolean }`. */
  payload: Record<string, unknown>;
  fetchImpl?: typeof fetch;
  /** Overrides the endpoint (tests only). */
  sendUrl?: string;
}

export interface FcmSendResult {
  /** `projects/…/messages/{message_id}` on success. */
  name?: string;
  status: number;
}

/**
 * Sends one message through FCM HTTP v1. Throws {@link FcmError} (already
 * logged) for any non-2xx response, and a plain Error for network failures.
 */
export async function sendFcmMessage(
  options: SendFcmOptions,
): Promise<FcmSendResult> {
  const projectId = options.projectId ?? FIREBASE_PROJECT_ID;
  const url = options.sendUrl ??
    FCM_SEND_URL_TEMPLATE.replace("{projectId}", projectId);
  const doFetch = options.fetchImpl ?? fetch;

  let response: Response;
  let body = "";
  try {
    response = await doFetch(url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${options.accessToken}`,
        "Content-Type": "application/json",
        "Accept": "application/json",
      },
      body: JSON.stringify(options.payload),
    });
    body = await response.text();
  } catch (error) {
    const detail = error instanceof Error
      ? `${error.name}: ${error.message}`
      : String(error);
    console.error(`[fcm] network failure calling ${url}: ${detail}`);
    throw new Error(
      `FCM request failed before a response was received: ${detail}`,
    );
  }

  if (!response.ok) {
    const parsed = safeParse(body);
    const errorBody = (parsed?.error ?? {}) as FcmErrorBody;
    const code = errorBody.status ?? errorBody.code ?? "";
    const googleMessage = errorBody.message ?? truncate(body);
    const classification = classifyFcmFailure(
      response.status,
      code,
      googleMessage,
    );
    const fcmError = new FcmError({
      status: response.status,
      code: code || "UNKNOWN",
      googleMessage,
      body: truncate(body),
      details: errorBody.details ?? [],
      ...classification,
    });
    console.error(fcmError.toLogString({ projectId, url }));
    throw fcmError;
  }

  const success = safeParse(body) ?? {};
  const name = typeof success.name === "string" ? success.name : undefined;
  console.log(
    `[fcm] delivered project=${projectId} messageId=${
      name ?? "unknown"
    } status=${response.status}`,
  );
  return { name, status: response.status };
}

function safeParse(body: string): Record<string, unknown> | null {
  if (!body) return null;
  try {
    const parsed = JSON.parse(body);
    return parsed && typeof parsed === "object"
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

export function isFcmError(error: unknown): error is FcmError {
  return error instanceof FcmError;
}

/** True when the target token is dead and must be purged from the database. */
export function isStaleTokenError(error: unknown): boolean {
  return isFcmError(error) && error.staleToken;
}

/** True when the failure is a credential/IAM problem (401 / 403). */
export function isAuthError(error: unknown): boolean {
  return isFcmError(error) && error.authFailure;
}

export interface SendPushOptions extends Omit<SendFcmOptions, "accessToken"> {
  credentials: ServiceAccountCredentials;
  tokenOptions?: TokenRequestOptions;
}

export interface SendPushResult extends FcmSendResult {
  /** 1 on the first try, 2 when a fresh OAuth token fixed an UNAUTHENTICATED. */
  attempts: number;
  retriedWithFreshToken: boolean;
}

/**
 * Convenience wrapper used by the Edge Functions: mints the bearer token
 * (cached), sends the message, and — on `UNAUTHENTICATED` — retries once with
 * a freshly minted token before giving up. That distinguishes a transient
 * token/clock issue from a genuinely broken private key.
 */
export async function sendPushMessage(
  options: SendPushOptions,
): Promise<SendPushResult> {
  const { credentials, ...rest } = options;
  const projectId = options.projectId ?? credentials.project_id ??
    FIREBASE_PROJECT_ID;

  let accessToken: string;
  try {
    accessToken = await getAccessToken(credentials, options.tokenOptions);
  } catch (error) {
    if (error instanceof GoogleOAuthError) {
      // Already logged with status + body by requestAccessToken().
      throw error;
    }
    console.error(
      `[fcm] unable to mint OAuth token: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
    throw error;
  }

  try {
    const result = await sendFcmMessage({ ...rest, accessToken, projectId });
    return { ...result, attempts: 1, retriedWithFreshToken: false };
  } catch (error) {
    if (!isFcmError(error) || !error.authFailure || error.status !== 401) {
      throw error;
    }

    console.warn(
      "[fcm] retrying once with a freshly minted OAuth token after UNAUTHENTICATED",
    );
    const freshToken = await getAccessToken(credentials, {
      ...options.tokenOptions,
      forceRefresh: true,
    });
    try {
      const result = await sendFcmMessage({
        ...rest,
        accessToken: freshToken,
        projectId,
      });
      return { ...result, attempts: 2, retriedWithFreshToken: true };
    } catch (retryError) {
      if (isFcmError(retryError)) {
        console.error(
          `${
            retryError.toLogString({ projectId, attempt: 2 })
          }\n  note: retry with a fresh token did not help — ` +
            "the service-account credential itself is invalid.",
        );
      }
      throw retryError;
    }
  }
}

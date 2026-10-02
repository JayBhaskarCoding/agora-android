/**
 * 🌟 Firebase service-account loading for the Agora push pipeline.
 *
 * WHY THIS MODULE EXISTS
 * ----------------------
 * After a credential rotation the single most common cause of "push silently
 * stops working" is a mangled `private_key`. The JSON is stored in a
 * single-line secret (`supabase secrets set FIREBASE_SERVICE_ACCOUNT=…`), so
 * the PEM's newlines survive only as escape sequences — and depending on the
 * shell / CI / secret manager that stored them they arrive as any of:
 *
 *   "-----BEGIN PRIVATE KEY-----\nMIIE…\n-----END PRIVATE KEY-----\n"  (ideal)
 *   "-----BEGIN PRIVATE KEY-----\\nMIIE…\\n-----END PRIVATE KEY-----\\n" (double-escaped)
 *   "-----BEGIN PRIVATE KEY-----\nMIIE…"  with real newlines            (fine)
 *   base64 of the whole JSON                                            (some vaults)
 *   the JSON wrapped in single/double quotes                             (some .env writers)
 *
 * The old inline `serviceAccount.private_key.replace(/\\n/g, "\n")` handled
 * exactly one of those shapes and threw an opaque crypto error for the rest.
 * `normalizePrivateKey()` below handles all of them, and every failure path
 * throws a `ServiceAccountConfigError` whose message names the actual problem
 * and the remediation — without ever logging the key material itself.
 *
 * RUNTIME: Deno (Supabase Edge Functions) or Node.js 18+. No npm imports.
 */

/** Firebase project the Agora push pipeline belongs to. */
export const FIREBASE_PROJECT_ID = "agora-application";

/**
 * Escape hatch for pointing the same code at a staging project. In production
 * this is unset and the expected project is {@link FIREBASE_PROJECT_ID}.
 */
const EXPECTED_PROJECT_ENV = "FIREBASE_EXPECTED_PROJECT_ID";

/** Environment variable holding the (single-line) service-account JSON. */
export const SERVICE_ACCOUNT_ENV = "FIREBASE_SERVICE_ACCOUNT";

export interface ServiceAccountCredentials {
  type: string;
  project_id: string;
  private_key_id: string;
  private_key: string;
  client_email: string;
  client_id?: string;
  auth_uri?: string;
  token_uri?: string;
}

/** Log-safe view of loaded credentials. Never contains key material. */
export interface CredentialsSummary {
  project_id: string;
  client_email: string;
  private_key_id: string;
  /** Short SHA-256 fingerprint of the PEM — proves *which* key is loaded. */
  private_key_fingerprint: string;
  private_key_bytes: number;
  source: string;
}

/** Thrown for every misconfiguration. `hint` is the operator remediation. */
export class ServiceAccountConfigError extends Error {
  readonly hint: string;

  constructor(message: string, hint = "") {
    super(hint ? `${message}\n  → ${hint}` : message);
    this.name = "ServiceAccountConfigError";
    this.hint = hint;
  }
}

/* -------------------------------------------------------------------------- */
/* Environment access (Deno first, Node second)                               */
/* -------------------------------------------------------------------------- */

type EnvReader = {
  env?:
    & { get?(name: string): string | undefined }
    & Record<string, string | undefined>;
};

function readEnvVar(name: string): string | undefined {
  const globals = globalThis as unknown as {
    Deno?: EnvReader;
    process?: EnvReader;
  };

  if (typeof globals.Deno !== "undefined" && globals.Deno.env) {
    const value = globals.Deno.env.get?.(name);
    if (value !== undefined) return value;
  }
  const processEnv = globals.process?.env;
  if (processEnv) return processEnv[name];
  return undefined;
}

/* -------------------------------------------------------------------------- */
/* Private-key normalisation                                                  */
/* -------------------------------------------------------------------------- */

const PEM_BLOCK =
  /-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----([\s\S]*?)-----END \1-----/;

/**
 * Turns any of the mangled shapes above into a well-formed PEM block:
 * 64-character base64 lines, LF endings, trailing newline.
 *
 * Throws {@link ServiceAccountConfigError} when the value cannot possibly be a
 * usable key, so failures surface at request time with an actionable message
 * instead of an "invalid PEM" crypto error.
 */
export function normalizePrivateKey(raw: string): string {
  if (typeof raw !== "string" || raw.trim() === "") {
    throw new ServiceAccountConfigError(
      "`private_key` is missing or empty in the service-account JSON.",
      "Re-download the key from Firebase Console → Project settings → Service accounts → Generate new private key.",
    );
  }

  let key = raw.trim();

  // 1. Undo `"` → newline escaping. Loops because secret managers and some
  //    CI systems escape the escape, producing `\\n`.
  for (let pass = 0; pass < 5 && key.includes("\\n"); pass++) {
    key = key.replace(/\\n/g, "\n");
  }

  // 2. Strip quote wrapping left behind by .env / shell quoting.
  if (
    key.length > 1 &&
    ((key.startsWith('"') && key.endsWith('"')) ||
      (key.startsWith("'") && key.endsWith("'")))
  ) {
    key = key.slice(1, -1).trim();
  }

  // 3. Normalise line endings and any literal `\r` left over from escaping.
  key = key.replace(/\r\n?/g, "\n").replace(/\\r/g, "").trim();

  const match = key.match(PEM_BLOCK);
  if (!match) {
    throw new ServiceAccountConfigError(
      "`private_key` is not a PEM block (no -----BEGIN … PRIVATE KEY----- … -----END … PRIVATE KEY----- wrapper).",
      "The key was truncated or over-escaped when the secret was set. Re-set it as one line of JSON, e.g. " +
        "supabase secrets set --env-file <(printf 'FIREBASE_SERVICE_ACCOUNT=%s\\n' \"$(cat key.json)\").",
    );
  }

  const label = match[1];
  if (/ENCRYPTED/.test(label)) {
    throw new ServiceAccountConfigError(
      "`private_key` is an ENCRYPTED private key; the Edge Function cannot supply a passphrase.",
      "Convert it to unencrypted PKCS#8: openssl pkcs8 -topk8 -nocrypt -in encrypted.pem -out key.pem",
    );
  }
  // Only unencrypted PKCS#8 ("-----BEGIN PRIVATE KEY-----") is importable.
  // PKCS#1 ("RSA PRIVATE KEY"), EC, and public-key PEMs all land here.
  if (label !== "PRIVATE KEY") {
    throw new ServiceAccountConfigError(
      `\`private_key\` uses the PKCS#1-style header "-----BEGIN ${label}-----" but the signer needs PKCS#8 ("-----BEGIN PRIVATE KEY-----").`,
      "Convert it: openssl pkcs8 -topk8 -nocrypt -in rsa-key.pem -out pkcs8-key.pem (Firebase always issues PKCS#8 — this usually means the key was hand-edited).",
    );
  }

  // 4. Rebuild the body: drop every whitespace artefact (real newlines, spaces
  //    injected by YAML/env wrappers, stray backslashes) and re-wrap at 64.
  const body = match[2].replace(/[\s\\]+/g, "");
  if (!/^[A-Za-z0-9+/=_-]+$/.test(body)) {
    throw new ServiceAccountConfigError(
      "`private_key` PEM body contains characters that are not valid base64.",
      "The key was corrupted in transit (common when a secret is copied through a shell without quoting). Re-set the secret from the original JSON file.",
    );
  }
  if (body.length < 512) {
    throw new ServiceAccountConfigError(
      `\`private_key\` PEM body is only ${body.length} base64 characters — too short for an RSA key.`,
      "The value was truncated (some CLIs cap secret length / split on newlines). Re-set the secret from the original JSON file.",
    );
  }

  const wrapped = body.match(/.{1,64}/g)?.join("\n") ?? "";
  return `-----BEGIN ${label}-----\n${wrapped}\n-----END ${label}-----\n`;
}

/** Decodes the base64 body of a PEM block into raw DER bytes. */
export function pemToDer(pem: string): ArrayBuffer {
  const match = pem.match(PEM_BLOCK);
  if (!match) {
    throw new ServiceAccountConfigError(
      "Cannot decode a value that is not a PEM block.",
      "Normalise the key with normalizePrivateKey() first.",
    );
  }
  const b64 = match[2].replace(/[\s\\]+/g, "").replace(/-/g, "+").replace(
    /_/g,
    "/",
  );
  const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  // Returned as ArrayBuffer (not Uint8Array) so it satisfies `BufferSource`
  // across TypeScript versions.
  return bytes.buffer;
}

/* -------------------------------------------------------------------------- */
/* JSON decoding                                                              */
/* -------------------------------------------------------------------------- */

function looksLikeBase64(value: string): boolean {
  return !value.includes("{") &&
    /^[A-Za-z0-9+/\r\n]+={0,2}$/.test(value.trim());
}

function decodeBase64Json(value: string): string {
  const cleaned = value.replace(/\s+/g, "").replace(/-/g, "+").replace(
    /_/g,
    "/",
  );
  const padded = cleaned + "=".repeat((4 - (cleaned.length % 4)) % 4);
  return atob(padded);
}

/**
 * Accepts raw JSON, quoted JSON, base64 JSON, or double-encoded JSON and
 * returns the parsed object. Every failure names the offending shape.
 */
export function decodeServiceAccountPayload(
  raw: string,
  source = SERVICE_ACCOUNT_ENV,
): Record<string, unknown> {
  if (typeof raw !== "string" || raw.trim() === "") {
    throw new ServiceAccountConfigError(
      `${source} is missing or empty.`,
      'Set it with: supabase secrets set FIREBASE_SERVICE_ACCOUNT="$(cat service-account.json)" (hosted) or add it to backend/.env (local).',
    );
  }

  // Candidate strings are tried in order: raw, quote-stripped, base64-decoded.
  const candidates: string[] = [];
  const pushCandidate = (value: string) => {
    const trimmed = value.trim();
    if (trimmed && !candidates.includes(trimmed)) candidates.push(trimmed);
  };

  pushCandidate(raw);

  // Wrapped in quotes (single-line .env / YAML scalars) — possibly repeatedly.
  let unwrapped = raw.trim();
  while (
    unwrapped.length > 1 &&
    ((unwrapped.startsWith('"') && unwrapped.endsWith('"')) ||
      (unwrapped.startsWith("'") && unwrapped.endsWith("'")))
  ) {
    unwrapped = unwrapped.slice(1, -1).trim();
    pushCandidate(unwrapped);
  }

  // Base64 / base64url envelope (some vaults and CI secret stores).
  for (const value of [...candidates]) {
    if (!looksLikeBase64(value)) continue;
    try {
      const decoded = decodeBase64Json(value);
      if (decoded.trim().startsWith("{")) pushCandidate(decoded);
    } catch {
      /* not base64 — the JSON error below is clearer */
    }
  }

  let lastError: unknown = null;
  for (const candidate of candidates) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(candidate);
    } catch (error) {
      lastError = error;
      continue;
    }

    // Double-encoded: `"{\"private_key\": …}"`.
    if (typeof parsed === "string") {
      try {
        parsed = JSON.parse(parsed);
      } catch (error) {
        lastError = error;
        continue;
      }
    }

    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
    lastError = new Error("value is not a JSON object");
  }

  const detail = lastError instanceof Error
    ? lastError.message
    : String(lastError);
  const snippet = candidates[0].slice(0, 120).replace(
    /(\\n|-----BEGIN[^-]*-----).*/,
    "$1…",
  );
  throw new ServiceAccountConfigError(
    `${source} is not valid JSON (${detail}). Received ${
      candidates[0].length
    } chars starting with: ${snippet}`,
    "Store the service-account JSON as a single line. If you paste it into .env, keep the \\n escapes inside private_key intact.",
  );
}

/** Maps the camelCase keys some secret stores emit onto Google's snake_case. */
function toSnakeCase(record: Record<string, unknown>): Record<string, unknown> {
  const aliases: Record<string, string> = {
    projectId: "project_id",
    privateKey: "private_key",
    privateKeyId: "private_key_id",
    clientEmail: "client_email",
    clientId: "client_id",
    tokenUri: "token_uri",
  };
  const out: Record<string, unknown> = { ...record };
  for (const [alias, canonical] of Object.entries(aliases)) {
    if (
      typeof record[alias] === "string" && typeof out[canonical] !== "string"
    ) {
      out[canonical] = record[alias];
    }
  }
  return out;
}

/* -------------------------------------------------------------------------- */
/* Validation                                                                 */
/* -------------------------------------------------------------------------- */

export function expectedProjectId(): string {
  return readEnvVar(EXPECTED_PROJECT_ENV)?.trim() || FIREBASE_PROJECT_ID;
}

/**
 * Parses, normalises and validates a service account for the Agora project.
 * Throws {@link ServiceAccountConfigError} on any problem — callers can surface
 * the message directly because it never contains key material.
 */
export function parseServiceAccount(
  raw: string,
  source = SERVICE_ACCOUNT_ENV,
): ServiceAccountCredentials {
  const record = toSnakeCase(decodeServiceAccountPayload(raw, source));

  const required = [
    "project_id",
    "private_key",
    "client_email",
    "private_key_id",
  ] as const;
  for (const field of required) {
    if (
      typeof record[field] !== "string" ||
      (record[field] as string).trim() === ""
    ) {
      throw new ServiceAccountConfigError(
        `Service account JSON is missing "${field}".`,
        "Download a fresh key: Firebase Console → Project settings → Service accounts → Generate new private key.",
      );
    }
  }

  if (typeof record.type === "string" && record.type !== "service_account") {
    throw new ServiceAccountConfigError(
      `Service account JSON has type "${record.type}" (expected "service_account").`,
      "This looks like a Firebase Admin SDK config snippet or an OAuth client — use the service-account key file instead.",
    );
  }

  const expected = expectedProjectId();
  const projectId = (record.project_id as string).trim();

  // Normalise first: a bad PEM must be reported as a key problem, not silently
  // accepted and then rejected 3 layers later by the crypto API.
  const privateKey = normalizePrivateKey(record.private_key as string);
  const privateKeyId = (record.private_key_id as string).trim();
  const clientEmail = (record.client_email as string).trim();

  if (projectId !== expected) {
    throw new ServiceAccountConfigError(
      `Service account belongs to project "${projectId}" but this pipeline targets "${expected}".`,
      `After a rotation the wrong project's key is a common mistake — the FCM endpoint and the key must both be for "${expected}". ` +
        `Set ${EXPECTED_PROJECT_ENV} only if this pipeline is intentionally pointed at another project.`,
    );
  }

  if (!clientEmail.endsWith(".iam.gserviceaccount.com")) {
    throw new ServiceAccountConfigError(
      `client_email "${clientEmail}" is not a Google service account.`,
      "Expected <name>@<project>.iam.gserviceaccount.com from the Firebase service-accounts page.",
    );
  }

  const expectedDomain = `@${projectId}.iam.gserviceaccount.com`;
  if (!clientEmail.endsWith(expectedDomain)) {
    // Not fatal (cross-project service accounts can be granted messaging
    // access), but it is the #1 rotation mistake, so make it loud.
    console.warn(
      `[firebase-sa] client_email "${clientEmail}" is not in the expected domain "${expectedDomain}". ` +
        "FCM v1 normally requires the service account to belong to the same Firebase project — pushes will likely fail with PERMISSION_DENIED.",
    );
  }

  if (!/^[a-f0-9]{20,}$/i.test(privateKeyId)) {
    console.warn(
      `[firebase-sa] private_key_id "${privateKeyId}" does not look like a Google key id — the value may have been truncated during rotation.`,
    );
  }

  return {
    type: "service_account",
    project_id: projectId,
    private_key_id: privateKeyId,
    private_key: privateKey,
    client_email: clientEmail,
    client_id: typeof record.client_id === "string"
      ? record.client_id
      : undefined,
    token_uri: typeof record.token_uri === "string"
      ? record.token_uri
      : undefined,
  };
}

/** Reads and validates the service account from the environment. */
export function loadServiceAccountCredentials(): ServiceAccountCredentials {
  const raw = readEnvVar(SERVICE_ACCOUNT_ENV);
  return parseServiceAccount(raw ?? "", SERVICE_ACCOUNT_ENV);
}

/** Short fingerprint of the loaded key — safe to log, proves which key is live. */
export async function privateKeyFingerprint(pem: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", pemToDer(pem));
  return Array.from(new Uint8Array(digest))
    .slice(0, 6)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Logs the credential summary once per worker (and again if the deployed key
 * changes), so a rotation is visible in the function logs without repeating
 * the block on every webhook invocation. Returns the summary either way.
 */
let lastLoggedFingerprint: string | null = null;

export async function logCredentialsSummary(
  credentials: ServiceAccountCredentials,
  context: Record<string, unknown> = {},
  source = SERVICE_ACCOUNT_ENV,
): Promise<CredentialsSummary> {
  const summary = await describeCredentials(credentials, source);
  if (summary.private_key_fingerprint !== lastLoggedFingerprint) {
    lastLoggedFingerprint = summary.private_key_fingerprint;
    console.log("[firebase-sa] service account in use", {
      ...context,
      ...summary,
    });
  }
  return summary;
}

/** Test helper: forget the "already logged" state. */
export function resetCredentialsLogState(): void {
  lastLoggedFingerprint = null;
}

/** Log-safe description of the credentials currently in use. */
export async function describeCredentials(
  credentials: ServiceAccountCredentials,
  source = SERVICE_ACCOUNT_ENV,
): Promise<CredentialsSummary> {
  return {
    project_id: credentials.project_id,
    client_email: credentials.client_email,
    private_key_id: credentials.private_key_id,
    private_key_fingerprint: await privateKeyFingerprint(
      credentials.private_key,
    ),
    private_key_bytes: credentials.private_key.length,
    source,
  };
}

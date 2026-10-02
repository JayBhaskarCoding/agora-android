#!/usr/bin/env -S deno run --allow-env --allow-net --allow-read
/**
 * 🌟 Post-rotation credential checker for the Agora push pipeline.
 *
 * Run this *before* deploying (and immediately after rotating the Firebase
 * service account) to prove the secret is usable. It performs the same steps
 * the Edge Functions perform, in order, and stops at the first failure with a
 * remediation:
 *
 *   1. reads FIREBASE_SERVICE_ACCOUNT from the environment (or an env file)
 *   2. parses + normalises + validates it (project, client_email, key id)
 *   3. signs a service-account JWS with that key
 *   4. exchanges it for an OAuth2 bearer token at oauth2.googleapis.com
 *      (prints Google's exact status + body on failure)
 *   5. calls FCM v1 with `validate_only: true` — a real, authenticated send
 *      that Google evaluates but never delivers
 *
 * Usage
 * -----
 *   # Offline checks only (parse + sign + validate the key):
 *   deno run --allow-env --allow-read backend/scripts/check-firebase-service-account.ts
 *
 *   # Full end-to-end against Google (needs egress):
 *   deno run --allow-env --allow-net --allow-read \
 *     backend/scripts/check-firebase-service-account.ts --live
 *
 *   # Read the secret straight from backend/.env (never committed):
 *   deno run --allow-env --allow-read --allow-net \
 *     backend/scripts/check-firebase-service-account.ts --env-file backend/.env --live
 *
 *   # Target a specific device token (validates the token too):
 *   ... --token <fcm-device-token>
 *
 * Exit code is 0 when every step succeeds, 1 otherwise.
 */

import {
  describeCredentials,
  FIREBASE_PROJECT_ID,
  loadServiceAccountCredentials,
  parseServiceAccount,
  ServiceAccountConfigError,
} from "../supabase/functions/_shared/firebase-service-account.ts";
import {
  DEFAULT_TOKEN_URL,
  GoogleOAuthError,
  requestAccessToken,
  signServiceAccountJwt,
} from "../supabase/functions/_shared/google-oauth.ts";
import { FcmError, sendFcmMessage } from "../supabase/functions/_shared/fcm.ts";

interface Options {
  live: boolean;
  envFile?: string;
  token?: string;
  tokenFile?: string;
}

function parseArgs(argv: string[]): Options {
  const options: Options = { live: false };
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (arg === "--live" || arg === "-l") options.live = true;
    else if (arg === "--env-file") options.envFile = argv[++index];
    else if (arg === "--token") options.token = argv[++index];
    else if (arg === "--token-file") options.tokenFile = argv[++index];
    else if (arg === "--help" || arg === "-h") {
      console.log(
        Deno.readTextFileSync(new URL(import.meta.url)).split("*/")[0].replace(
          /^\/\*\*!?/,
          "",
        ),
      );
      Deno.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return options;
}

/** Minimal .env reader — only used to locate the secret locally. */
async function loadEnvFile(path: string): Promise<void> {
  const text = await Deno.readTextFile(path);
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator === -1) continue;
    const name = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if (
      value.length > 1 &&
      ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    if (!Deno.env.get(name)) Deno.env.set(name, value);
  }
}

const GREEN = "\x1b[32m";
const RED = "\x1b[31m";
const YELLOW = "\x1b[33m";
const RESET = "\x1b[0m";

function ok(message: string): void {
  console.log(`${GREEN}✔${RESET} ${message}`);
}
function warn(message: string): void {
  console.log(`${YELLOW}!${RESET} ${message}`);
}
function fail(message: string): void {
  console.error(`${RED}✘${RESET} ${message}`);
}

async function main(): Promise<number> {
  const options = parseArgs(Deno.args);

  if (options.envFile) {
    await loadEnvFile(options.envFile);
    ok(`loaded environment from ${options.envFile}`);
  }

  const raw = Deno.env.get("FIREBASE_SERVICE_ACCOUNT");

  // Step 1 + 2 — parse, normalise, validate.
  let credentials;
  try {
    credentials = raw
      ? parseServiceAccount(raw)
      : loadServiceAccountCredentials();
  } catch (error) {
    if (error instanceof ServiceAccountConfigError) {
      fail("service account rejected");
      console.error(`  ${error.message.replace(/\n/g, "\n  ")}`);
      return 1;
    }
    throw error;
  }
  ok("service account parsed, PEM normalised and validated");

  const summary = await describeCredentials(credentials);
  console.log(
    "    project_id             :",
    summary.project_id,
    summary.project_id === FIREBASE_PROJECT_ID ? "" : "(!! unexpected)",
  );
  console.log("    client_email           :", summary.client_email);
  console.log("    private_key_id         :", summary.private_key_id);
  console.log(
    "    private_key fingerprint:",
    summary.private_key_fingerprint,
    "(first 6 bytes of SHA-256 of the DER)",
  );
  console.log(
    "    private_key length     :",
    summary.private_key_bytes,
    "chars",
  );

  if (summary.project_id !== FIREBASE_PROJECT_ID) {
    warn(
      `expected project "${FIREBASE_PROJECT_ID}" — FCM will be addressed at /v1/projects/${FIREBASE_PROJECT_ID}/messages:send`,
    );
  }

  // Step 3 — sign the JWS locally (no network).
  try {
    const { assertion } = await signServiceAccountJwt(credentials);
    ok(`service-account JWS signed (${assertion.length} chars, alg RS256)`);
  } catch (error) {
    fail("signing with the service-account key failed");
    console.error(
      `  ${error instanceof Error ? error.message : String(error)}`,
    );
    return 1;
  }

  if (!options.live) {
    console.log(
      `\nOffline checks passed. Re-run with ${YELLOW}--live${RESET} to hit ${DEFAULT_TOKEN_URL} and FCM.`,
    );
    return 0;
  }

  // Step 4 — exchange the assertion for a bearer token.
  let accessToken: string;
  try {
    const token = await requestAccessToken(credentials);
    accessToken = token.token;
    ok(
      `OAuth2 bearer token acquired from ${DEFAULT_TOKEN_URL} (expires in ~${
        Math.round((token.expiresAt - Date.now()) / 1000)
      }s)`,
    );
  } catch (error) {
    if (error instanceof GoogleOAuthError) {
      fail(
        "Google rejected the token request — the exact response was logged above",
      );
      console.error(`  status: ${error.status ?? "no response"}`);
      console.error(
        `  error : ${error.googleError ?? "n/a"}${
          error.googleErrorDescription
            ? ` — ${error.googleErrorDescription}`
            : ""
        }`,
      );
      console.error(`  hint  : ${error.hint}`);
      return 1;
    }
    throw error;
  }

  // Step 5 — authenticated, non-delivering FCM call (validate_only).
  const target = options.token ??
    (options.tokenFile
      ? (await Deno.readTextFile(options.tokenFile)).trim()
      : undefined);

  const payload: Record<string, unknown> = {
    validate_only: true,
    message: target
      ? {
        token: target,
        notification: {
          title: "Agora credential check",
          body: "validate_only — not delivered",
        },
      }
      : {
        topic: "new_posts",
        data: {
          title: "Agora credential check",
          body: "validate_only — not delivered",
        },
      },
  };

  try {
    const result = await sendFcmMessage({ accessToken, payload });
    ok(
      target
        ? `FCM accepted the message (validate_only) for device token ${
          target.slice(0, 8)
        }… — the token is registered`
        : `FCM accepted the message (validate_only) for topic new_posts — credentials and project are correct`,
    );
    if (result.name) console.log("    message name:", result.name);
    return 0;
  } catch (error) {
    if (error instanceof FcmError) {
      fail(`FCM rejected the request: HTTP ${error.status} ${error.code}`);
      console.error(`  message    : ${error.googleMessage}`);
      console.error(`  body       : ${error.body}`);
      console.error(`  remediation: ${error.remediation}`);
      if (error.staleToken) {
        console.error(
          "  → the device token is dead; purge it from profiles.fcm_token / fcm_tokens.",
        );
      }
      return 1;
    }
    throw error;
  }
}

const exitCode = await main();
Deno.exit(exitCode);

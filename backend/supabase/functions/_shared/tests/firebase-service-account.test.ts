/**
 * Service-account parsing / private-key normalisation tests.
 *
 * These cover the exact failure modes that took push delivery down after the
 * credential rotation: escaped and double-escaped newlines, base64 envelopes,
 * quoted .env values, truncated keys and wrong-project keys.
 */

import {
  assertEquals,
  assertRejects,
  assertStringIncludes,
  assertThrows,
} from "./assert.ts";
import {
  FIREBASE_PROJECT_ID,
  loadServiceAccountCredentials,
  normalizePrivateKey,
  parseServiceAccount,
  pemToDer,
  ServiceAccountConfigError,
} from "../firebase-service-account.ts";
import {
  serviceAccountObject,
  TEST_CLIENT_EMAIL,
  TEST_PRIVATE_KEY_ID,
  testKeyPair,
  toPem,
} from "./test-utils.ts";

const { privateKeyPem } = await testKeyPair();

/** The PEM as it appears inside a JSON string (escaped \n). */
function escapedPem(pem = privateKeyPem): string {
  return pem.replace(/\n/g, "\\n");
}

function jsonOf(record: Record<string, unknown>): string {
  return JSON.stringify(record);
}

Deno.test("normalizePrivateKey keeps an already well-formed PEM intact", () => {
  assertEquals(normalizePrivateKey(privateKeyPem), privateKeyPem);
});

Deno.test("normalizePrivateKey un-escapes literal \\n sequences", () => {
  const normalized = normalizePrivateKey(escapedPem());
  assertEquals(normalized, privateKeyPem);
  assertStringIncludes(normalized, "-----BEGIN PRIVATE KEY-----");
});

Deno.test("normalizePrivateKey un-escapes double-escaped \\\\n sequences", () => {
  const doubleEscaped = privateKeyPem.replace(/\n/g, "\\\\n");
  assertEquals(normalizePrivateKey(doubleEscaped), privateKeyPem);
});

Deno.test("normalizePrivateKey handles CRLF and stray whitespace", () => {
  const crlf = privateKeyPem.replace(/\n/g, "\r\n");
  assertEquals(normalizePrivateKey(crlf), privateKeyPem);

  const spaced = privateKeyPem.replace(/\n/g, "   \n  ");
  assertEquals(normalizePrivateKey(spaced), privateKeyPem);
});

Deno.test("normalizePrivateKey accepts quoted and unwrapped variants", () => {
  assertEquals(normalizePrivateKey(`"${escapedPem()}"`), privateKeyPem);
  assertEquals(normalizePrivateKey(`  ${privateKeyPem}  `), privateKeyPem);
});

Deno.test("normalizePrivateKey rejects non-PEM, encrypted, PKCS#1 and truncated keys", () => {
  assertThrows(
    () =>
      normalizePrivateKey("MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQ"),
    ServiceAccountConfigError,
    "not a PEM block",
  );

  assertThrows(
    () =>
      normalizePrivateKey(
        `-----BEGIN ENCRYPTED PRIVATE KEY-----\n${
          "A".repeat(800)
        }\n-----END ENCRYPTED PRIVATE KEY-----\n`,
      ),
    ServiceAccountConfigError,
    "ENCRYPTED",
  );

  assertThrows(
    () =>
      normalizePrivateKey(
        `-----BEGIN RSA PRIVATE KEY-----\n${
          "A".repeat(800)
        }\n-----END RSA PRIVATE KEY-----\n`,
      ),
    ServiceAccountConfigError,
    "PKCS#8",
  );

  assertThrows(
    () =>
      normalizePrivateKey(
        `-----BEGIN PRIVATE KEY-----\n${
          "A".repeat(64)
        }\n-----END PRIVATE KEY-----\n`,
      ),
    ServiceAccountConfigError,
    "too short",
  );
});

Deno.test("pemToDer round-trips the key bytes", async () => {
  const der = new Uint8Array(pemToDer(normalizePrivateKey(escapedPem())));
  assertEquals(der.length > 1000, true);
  // The DER must still be importable — proves the normalisation is lossless.
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    [
      "sign",
    ],
  );
  assertEquals(key.type, "private");
});

Deno.test("parseServiceAccount accepts single-line JSON with escaped newlines", () => {
  const credentials = parseServiceAccount(
    jsonOf(serviceAccountObject({ privateKey: escapedPem() })),
  );
  assertEquals(credentials.project_id, FIREBASE_PROJECT_ID);
  assertEquals(credentials.client_email, TEST_CLIENT_EMAIL);
  assertEquals(credentials.private_key_id, TEST_PRIVATE_KEY_ID);
  assertEquals(credentials.private_key, privateKeyPem);
});

Deno.test("parseServiceAccount accepts JSON with real newlines", () => {
  const credentials = parseServiceAccount(
    jsonOf(serviceAccountObject({ privateKey: privateKeyPem })),
  );
  assertEquals(credentials.private_key, privateKeyPem);
});

Deno.test("parseServiceAccount accepts a base64 envelope", () => {
  const json = jsonOf(serviceAccountObject({ privateKey: escapedPem() }));
  const encoded = btoa(json);
  const credentials = parseServiceAccount(encoded);
  assertEquals(credentials.private_key, privateKeyPem);
});

Deno.test("parseServiceAccount accepts quote-wrapped and double-encoded JSON", () => {
  const credentials = parseServiceAccount(
    `"${
      jsonOf(serviceAccountObject({ privateKey: escapedPem() })).replace(
        /"/g,
        '\\"',
      )
    }"`,
  );
  assertEquals(credentials.private_key, privateKeyPem);
});

Deno.test("parseServiceAccount accepts camelCase keys from secret managers", () => {
  const camel = {
    type: "service_account",
    projectId: FIREBASE_PROJECT_ID,
    privateKeyId: TEST_PRIVATE_KEY_ID,
    privateKey: escapedPem(),
    clientEmail: TEST_CLIENT_EMAIL,
  };
  const credentials = parseServiceAccount(JSON.stringify(camel));
  assertEquals(credentials.private_key, privateKeyPem);
  assertEquals(credentials.project_id, FIREBASE_PROJECT_ID);
});

Deno.test("parseServiceAccount rejects invalid JSON with a useful message", () => {
  const error = assertThrows(
    () => parseServiceAccount('{"private_key": "abc"'),
    ServiceAccountConfigError,
    "not valid JSON",
  ) as ServiceAccountConfigError;
  assertStringIncludes(error.hint, "single line");
});

Deno.test("parseServiceAccount reports missing fields", () => {
  assertThrows(
    () =>
      parseServiceAccount(
        jsonOf(
          serviceAccountObject({
            privateKey: escapedPem(),
            omit: ["private_key_id"],
          }),
        ),
      ),
    ServiceAccountConfigError,
    "private_key_id",
  );
  assertThrows(
    () =>
      parseServiceAccount(
        jsonOf(
          serviceAccountObject({
            privateKey: escapedPem(),
            omit: ["client_email"],
          }),
        ),
      ),
    ServiceAccountConfigError,
    "client_email",
  );
});

Deno.test("parseServiceAccount rejects a key from the wrong project", () => {
  const error = assertThrows(
    () =>
      parseServiceAccount(
        jsonOf(
          serviceAccountObject({
            privateKey: escapedPem(),
            projectId: "some-other-project",
            clientEmail:
              "firebase-adminsdk-fake@some-other-project.iam.gserviceaccount.com",
          }),
        ),
      ),
    ServiceAccountConfigError,
    "some-other-project",
  ) as ServiceAccountConfigError;
  assertStringIncludes(error.hint, "agora-application");
});

Deno.test("parseServiceAccount rejects a non-service-account email", () => {
  assertThrows(
    () =>
      parseServiceAccount(
        jsonOf(
          serviceAccountObject({
            privateKey: escapedPem(),
            clientEmail: "someone@gmail.com",
          }),
        ),
      ),
    ServiceAccountConfigError,
    "not a Google service account",
  );
});

Deno.test("parseServiceAccount never leaks key material in errors", () => {
  try {
    parseServiceAccount(
      jsonOf(
        serviceAccountObject({
          privateKey: privateKeyPem.replace(/\n/g, "\\\\n") + "CORRUPT",
        }),
      ),
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    assertEquals(message.includes(privateKeyPem.slice(40, 80)), false);
  }
});

Deno.test("loadServiceAccountCredentials reads and validates the env secret", () => {
  const original = Deno.env.get("FIREBASE_SERVICE_ACCOUNT");
  Deno.env.set(
    "FIREBASE_SERVICE_ACCOUNT",
    jsonOf(serviceAccountObject({ privateKey: escapedPem() })),
  );
  try {
    const credentials = loadServiceAccountCredentials();
    assertEquals(credentials.private_key, privateKeyPem);
    assertEquals(credentials.project_id, FIREBASE_PROJECT_ID);
  } finally {
    if (original === undefined) Deno.env.delete("FIREBASE_SERVICE_ACCOUNT");
    else Deno.env.set("FIREBASE_SERVICE_ACCOUNT", original);
  }
});

Deno.test("loadServiceAccountCredentials fails loudly when the secret is unset", async () => {
  const original = Deno.env.get("FIREBASE_SERVICE_ACCOUNT");
  Deno.env.delete("FIREBASE_SERVICE_ACCOUNT");
  try {
    await assertRejects(
      () => Promise.resolve().then(() => loadServiceAccountCredentials()),
      ServiceAccountConfigError,
      "missing or empty",
    );
  } finally {
    if (original !== undefined) {
      Deno.env.set("FIREBASE_SERVICE_ACCOUNT", original);
    }
  }
});

Deno.test("normalizePrivateKey tolerates a PEM whose lines were joined by spaces", () => {
  const flattened = privateKeyPem.replace(/\n/g, " ");
  assertEquals(normalizePrivateKey(flattened), privateKeyPem);
  // Same, but through the JSON path (the shape a broken .env writer produces).
  const credentials = parseServiceAccount(
    jsonOf(serviceAccountObject({ privateKey: flattened })),
  );
  assertEquals(credentials.private_key, toPem(pemToDer(privateKeyPem)));
});

/**
 * OAuth2 token-exchange tests: JWS signing + Google's error surface.
 *
 * The production bug this guards: after the rotation, Google answered
 * `400 invalid_grant` and the pipeline swallowed the body, so nobody could tell
 * *why* signing failed. These tests assert that the status and raw body are
 * both logged and attached to the thrown error.
 */

import { assertEquals, assertRejects, assertStringIncludes } from "./assert.ts";
import {
  DEFAULT_TOKEN_URL,
  FIREBASE_MESSAGING_SCOPE,
  getAccessToken,
  GoogleOAuthError,
  requestAccessToken,
  resetAccessTokenCache,
  signServiceAccountJwt,
} from "../google-oauth.ts";
import {
  parseServiceAccount,
  ServiceAccountConfigError,
} from "../firebase-service-account.ts";
import {
  jsonResponse,
  serviceAccountObject,
  startMockServer,
  testKeyPair,
} from "./test-utils.ts";

const { privateKeyPem, publicKey } = await testKeyPair();
const credentials = parseServiceAccount(
  JSON.stringify(
    serviceAccountObject({ privateKey: privateKeyPem.replace(/\n/g, "\\n") }),
  ),
);

/** Captures console.error output for a block. */
async function captureConsoleError<T>(
  fn: () => Promise<T>,
): Promise<{ logs: string[]; result: T }> {
  const original = console.error;
  const logs: string[] = [];
  console.error = (...args: unknown[]) => {
    logs.push(
      args.map((arg) => (typeof arg === "string" ? arg : JSON.stringify(arg)))
        .join(" "),
    );
  };
  try {
    const result = await fn();
    return { logs, result };
  } finally {
    console.error = original;
  }
}

function base64UrlDecode(value: string): ArrayBuffer {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") +
    "=".repeat((4 - (value.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

Deno.test("signServiceAccountJwt produces a verifiable RS256 assertion", async () => {
  const now = Date.UTC(2026, 0, 1, 12, 0, 0);
  const { assertion, header, claims } = await signServiceAccountJwt(
    credentials,
    { now },
  );

  const [encodedHeader, encodedClaims, encodedSignature] = assertion.split(".");
  assertEquals(header, { alg: "RS256", typ: "JWT" });
  assertEquals(claims.iss, credentials.client_email);
  assertEquals(claims.scope, FIREBASE_MESSAGING_SCOPE);
  assertEquals(claims.aud, DEFAULT_TOKEN_URL);
  assertEquals((claims.exp as number) - (claims.iat as number), 3600);

  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    publicKey,
    base64UrlDecode(encodedSignature),
    new TextEncoder().encode(`${encodedHeader}.${encodedClaims}`),
  );
  assertEquals(valid, true);
});

Deno.test("signServiceAccountJwt raises a config error for a mangled key", async () => {
  // Structurally valid PEM, corrupted DER body → WebCrypto import must fail
  // with the actionable config error (not an opaque crypto message).
  const body = privateKeyPem.replace(/\n/g, "").replace(
    "-----BEGIN PRIVATE KEY-----",
    "",
  ).replace("-----END PRIVATE KEY-----", "");
  const corrupted = `-----BEGIN PRIVATE KEY-----\n${"A".repeat(40)}${
    body.slice(40)
  }\n-----END PRIVATE KEY-----\n`;
  const mangled = parseServiceAccount(
    JSON.stringify(serviceAccountObject({ privateKey: corrupted })),
  );
  await assertRejects(
    () => signServiceAccountJwt(mangled),
    ServiceAccountConfigError,
    "WebCrypto",
  );
});

Deno.test("requestAccessToken calls the jwt-bearer grant and returns the token", async () => {
  const server = await startMockServer(() =>
    jsonResponse({
      access_token: "ya29.test-token",
      expires_in: 3600,
      token_type: "Bearer",
    })
  );
  try {
    const token = await requestAccessToken(credentials, {
      tokenUrl: server.url,
    });
    assertEquals(token.token, "ya29.test-token");

    const request = server.requests[0];
    assertEquals(request.method, "POST");
    assertStringIncludes(
      request.headers.get("content-type") ?? "",
      "application/x-www-form-urlencoded",
    );
    assertStringIncludes(
      request.body,
      "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer",
    );
    assertStringIncludes(request.body, "assertion=");
  } finally {
    await server.close();
  }
});

Deno.test("requestAccessToken logs Google's exact status and body on invalid_grant", async () => {
  const body = JSON.stringify({
    error: "invalid_grant",
    error_description: "Invalid JWT Signature.",
  });
  const server = await startMockServer(() =>
    new Response(body, {
      status: 400,
      headers: { "Content-Type": "application/json" },
    })
  );
  try {
    const { logs } = await captureConsoleError(() =>
      requestAccessToken(credentials, { tokenUrl: server.url }).catch((error) =>
        error as GoogleOAuthError
      )
    );

    assertEquals(logs.length, 1);
    assertStringIncludes(logs[0], "status : 400");
    assertStringIncludes(logs[0], "invalid_grant");
    assertStringIncludes(logs[0], "Invalid JWT Signature."); // exact body preserved
    assertStringIncludes(logs[0], "mangled");
    assertStringIncludes(logs[0], "invalid_grant");
  } finally {
    await server.close();
  }
});

Deno.test("requestAccessToken attaches status/body/hint to GoogleOAuthError", async () => {
  const server = await startMockServer(() =>
    new Response(
      JSON.stringify({
        error: "invalid_grant",
        error_description: "Invalid JWT Signature.",
      }),
      {
        status: 400,
        headers: { "Content-Type": "application/json" },
      },
    )
  );
  try {
    const error = (await assertRejects(
      () => requestAccessToken(credentials, { tokenUrl: server.url }),
      GoogleOAuthError,
      "invalid_grant",
    )) as GoogleOAuthError;
    assertEquals(error.status, 400);
    assertEquals(error.googleError, "invalid_grant");
    assertEquals(error.googleErrorDescription, "Invalid JWT Signature.");
    assertStringIncludes(error.body, "Invalid JWT Signature.");
    assertStringIncludes(error.hint, "private_key");
  } finally {
    await server.close();
  }
});

Deno.test("requestAccessToken distinguishes 401/403/5xx remediations", async () => {
  const cases = [
    {
      status: 401,
      body: { error: "unauthorized_client" },
      expect: "not authorised",
    },
    { status: 403, body: { error: "access_denied" }, expect: "disabled" },
    { status: 500, body: { error: "internal_failure" }, expect: "transient" },
  ];

  for (const testCase of cases) {
    const server = await startMockServer(() =>
      new Response(JSON.stringify(testCase.body), {
        status: testCase.status,
        headers: { "Content-Type": "application/json" },
      })
    );
    try {
      const error = (await assertRejects(
        () => requestAccessToken(credentials, { tokenUrl: server.url }),
        GoogleOAuthError,
      )) as GoogleOAuthError;
      assertEquals(error.status, testCase.status);
      assertStringIncludes(error.hint, testCase.expect);
    } finally {
      await server.close();
    }
  }
});

Deno.test("requestAccessToken reports network failures with status null", async () => {
  const server = await startMockServer(() => jsonResponse({}));
  const url = server.url;
  await server.close();

  const { logs } = await captureConsoleError(() =>
    requestAccessToken(credentials, { tokenUrl: url }).catch((error) =>
      error as GoogleOAuthError
    )
  );
  assertStringIncludes(logs[0], "no response (network error)");

  const error = (await assertRejects(
    () => requestAccessToken(credentials, { tokenUrl: url }),
    GoogleOAuthError,
    "Network failure",
  )) as GoogleOAuthError;
  assertEquals(error.status, null);
});

Deno.test("requestAccessToken rejects a 200 response without an access_token", async () => {
  const server = await startMockServer(() =>
    new Response("<html>proxy login</html>", { status: 200 })
  );
  try {
    await assertRejects(
      () => requestAccessToken(credentials, { tokenUrl: server.url }),
      GoogleOAuthError,
      "non-JSON body",
    );
  } finally {
    await server.close();
  }
});

Deno.test("getAccessToken caches per worker and honours forceRefresh", async () => {
  resetAccessTokenCache();
  const server = await startMockServer(() =>
    jsonResponse({ access_token: "ya29.cached", expires_in: 3600 })
  );
  try {
    const first = await getAccessToken(credentials, { tokenUrl: server.url });
    const second = await getAccessToken(credentials, { tokenUrl: server.url });
    assertEquals(first, second);
    assertEquals(server.requests.length, 1);

    await getAccessToken(credentials, {
      tokenUrl: server.url,
      forceRefresh: true,
    });
    assertEquals(server.requests.length, 2);
  } finally {
    await server.close();
    resetAccessTokenCache();
  }
});

Deno.test("getAccessToken refreshes after the cached token expires", async () => {
  resetAccessTokenCache();
  const server = await startMockServer(() =>
    jsonResponse({ access_token: "ya29.short", expires_in: 30 })
  );
  try {
    await getAccessToken(credentials, { tokenUrl: server.url });
    await getAccessToken(credentials, { tokenUrl: server.url }); // expires_in 30 < 60s skew → refresh
    assertEquals(server.requests.length, 2);
  } finally {
    await server.close();
    resetAccessTokenCache();
  }
});

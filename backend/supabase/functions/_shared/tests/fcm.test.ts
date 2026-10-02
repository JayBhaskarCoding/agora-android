/**
 * FCM HTTP v1 send + error-taxonomy tests.
 *
 * Covers the three status codes called out in the audit:
 *   401 UNAUTHENTICATED  → credential/signing problem (retry once, then fail)
 *   403 PERMISSION_DENIED → FCM API not enabled for agora-application
 *   404 UNREGISTERED      → stale device token → purge from the database
 */

import { assertEquals, assertRejects, assertStringIncludes } from "./assert.ts";
import {
  classifyFcmFailure,
  FCM_SEND_URL_TEMPLATE,
  FcmError,
  isAuthError,
  isStaleTokenError,
  sendFcmMessage,
  sendPushMessage,
} from "../fcm.ts";
import { parseServiceAccount } from "../firebase-service-account.ts";
import { GoogleOAuthError, resetAccessTokenCache } from "../google-oauth.ts";
import {
  jsonResponse,
  serviceAccountObject,
  startMockServer,
  testKeyPair,
} from "./test-utils.ts";

const { privateKeyPem } = await testKeyPair();
const credentials = parseServiceAccount(
  JSON.stringify(
    serviceAccountObject({ privateKey: privateKeyPem.replace(/\n/g, "\\n") }),
  ),
);

const MESSAGE = {
  message: {
    token: "device-token-abc",
    notification: { title: "Hello", body: "World" },
  },
};

function fcmErrorResponse(
  status: number,
  code: string,
  message: string,
): Response {
  return new Response(
    JSON.stringify({ error: { code: status, message, status: code } }),
    {
      status,
      headers: { "Content-Type": "application/json" },
    },
  );
}

Deno.test("classifyFcmFailure maps each audited status code", () => {
  const unauthenticated = classifyFcmFailure(
    401,
    "UNAUTHENTICATED",
    "auth error",
  );
  assertEquals(unauthenticated.authFailure, true);
  assertStringIncludes(unauthenticated.remediation, "mangled");

  const permission = classifyFcmFailure(403, "PERMISSION_DENIED", "denied");
  assertEquals(permission.authFailure, true);
  assertEquals(permission.retryable, false);
  assertStringIncludes(permission.remediation, "fcm.googleapis.com");

  const unregistered = classifyFcmFailure(
    404,
    "UNREGISTERED",
    "token not registered",
  );
  assertEquals(unregistered.staleToken, true);
  assertStringIncludes(unregistered.remediation, "stale");

  const staleArgument = classifyFcmFailure(
    400,
    "INVALID_ARGUMENT",
    "The registration token is not a valid FCM registration token",
  );
  assertEquals(staleArgument.staleToken, true);

  const badPayload = classifyFcmFailure(
    400,
    "INVALID_ARGUMENT",
    "data must only contain string values",
  );
  assertEquals(badPayload.staleToken, false);

  const quota = classifyFcmFailure(429, "RESOURCE_EXHAUSTED", "quota");
  assertEquals(quota.retryable, true);

  const transient = classifyFcmFailure(503, "UNAVAILABLE", "backend down");
  assertEquals(transient.retryable, true);
});

Deno.test("sendFcmMessage posts to the project endpoint and returns the message id", async () => {
  const server = await startMockServer(() =>
    jsonResponse({ name: "projects/agora-application/messages/42" })
  );
  try {
    const result = await sendFcmMessage({
      accessToken: "ya29.x",
      payload: MESSAGE,
      sendUrl: server.url,
    });
    assertEquals(result.name, "projects/agora-application/messages/42");
    assertEquals(result.status, 200);

    const request = server.requests[0];
    assertEquals(request.method, "POST");
    assertEquals(request.headers.get("authorization"), "Bearer ya29.x");
    assertEquals(JSON.parse(request.body), MESSAGE);
  } finally {
    await server.close();
  }
});

Deno.test("sendFcmMessage builds the agora-application v1 URL by default", async () => {
  const server = await startMockServer(() =>
    jsonResponse({ name: "projects/agora-application/messages/1" })
  );
  try {
    // Point the template at the mock by overriding only the project segment.
    const sendUrl = FCM_SEND_URL_TEMPLATE.replace(
      "https://fcm.googleapis.com",
      server.url,
    ).replace(
      "{projectId}",
      "agora-application",
    );
    assertStringIncludes(
      sendUrl,
      "/v1/projects/agora-application/messages:send",
    );

    const result = await sendFcmMessage({
      accessToken: "ya29.x",
      payload: MESSAGE,
      sendUrl,
    });
    assertEquals(result.name, "projects/agora-application/messages/1");
  } finally {
    await server.close();
  }
});

Deno.test("sendFcmMessage classifies 404 UNREGISTERED as a stale token", async () => {
  const server = await startMockServer(() =>
    fcmErrorResponse(404, "UNREGISTERED", "Requested entity was not found.")
  );
  try {
    const error = (await assertRejects(
      () =>
        sendFcmMessage({
          accessToken: "ya29.x",
          payload: MESSAGE,
          sendUrl: server.url,
        }),
      FcmError,
      "UNREGISTERED",
    )) as FcmError;

    assertEquals(error.status, 404);
    assertEquals(error.code, "UNREGISTERED");
    assertEquals(error.staleToken, true);
    assertEquals(error.authFailure, false);
    assertEquals(isStaleTokenError(error), true);
    assertStringIncludes(error.body, "Requested entity was not found.");
  } finally {
    await server.close();
  }
});

Deno.test("sendFcmMessage classifies 403 PERMISSION_DENIED as a project/IAM problem", async () => {
  const server = await startMockServer(() =>
    fcmErrorResponse(
      403,
      "PERMISSION_DENIED",
      "Firebase Cloud Messaging API has not been used in project 123 before or it is disabled.",
    )
  );
  try {
    const error = (await assertRejects(
      () =>
        sendFcmMessage({
          accessToken: "ya29.x",
          payload: MESSAGE,
          sendUrl: server.url,
        }),
      FcmError,
      "PERMISSION_DENIED",
    )) as FcmError;

    assertEquals(error.status, 403);
    assertEquals(error.authFailure, true);
    assertEquals(error.staleToken, false);
    assertEquals(isAuthError(error), true);
    assertStringIncludes(error.remediation, "agora-application");
  } finally {
    await server.close();
  }
});

Deno.test("sendFcmMessage classifies 401 UNAUTHENTICATED as an auth failure", async () => {
  const server = await startMockServer(() =>
    fcmErrorResponse(
      401,
      "UNAUTHENTICATED",
      "Request had invalid authentication credentials.",
    )
  );
  try {
    const error = (await assertRejects(
      () =>
        sendFcmMessage({
          accessToken: "ya29.x",
          payload: MESSAGE,
          sendUrl: server.url,
        }),
      FcmError,
      "UNAUTHENTICATED",
    )) as FcmError;

    assertEquals(error.status, 401);
    assertEquals(error.authFailure, true);
    assertEquals(error.retryable, true);
  } finally {
    await server.close();
  }
});

Deno.test("sendPushMessage retries once with a fresh token after 401", async () => {
  resetAccessTokenCache();
  const tokenServer = await startMockServer(() =>
    jsonResponse({ access_token: "ya29.fresh", expires_in: 3600 })
  );
  let attempts = 0;
  const fcmServer = await startMockServer(() => {
    attempts += 1;
    return attempts === 1
      ? fcmErrorResponse(401, "UNAUTHENTICATED", "invalid credentials")
      : jsonResponse({ name: "projects/agora-application/messages/7" });
  });

  try {
    const result = await sendPushMessage({
      credentials,
      payload: MESSAGE,
      sendUrl: fcmServer.url,
      tokenOptions: { tokenUrl: tokenServer.url },
    });
    assertEquals(result.attempts, 2);
    assertEquals(result.retriedWithFreshToken, true);
    assertEquals(result.name, "projects/agora-application/messages/7");
    assertEquals(tokenServer.requests.length, 2); // initial + forced refresh
  } finally {
    await fcmServer.close();
    await tokenServer.close();
    resetAccessTokenCache();
  }
});

Deno.test("sendPushMessage gives up when the fresh token is also rejected", async () => {
  resetAccessTokenCache();
  const tokenServer = await startMockServer(() =>
    jsonResponse({ access_token: "ya29.fresh", expires_in: 3600 })
  );
  const fcmServer = await startMockServer(() =>
    fcmErrorResponse(401, "UNAUTHENTICATED", "invalid credentials")
  );

  try {
    const error = (await assertRejects(
      () =>
        sendPushMessage({
          credentials,
          payload: MESSAGE,
          sendUrl: fcmServer.url,
          tokenOptions: { tokenUrl: tokenServer.url },
        }),
      FcmError,
    )) as FcmError;
    assertEquals(error.status, 401);
    assertEquals(error.authFailure, true);
    assertEquals(fcmServer.requests.length, 2);
  } finally {
    await fcmServer.close();
    await tokenServer.close();
    resetAccessTokenCache();
  }
});

Deno.test("sendPushMessage surfaces GoogleOAuthError from the token exchange", async () => {
  resetAccessTokenCache();
  const tokenServer = await startMockServer(() =>
    new Response(
      JSON.stringify({
        error: "invalid_grant",
        error_description: "Invalid JWT Signature.",
      }),
      { status: 400 },
    )
  );

  try {
    const error = (await assertRejects(
      () =>
        sendPushMessage({
          credentials,
          payload: MESSAGE,
          sendUrl: "http://127.0.0.1:1/never-used",
          tokenOptions: { tokenUrl: tokenServer.url },
        }),
      GoogleOAuthError,
    )) as GoogleOAuthError;
    assertEquals(error.status, 400);
    assertStringIncludes(error.hint, "private_key");
  } finally {
    await tokenServer.close();
    resetAccessTokenCache();
  }
});

Deno.test("sendFcmMessage reports network failures distinctly from FCM errors", async () => {
  const server = await startMockServer(() => jsonResponse({}));
  const url = server.url;
  await server.close();

  await assertRejects(
    () =>
      sendFcmMessage({ accessToken: "ya29.x", payload: MESSAGE, sendUrl: url }),
    Error,
    "before a response was received",
  );
});

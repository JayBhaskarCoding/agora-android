/**
 * End-to-end delivery tests (mock Supabase + mock OAuth + mock FCM).
 *
 * These run the exact code path the Edge Function uses: resolve tokens →
 * mint a bearer token → POST to FCM v1 → purge tokens FCM reports dead.
 */

import { assertEquals, assertStringIncludes } from "./assert.ts";
import {
  buildNotificationMessage,
  deliverNotification,
  previewToken,
} from "../deliver-notification.ts";
import { parseServiceAccount } from "../firebase-service-account.ts";
import { resetAccessTokenCache } from "../google-oauth.ts";
import {
  FakeClient,
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

const USER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
const GOOD_TOKEN = "good-device-token-0001";
const STALE_TOKEN = "stale-device-token-0002";

function fcmError(status: number, code: string, message: string): Response {
  return new Response(
    JSON.stringify({ error: { code: status, message, status: code } }),
    {
      status,
      headers: { "Content-Type": "application/json" },
    },
  );
}

Deno.test("previewToken never logs more than a short prefix", () => {
  assertEquals(previewToken(GOOD_TOKEN), "good-dev…(22)");
});

Deno.test("buildNotificationMessage keeps the Android payload contract", () => {
  const payload = buildNotificationMessage(
    {
      recipientId: USER_ID,
      title: "Someone liked your post",
      body: "Nice one!",
      data: {
        post_id: "post-1",
        comment_id: "c-9",
        author_id: "u-1",
        sender_id: "u-1",
        reporter_id: "",
      },
    },
    GOOD_TOKEN,
  ) as {
    message: {
      token: string;
      notification: unknown;
      data: Record<string, string>;
      android: { notification: { channel_id: string } };
    };
  };

  assertEquals(payload.message.token, GOOD_TOKEN);
  assertEquals(payload.message.notification, {
    title: "Someone liked your post",
    body: "Nice one!",
  });
  assertEquals(payload.message.data.action, "open_comment");
  assertEquals(payload.message.data.post_id, "post-1");
  assertEquals(payload.message.data.comment_id, "c-9");
  assertEquals(payload.message.data.sender_id, "u-1");
  assertEquals(
    payload.message.android.notification.channel_id,
    "agora_notifications_channel",
  );
});

Deno.test("deliverNotification sends to good tokens and purges stale ones", async () => {
  resetAccessTokenCache();
  const tokenServer = await startMockServer(() =>
    jsonResponse({ access_token: "ya29.e2e", expires_in: 3600 })
  );

  // Two devices: the first is dead (404 UNREGISTERED), the second delivers.
  const fcmServer = await startMockServer((request) => {
    const body = JSON.parse(request.body);
    return body.message.token === STALE_TOKEN
      ? fcmError(404, "UNREGISTERED", "Requested entity was not found.")
      : jsonResponse({ name: "projects/agora-application/messages/99" });
  });

  const client = new FakeClient();
  client.handler = (call) => {
    if (call.table === "profiles" && call.op === "select") {
      return { data: [{ fcm_token: STALE_TOKEN }], error: null };
    }
    if (call.table === "fcm_tokens" && call.op === "select") {
      return { data: [{ token: GOOD_TOKEN }], error: null };
    }
    return { data: [{ id: USER_ID }], error: null };
  };

  try {
    const result = await deliverNotification({
      client,
      credentials,
      sendUrl: fcmServer.url,
      tokenOptions: { tokenUrl: tokenServer.url },
      notification: {
        notificationId: "n-1",
        recipientId: USER_ID,
        title: "Title",
        body: "Body",
        data: { post_id: "post-1" },
      },
    });

    assertEquals(result.tokensResolved, 2);
    assertEquals(result.delivered, 1);
    assertEquals(result.staleTokensRemoved, 1);
    assertEquals(result.failed, 0);
    assertEquals(result.fatal, undefined);

    // The stale token must have been removed from the database …
    const profileUpdate = client.callsFor("profiles", "update")[0];
    assertEquals(profileUpdate.values, { fcm_token: null });
    assertEquals(profileUpdate.filters, [
      ["id", USER_ID],
      ["fcm_token", STALE_TOKEN],
    ]);

    // … and the good one must not have been touched.
    assertEquals(client.callsFor("profiles", "update").length, 1);

    // Both FCM calls carried an Authorization header with the minted token.
    assertEquals(fcmServer.requests.length, 2);
    assertEquals(
      fcmServer.requests[0].headers.get("authorization"),
      "Bearer ya29.e2e",
    );
    assertEquals(
      fcmServer.requests[1].headers.get("authorization"),
      "Bearer ya29.e2e",
    );

    // The delivered message kept the deep-link data.
    const deliveredBody = JSON.parse(fcmServer.requests[1].body);
    assertEquals(deliveredBody.message.token, GOOD_TOKEN);
    assertEquals(deliveredBody.message.data.post_id, "post-1");
  } finally {
    await fcmServer.close();
    await tokenServer.close();
    resetAccessTokenCache();
  }
});

Deno.test("deliverNotification reports 403 PERMISSION_DENIED as fatal and stops the batch", async () => {
  resetAccessTokenCache();
  const tokenServer = await startMockServer(() =>
    jsonResponse({ access_token: "ya29.e2e", expires_in: 3600 })
  );
  const fcmServer = await startMockServer(() =>
    fcmError(
      403,
      "PERMISSION_DENIED",
      "Firebase Cloud Messaging API has not been used in project 123 before or it is disabled.",
    )
  );

  const client = new FakeClient();
  client.handler = (call) => {
    if (call.table === "profiles" && call.op === "select") {
      return { data: [{ fcm_token: GOOD_TOKEN }], error: null };
    }
    return { data: [], error: null };
  };

  try {
    const result = await deliverNotification({
      client,
      credentials,
      sendUrl: fcmServer.url,
      tokenOptions: { tokenUrl: tokenServer.url },
      notification: { recipientId: USER_ID, title: "T", body: "B" },
    });

    assertEquals(result.delivered, 0);
    assertEquals(result.failed, 1);
    assertEquals(result.fatal?.kind, "fcm_auth");
    assertEquals(result.fatal?.status, 403);
    assertStringIncludes(result.fatal?.remediation ?? "", "fcm.googleapis.com");
    // No cleanup: a permission error says nothing about the token.
    assertEquals(client.callsFor("profiles", "update").length, 0);
  } finally {
    await fcmServer.close();
    await tokenServer.close();
    resetAccessTokenCache();
  }
});

Deno.test("deliverNotification marks an OAuth failure fatal without touching tokens", async () => {
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
  const fcmServer = await startMockServer(() =>
    jsonResponse({ name: "never" })
  );

  const client = new FakeClient();
  client.handler = (call) => {
    if (call.table === "profiles" && call.op === "select") {
      return { data: [{ fcm_token: GOOD_TOKEN }], error: null };
    }
    return { data: [], error: null };
  };

  try {
    const result = await deliverNotification({
      client,
      credentials,
      sendUrl: fcmServer.url,
      tokenOptions: { tokenUrl: tokenServer.url },
      notification: { recipientId: USER_ID, title: "T", body: "B" },
    });

    assertEquals(result.delivered, 0);
    assertEquals(result.fatal?.kind, "oauth");
    assertEquals(result.fatal?.status, 400);
    assertStringIncludes(result.fatal?.remediation ?? "", "private_key");
    assertEquals(fcmServer.requests.length, 0);
    assertEquals(client.callsFor("profiles", "update").length, 0);
  } finally {
    await fcmServer.close();
    await tokenServer.close();
    resetAccessTokenCache();
  }
});

Deno.test("deliverNotification is a no-op when the recipient has no tokens", async () => {
  const client = new FakeClient();
  client.handler = () => ({ data: [], error: null });

  const result = await deliverNotification({
    client,
    credentials,
    notification: { recipientId: USER_ID, title: "T", body: "B" },
  });

  assertEquals(result.tokensResolved, 0);
  assertEquals(result.outcomes, []);
});

/**
 * Stale-token cleanup tests.
 *
 * Guarantees the audit requirement: when FCM reports UNREGISTERED (404) or an
 * INVALID_ARGUMENT about the registration token (400), the token is removed
 * from `fcm_tokens` (or `profiles.fcm_token` when that table is absent) — and
 * that a cleanup failure can never turn a delivered notification into a 500.
 */

import { assertEquals, assertStringIncludes } from "./assert.ts";
import {
  deactivateStaleToken,
  resolveRecipientTokens,
} from "../token-cleanup.ts";
import { type FakeCall, FakeClient } from "./test-utils.ts";

const USER_ID = "11111111-2222-3333-4444-555555555555";
const TOKEN = "fcm-device-token-abc123";

Deno.test("deactivateStaleToken clears profiles.fcm_token only when it still matches", async () => {
  const client = new FakeClient();
  client.handler = (call: FakeCall) => {
    if (call.table === "fcm_tokens") {
      return {
        error: {
          code: "PGRST205",
          message:
            "Could not find the table 'public.fcm_tokens' in the schema cache",
        },
      };
    }
    return { data: [{ id: USER_ID }], error: null };
  };

  const result = await deactivateStaleToken(client, {
    userId: USER_ID,
    token: TOKEN,
    reason: "404 UNREGISTERED",
  });

  const profileCall = client.callsFor("profiles", "update")[0];
  assertEquals(profileCall.values, { fcm_token: null });
  // The guard column is what stops us wiping a token the client refreshed
  // between the send and this cleanup.
  assertEquals(profileCall.filters, [
    ["id", USER_ID],
    ["fcm_token", TOKEN],
  ]);

  assertEquals(result.profileCleared, true);
  assertEquals(result.skippedFcmTokensTable, true);
  assertEquals(result.warnings, []);
});

Deno.test("deactivateStaleToken marks fcm_tokens rows inactive when the table exists", async () => {
  const client = new FakeClient();
  client.handler = () => ({ data: [{ token: TOKEN }], error: null });

  const result = await deactivateStaleToken(client, {
    userId: USER_ID,
    token: TOKEN,
    reason: "400 INVALID_ARGUMENT",
  });

  const tableCall = client.callsFor("fcm_tokens", "update")[0];
  assertEquals(tableCall.filters, [["token", TOKEN]]);
  assertEquals(tableCall.values?.active, false);
  assertEquals(typeof tableCall.values?.updated_at, "string");

  assertEquals(result.fcmTokensDeactivated, true);
  assertEquals(result.fcmTokensDeleted, false);
  assertEquals(result.profileCleared, true);
});

Deno.test("deactivateStaleToken deletes the row when fcm_tokens has no active column", async () => {
  const client = new FakeClient();
  client.handler = (call: FakeCall) => {
    if (call.table === "fcm_tokens" && call.op === "update") {
      return {
        error: {
          code: "PGRST204",
          message:
            "Could not find the 'active' column of 'fcm_tokens' in the schema cache",
        },
      };
    }
    return { data: [], error: null };
  };

  const result = await deactivateStaleToken(client, {
    userId: USER_ID,
    token: TOKEN,
    reason: "404 UNREGISTERED",
  });

  assertEquals(result.fcmTokensDeleted, true);
  assertEquals(client.callsFor("fcm_tokens", "delete").length, 1);
  assertEquals(result.warnings, []);
});

Deno.test("deactivateStaleToken records warnings instead of throwing", async () => {
  const client = new FakeClient();
  client.handler = () => new Error("connection reset");

  const result = await deactivateStaleToken(client, {
    userId: USER_ID,
    token: TOKEN,
    reason: "404 UNREGISTERED",
  });

  assertEquals(result.profileCleared, false);
  assertEquals(result.fcmTokensDeactivated, false);
  assertEquals(result.warnings.length, 2);
  assertStringIncludes(result.warnings[0], "fcm_tokens cleanup threw");
  assertStringIncludes(result.warnings[1], "profiles cleanup threw");
});

Deno.test("deactivateStaleToken skips profiles when the recipient is unknown", async () => {
  const client = new FakeClient();
  const result = await deactivateStaleToken(client, {
    userId: null,
    token: TOKEN,
    reason: "404 UNREGISTERED",
  });

  assertEquals(client.callsFor("profiles").length, 0);
  assertStringIncludes(result.warnings[0], "no recipient id");
});

Deno.test("deactivateStaleToken reports when no row matched (token already replaced)", async () => {
  const client = new FakeClient();
  // RETURNING comes back empty → the client had already registered a new token.
  client.handler = () => ({ data: [], error: null });

  const result = await deactivateStaleToken(client, {
    userId: USER_ID,
    token: TOKEN,
    reason: "404 UNREGISTERED",
  });

  assertEquals(result.profileCleared, false);
  assertEquals(result.fcmTokensDeactivated, false);
  assertEquals(result.warnings, []);
  // The calls still happened — the guard simply matched nothing.
  assertEquals(client.callsFor("profiles", "update").length, 1);
  assertEquals(client.callsFor("fcm_tokens", "update").length, 1);
});

Deno.test("deactivateStaleToken is a no-op for an empty token", async () => {
  const client = new FakeClient();
  const result = await deactivateStaleToken(client, {
    userId: USER_ID,
    token: "",
    reason: "test",
  });
  assertEquals(client.calls.length, 0);
  assertEquals(result.warnings, ["cleanup skipped: empty token"]);
});

Deno.test("resolveRecipientTokens merges and de-duplicates profile + table tokens", async () => {
  const client = new FakeClient();
  client.handler = (call: FakeCall) => {
    if (call.table === "profiles") {
      return { data: [{ fcm_token: "token-a" }], error: null };
    }
    return { data: [{ token: "token-a" }, { token: "token-b" }], error: null };
  };

  const resolved = await resolveRecipientTokens(client, USER_ID);

  assertEquals(resolved.tokens, ["token-a", "token-b"]);
  assertEquals(resolved.fromProfile, ["token-a"]);
  assertEquals(resolved.fromTable, ["token-a", "token-b"]);
  assertEquals(resolved.warnings, []);
});

Deno.test("resolveRecipientTokens tolerates a missing fcm_tokens table", async () => {
  const client = new FakeClient();
  client.handler = (call: FakeCall) => {
    if (call.table === "profiles") {
      return { data: [{ fcm_token: "token-a" }], error: null };
    }
    return {
      error: {
        code: "42P01",
        message: 'relation "public.fcm_tokens" does not exist',
      },
    };
  };

  const resolved = await resolveRecipientTokens(client, USER_ID);
  assertEquals(resolved.tokens, ["token-a"]);
  assertEquals(resolved.warnings, []);
});

Deno.test("resolveRecipientTokens reports genuine query failures", async () => {
  const client = new FakeClient();
  client.handler = (call: FakeCall) => {
    if (call.table === "profiles") {
      return {
        data: null,
        error: { message: "permission denied for table profiles" },
      };
    }
    return { data: [], error: null };
  };

  const resolved = await resolveRecipientTokens(client, USER_ID);
  assertEquals(resolved.tokens, []);
  assertStringIncludes(resolved.warnings[0], "profiles lookup failed");
});

Deno.test("resolveRecipientTokens ignores blank tokens", async () => {
  const client = new FakeClient();
  client.handler = (call: FakeCall) => {
    if (call.table === "profiles") {
      return { data: [{ fcm_token: null }, { fcm_token: "  " }], error: null };
    }
    return { data: [], error: null };
  };

  const resolved = await resolveRecipientTokens(client, USER_ID);
  assertEquals(resolved.tokens, []);
});

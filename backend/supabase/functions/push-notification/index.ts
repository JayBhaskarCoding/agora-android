/**
 * 🌟 push-notification — Supabase Edge Function (database webhook).
 *
 * Triggered by an INSERT on `public.notifications` (see
 * migrations/…_remote_schema.sql and …_repoint_edge_function_webhooks.sql).
 * Delivers the row to every device token registered for the recipient via
 * FCM HTTP v1.
 *
 * Hardening added after the service-account rotation:
 *   • credentials are parsed/normalised/validated once, in `_shared`
 *     (handles \\n-mangled PEMs, base64 envelopes, wrong-project keys);
 *   • the OAuth token exchange logs Google's exact status + body on failure;
 *   • FCM responses are classified (401 / 403 / 404 / 400 …) with remediation;
 *   • stale device tokens are purged from fcm_tokens → profiles.fcm_token.
 *
 * The delivery logic itself lives in ../_shared/deliver-notification.ts so it
 * is covered by unit tests; this file is only the HTTP adapter.
 */

import { serve } from "https://deno.land/std@0.192.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.33.1";

import {
  FIREBASE_PROJECT_ID,
  loadServiceAccountCredentials,
  logCredentialsSummary,
  ServiceAccountConfigError,
} from "../_shared/firebase-service-account.ts";
import { GoogleOAuthError } from "../_shared/google-oauth.ts";
import { deliverNotification } from "../_shared/deliver-notification.ts";
import type { SupabaseLikeClient } from "../_shared/token-cleanup.ts";

interface WebhookPayload {
  type: "INSERT";
  table: string;
  record: {
    id: string;
    recipient_id: string;
    title: string;
    body: string;
    data: Record<string, unknown> | null;
  };
}

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

serve(async (req) => {
  const expectedWebhookSecret = Deno.env.get("WEBHOOK_SECRET");
  const receivedWebhookSecret = req.headers.get("x-webhook-secret");

  if (
    !expectedWebhookSecret || receivedWebhookSecret !== expectedWebhookSecret
  ) {
    return jsonResponse({ error: "Unauthorized" }, 401);
  }

  let notificationId: string | undefined;

  try {
    const payload = (await req.json()) as WebhookPayload;
    const notification = payload.record;
    notificationId = notification?.id;

    if (!notification || !notification.recipient_id) {
      console.warn(
        "[push-notification] webhook payload without recipient_id — ignored",
        { notificationId },
      );
      return jsonResponse({ skipped: true, reason: "no recipient_id" });
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    ) as unknown as SupabaseLikeClient;

    // Load + validate the rotated service account. A bad secret throws a
    // ServiceAccountConfigError that names the field and the fix — surface that
    // verbatim instead of a generic crypto error.
    const credentials = loadServiceAccountCredentials();
    // Logged once per worker (and again whenever the deployed key changes).
    await logCredentialsSummary(credentials, { notificationId });

    const result = await deliverNotification({
      client: supabase,
      credentials,
      notification: {
        notificationId,
        recipientId: notification.recipient_id,
        title: notification.title,
        body: notification.body,
        data: notification.data,
      },
    });

    for (const warning of result.warnings) {
      console.warn(`[push-notification] ${warning}`, { notificationId });
    }

    if (result.tokensResolved === 0) {
      console.log(
        `[push-notification] no FCM token for user ${notification.recipient_id} — skipped`,
        { notificationId },
      );
      return jsonResponse({
        skipped: true,
        reason: "no_token",
        notificationId,
      });
    }

    // Nothing delivered and the failure is a credential/project fault → 502 so
    // the dashboard shows it (and the webhook is retried once the secret is fixed).
    if (result.delivered === 0 && result.fatal) {
      return jsonResponse(
        {
          error: result.fatal.kind === "oauth"
            ? "firebase_oauth_failed"
            : "fcm_rejected_credentials",
          message: result.fatal.message,
          remediation: result.fatal.remediation,
          status: result.fatal.status,
          notificationId,
          outcomes: result.outcomes,
        },
        502,
      );
    }

    return jsonResponse({
      success: result.delivered > 0,
      notificationId,
      projectId: FIREBASE_PROJECT_ID,
      delivered: result.delivered,
      staleTokensRemoved: result.staleTokensRemoved,
      failed: result.failed,
      outcomes: result.outcomes,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[push-notification] unhandled error: ${message}`, {
      notificationId,
    });

    if (error instanceof ServiceAccountConfigError) {
      return jsonResponse(
        {
          error: "firebase_service_account_invalid",
          message: error.message,
          hint: error.hint,
          notificationId,
        },
        500,
      );
    }
    if (error instanceof GoogleOAuthError) {
      return jsonResponse(
        {
          error: "firebase_oauth_failed",
          status: error.status,
          googleError: error.googleError,
          hint: error.hint,
          notificationId,
        },
        502,
      );
    }
    return jsonResponse({ error: message, notificationId }, 500);
  }
});

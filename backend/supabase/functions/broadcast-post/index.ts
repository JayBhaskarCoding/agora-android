/**
 * 🌟 broadcast-post — Supabase Edge Function (database webhook).
 *
 * Triggered by an INSERT on `public.posts` (broadcast_new_post()). Fans the new
 * post out to the `new_posts` FCM topic.
 *
 * Shares the hardened credential/OAuth/FCM pipeline with push-notification:
 * normalised service-account parsing, exact Google OAuth error logging, and
 * classified FCM failures (401 / 403 / 429 / 5xx). Topic messages carry no
 * device token, so there is nothing to purge on UNREGISTERED.
 */

import { serve } from "https://deno.land/std@0.192.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.33.1";

import {
  loadServiceAccountCredentials,
  logCredentialsSummary,
  ServiceAccountConfigError,
} from "../_shared/firebase-service-account.ts";
import { GoogleOAuthError } from "../_shared/google-oauth.ts";
import { isFcmError, sendPushMessage } from "../_shared/fcm.ts";

interface WebhookPayload {
  type: "INSERT";
  table: string;
  record: {
    id: string;
    user_id: string; // The author's ID
    content?: string;
  };
}

const TOPIC = "new_posts";

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

  let postId = "unknown";
  let authorId = "unknown";

  try {
    const payload = (await req.json()) as WebhookPayload;
    const post = payload.record;
    postId = post?.id ?? postId;
    authorId = post?.user_id ?? authorId;

    if (!post?.id || !post?.user_id) {
      console.warn(
        "[broadcast-post] webhook payload without post id/user_id — ignored",
      );
      return jsonResponse({ skipped: true, reason: "incomplete record" });
    }

    const postText = post.content || "A new post was added!";

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );
    const { data: profile, error: profileError } = await supabase
      .from("profiles")
      .select("first_name")
      .eq("id", post.user_id)
      .single();

    if (profileError) {
      // Non-fatal: the broadcast still goes out with a generic author name.
      console.warn(
        `[broadcast-post] author lookup failed for ${post.user_id}: ${profileError.message}`,
      );
    }
    const authorName = (profile?.first_name as string | undefined) || "A user";

    const credentials = loadServiceAccountCredentials();
    // Logged once per worker (and again whenever the deployed key changes).
    await logCredentialsSummary(credentials, { postId });

    const result = await sendPushMessage({
      credentials,
      payload: {
        message: {
          topic: TOPIC,
          data: {
            title: `${authorName} just added a new post`,
            body: postText.substring(0, 40) +
              (postText.length > 40 ? "..." : ""),
            author_id: post.user_id,
            // Route the notification tap straight to this post (agora://post/{post_id}).
            post_id: post.id,
          },
          android: {
            // High priority so data-only messages are delivered promptly to
            // backgrounded apps (otherwise they are deferred by Android).
            priority: "high",
          },
        },
      },
    });

    return jsonResponse({
      success: true,
      postId,
      topic: TOPIC,
      messageId: result.name ?? null,
      attempts: result.attempts,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);

    if (error instanceof ServiceAccountConfigError) {
      // Already actionable: names the field and the remediation.
      console.error(
        `[broadcast-post] service account misconfigured: ${error.message}`,
        { postId },
      );
      return jsonResponse({
        error: "firebase_service_account_invalid",
        message: error.message,
        hint: error.hint,
        postId,
      }, 500);
    }
    if (error instanceof GoogleOAuthError) {
      // Google's exact status + body were logged by requestAccessToken().
      console.error(`[broadcast-post] OAuth failed: ${error.toLogString()}`, {
        postId,
      });
      return jsonResponse(
        {
          error: "firebase_oauth_failed",
          status: error.status,
          googleError: error.googleError,
          hint: error.hint,
          postId,
        },
        502,
      );
    }
    if (isFcmError(error)) {
      console.error(error.toLogString({ postId, topic: TOPIC, authorId }));
      return jsonResponse(
        {
          error: "fcm_send_failed",
          status: error.status,
          code: error.code,
          message: error.googleMessage,
          remediation: error.remediation,
          postId,
        },
        502,
      );
    }

    console.error(`[broadcast-post] unhandled error: ${message}`, {
      postId,
      authorId,
    });
    return jsonResponse({ error: message, postId }, 500);
  }
});

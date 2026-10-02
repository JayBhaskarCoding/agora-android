/**
 * 🌟 Recipient-level delivery logic shared by the push Edge Functions.
 *
 * Split out of `push-notification/index.ts` so the whole pipeline — token
 * resolution → OAuth → FCM → stale-token cleanup — is exercised by tests
 * without standing up an HTTP server for the function itself. The Edge
 * Function is now a thin adapter: authenticate the webhook, parse the payload,
 * delegate here, map the result onto an HTTP status.
 */

import type { ServiceAccountCredentials } from "./firebase-service-account.ts";
import { GoogleOAuthError, type TokenRequestOptions } from "./google-oauth.ts";
import { isFcmError, isStaleTokenError, sendPushMessage } from "./fcm.ts";
import {
  type CleanupResult,
  deactivateStaleToken,
  resolveRecipientTokens,
  type SupabaseLikeClient,
} from "./token-cleanup.ts";

export interface NotificationInput {
  notificationId?: string;
  recipientId: string;
  title: string;
  body: string;
  data?: Record<string, unknown> | null;
}

export interface TokenOutcome {
  /** Prefix only — device tokens are never logged in full. */
  tokenPreview: string;
  status: "delivered" | "stale_token_removed" | "failed";
  messageId?: string;
  attempts?: number;
  retriedWithFreshToken?: boolean;
  error?: {
    status: number;
    code: string;
    message: string;
    remediation: string;
  };
  cleanup?: CleanupResult;
}

export type DeliveryFailureKind = "oauth" | "service_account" | "fcm_auth";

export interface DeliveryResult {
  notificationId?: string;
  /** Tokens found for the recipient before sending. */
  tokensResolved: number;
  delivered: number;
  staleTokensRemoved: number;
  failed: number;
  outcomes: TokenOutcome[];
  warnings: string[];
  /** Set when no token could be delivered because of a credential/project problem. */
  fatal?: {
    kind: DeliveryFailureKind;
    status?: number;
    message: string;
    remediation?: string;
  };
}

export interface DeliverOptions {
  client: SupabaseLikeClient;
  credentials: ServiceAccountCredentials;
  notification: NotificationInput;
  /** Endpoint overrides, used by tests (and proxies) only. */
  sendUrl?: string;
  tokenOptions?: TokenRequestOptions;
  fetchImpl?: typeof fetch;
  androidChannelId?: string;
}

/** Tokens are secret-ish: never log more than a short prefix. */
export function previewToken(token: string): string {
  return `${token.slice(0, 8)}…(${token.length})`;
}

/**
 * Builds the FCM v1 `message` for a device token.
 *
 * Contract shared with the Android client (see docs/engineering-notes.md):
 * `data.post_id` / `data.comment_id` drive the deep link, `data.sender_id` /
 * `data.author_id` / `data.reporter_id` drive the self-action filter.
 */
export function buildNotificationMessage(
  notification: NotificationInput,
  token: string,
  androidChannelId = "agora_notifications_channel",
): Record<string, unknown> {
  const customData = notification.data ?? {};
  const asString = (value: unknown): string =>
    typeof value === "string"
      ? value
      : value === null || value === undefined
      ? ""
      : String(value);
  const commentId = asString(customData.comment_id);

  return {
    message: {
      token,
      // Top-level notification block → Android renders the system tray UI.
      notification: {
        title: notification.title,
        body: notification.body,
      },
      data: {
        title: notification.title,
        body: notification.body,
        post_id: asString(customData.post_id),
        comment_id: commentId,
        action: commentId ? "open_comment" : "open_post",
        author_id: asString(customData.author_id),
        sender_id: asString(customData.sender_id),
        reporter_id: asString(customData.reporter_id),
      },
      android: {
        priority: "high",
        notification: {
          // Raw HTTP v1 API requires snake_case "channel_id".
          channel_id: androidChannelId,
        },
      },
    },
  };
}

/**
 * Sends one notification to every device token registered for the recipient.
 *
 * Never throws for per-token problems: stale tokens are purged and counted,
 * and unrecoverable credential problems are reported through
 * {@link DeliveryResult.fatal} so the caller can choose an HTTP status.
 * OAuth/config errors do propagate (they are environment faults, not
 * per-device faults).
 */
export async function deliverNotification(
  options: DeliverOptions,
): Promise<DeliveryResult> {
  const { client, credentials, notification } = options;
  const notificationId = notification.notificationId;

  const resolved = await resolveRecipientTokens(
    client,
    notification.recipientId,
  );
  const result: DeliveryResult = {
    notificationId,
    tokensResolved: resolved.tokens.length,
    delivered: 0,
    staleTokensRemoved: 0,
    failed: 0,
    outcomes: [],
    warnings: resolved.warnings,
  };

  if (resolved.tokens.length === 0) return result;

  for (const token of resolved.tokens) {
    const tokenPreview = previewToken(token);
    try {
      const send = await sendPushMessage({
        credentials,
        payload: buildNotificationMessage(
          notification,
          token,
          options.androidChannelId,
        ),
        sendUrl: options.sendUrl,
        tokenOptions: options.tokenOptions,
        fetchImpl: options.fetchImpl,
      });
      result.delivered += 1;
      result.outcomes.push({
        tokenPreview,
        status: "delivered",
        messageId: send.name,
        attempts: send.attempts,
        retriedWithFreshToken: send.retriedWithFreshToken,
      });
    } catch (error) {
      if (isStaleTokenError(error)) {
        // 404 UNREGISTERED / 400 INVALID_ARGUMENT about the registration token.
        const cleanup = await deactivateStaleToken(client, {
          userId: notification.recipientId,
          token,
          reason: isFcmError(error)
            ? `${error.status} ${error.code}`
            : "stale token",
        });
        result.staleTokensRemoved += 1;
        result.outcomes.push({
          tokenPreview,
          status: "stale_token_removed",
          cleanup,
          error: isFcmError(error)
            ? {
              status: error.status,
              code: error.code,
              message: error.googleMessage,
              remediation: error.remediation,
            }
            : {
              status: 0,
              code: "UNKNOWN",
              message: String(error),
              remediation: "",
            },
        });
        continue;
      }

      if (error instanceof GoogleOAuthError) {
        // Already logged with Google's exact status + body.
        result.fatal = {
          kind: "oauth",
          status: error.status ?? undefined,
          message: error.message,
          remediation: error.hint,
        };
        break;
      }

      if (isFcmError(error)) {
        result.failed += 1;
        result.outcomes.push({
          tokenPreview,
          status: "failed",
          error: {
            status: error.status,
            code: error.code,
            message: error.googleMessage,
            remediation: error.remediation,
          },
        });
        // 401/403 affects every token — stop the loop and report it as fatal.
        if (error.authFailure) {
          result.fatal = {
            kind: "fcm_auth",
            status: error.status,
            message: error.googleMessage,
            remediation: error.remediation,
          };
          break;
        }
        continue;
      }

      throw error;
    }
  }

  return result;
}

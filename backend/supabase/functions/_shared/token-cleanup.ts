/**
 * 🌟 Device-token lifecycle: resolution + stale-token cleanup.
 *
 * FCM returns `UNREGISTERED` (404) / `INVALID_ARGUMENT` (400) for tokens that
 * can never be delivered to again. Left in the database they fail on every
 * single notification forever, and they poison topic fan-out metrics. This
 * module removes them:
 *
 *   1. `fcm_tokens` — multi-device table. Preferred path: mark the row
 *      `active = false`. If the table has no `active` column the row is
 *      deleted instead. If the table does not exist at all (the current
 *      schema), the attempt is logged once and skipped.
 *   2. `profiles.fcm_token` — the single-device column the Android client
 *      writes today (PushNotificationService.onNewToken). Cleared with a
 *      `.eq("fcm_token", token)` guard so a token the client refreshed *after*
 *      this send is never wiped.
 *
 * Steps 1 and 2 are independent: neither failure aborts the other, and
 * cleanup never throws — a dead token must not fail the whole webhook.
 */

/** Minimal structural type for the PostgREST builder surface we rely on. */
export interface PostgrestResult {
  data?: unknown;
  error?: { message?: string; code?: string; details?: string } | null;
  count?: number | null;
  status?: number;
}

export interface PostgrestBuilderLike extends PromiseLike<PostgrestResult> {
  eq(column: string, value: unknown): PostgrestBuilderLike;
  /** `UPDATE … RETURNING` / column projection. */
  select(columns: string): PostgrestBuilderLike;
}

export interface SupabaseLikeClient {
  from(table: string): {
    select(columns: string): PostgrestBuilderLike;
    update(values: Record<string, unknown>): PostgrestBuilderLike;
    delete(): PostgrestBuilderLike;
  };
}

export type TokenCleanupClient = SupabaseLikeClient;

/** True when PostgREST reports that the relation/schema object is missing. */
function isMissingRelation(
  error: { message?: string; code?: string } | null | undefined,
): boolean {
  if (!error) return false;
  const message = error.message ?? "";
  return (
    error.code === "42P01" ||
    error.code === "PGRST205" ||
    /does not exist/i.test(message) ||
    /Could not find the table/i.test(message)
  );
}

/** True when the target column does not exist on an existing table. */
function isMissingColumn(
  error: { message?: string; code?: string } | null | undefined,
): boolean {
  if (!error) return false;
  const message = error.message ?? "";
  return (
    error.code === "42703" ||
    error.code === "PGRST204" ||
    /Could not find the '.*' column/i.test(message) ||
    /column .* does not exist/i.test(message)
  );
}

export interface CleanupResult {
  token: string;
  reason: string;
  /** Row matched in `fcm_tokens` (marked inactive or deleted). */
  fcmTokensDeactivated: boolean;
  fcmTokensDeleted: boolean;
  /** Row matched in `profiles` and `fcm_token` cleared. */
  profileCleared: boolean;
  skippedFcmTokensTable: boolean;
  warnings: string[];
}

/**
 * Marks a dead device token inactive (or deletes it) in `fcm_tokens`, then
 * clears `profiles.fcm_token` if it still holds that exact token.
 *
 * Never throws — cleanup is best-effort bookkeeping.
 */
export async function deactivateStaleToken(
  client: SupabaseLikeClient,
  target: { userId?: string | null; token: string; reason: string },
): Promise<CleanupResult> {
  const result: CleanupResult = {
    token: target.token,
    reason: target.reason,
    fcmTokensDeactivated: false,
    fcmTokensDeleted: false,
    profileCleared: false,
    skippedFcmTokensTable: false,
    warnings: [],
  };

  if (!target.token) {
    result.warnings.push("cleanup skipped: empty token");
    return result;
  }

  // 1. fcm_tokens (optional table — tolerated when absent).
  try {
    const { error, data } = await client
      .from("fcm_tokens")
      .update({ active: false, updated_at: new Date().toISOString() })
      .eq("token", target.token)
      .select("token");

    if (!error) {
      // RETURNING tells us whether a row actually matched.
      result.fcmTokensDeactivated = Array.isArray(data)
        ? data.length > 0
        : true;
    } else if (isMissingColumn(error)) {
      // Checked before "missing relation": PostgREST reports both through the
      // same "in the schema cache" phrasing.
      // Table exists but has no `active` column → hard delete instead.
      const deleted = await client.from("fcm_tokens").delete().eq(
        "token",
        target.token,
      );
      if (deleted.error) {
        result.warnings.push(
          `fcm_tokens delete failed: ${deleted.error.message}`,
        );
      } else {
        result.fcmTokensDeleted = true;
      }
    } else if (isMissingRelation(error)) {
      result.skippedFcmTokensTable = true;
    } else {
      result.warnings.push(`fcm_tokens update failed: ${error.message}`);
    }
  } catch (error) {
    result.warnings.push(
      `fcm_tokens cleanup threw: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  }

  // 2. profiles.fcm_token (the column the Android client writes today).
  if (!target.userId) {
    result.warnings.push(
      "profiles cleanup skipped: no recipient id in the webhook payload",
    );
  } else {
    try {
      // The `.eq("fcm_token", token)` guard is essential: the user may have
      // reinstalled the app and registered a new token between the send and
      // this cleanup — that new token must survive.
      const { error, data } = await client
        .from("profiles")
        .update({ fcm_token: null })
        .eq("id", target.userId)
        .eq("fcm_token", target.token)
        .select("id");

      if (error) {
        result.warnings.push(`profiles cleanup failed: ${error.message}`);
      } else {
        result.profileCleared = Array.isArray(data) ? data.length > 0 : true;
      }
    } catch (error) {
      result.warnings.push(
        `profiles cleanup threw: ${
          error instanceof Error ? error.message : String(error)
        }`,
      );
    }
  }

  console.warn(
    `[fcm-cleanup] stale token purged reason=${target.reason} profileCleared=${result.profileCleared} ` +
      `fcmTokensDeactivated=${result.fcmTokensDeactivated} fcmTokensDeleted=${result.fcmTokensDeleted} ` +
      `fcmTokensTableMissing=${result.skippedFcmTokensTable}` +
      (result.warnings.length ? ` warnings=${result.warnings.join("; ")}` : ""),
  );

  return result;
}

export interface ResolvedTokens {
  tokens: string[];
  /** Tokens coming from `profiles.fcm_token`. */
  fromProfile: string[];
  /** Tokens coming from the optional `fcm_tokens` table. */
  fromTable: string[];
  warnings: string[];
}

/**
 * Resolves every device token registered for a recipient.
 *
 * `profiles.fcm_token` is the source of truth today; `fcm_tokens` is read too
 * when present so multi-device support needs no code change later.
 */
export async function resolveRecipientTokens(
  client: SupabaseLikeClient,
  userId: string,
): Promise<ResolvedTokens> {
  const resolved: ResolvedTokens = {
    tokens: [],
    fromProfile: [],
    fromTable: [],
    warnings: [],
  };

  const { data, error } = await client.from("profiles").select("fcm_token").eq(
    "id",
    userId,
  );
  if (error) {
    resolved.warnings.push(`profiles lookup failed: ${error.message}`);
  } else {
    const rows = (Array.isArray(data) ? data : data ? [data] : []) as Array<
      { fcm_token?: string | null }
    >;
    resolved.fromProfile = rows
      .map((row) => row?.fcm_token)
      .filter((token): token is string =>
        typeof token === "string" && token.trim().length > 0
      );
  }

  try {
    const { data: rows, error: tableError } = await client
      .from("fcm_tokens")
      .select("token")
      .eq("user_id", userId)
      .eq("active", true);

    if (tableError) {
      if (!isMissingRelation(tableError)) {
        resolved.warnings.push(
          `fcm_tokens lookup failed: ${tableError.message}`,
        );
      }
    } else {
      const list = (Array.isArray(rows) ? rows : rows ? [rows] : []) as Array<
        { token?: string | null }
      >;
      resolved.fromTable = list
        .map((row) => row?.token)
        .filter((token): token is string =>
          typeof token === "string" && token.trim().length > 0
        );
    }
  } catch (error) {
    resolved.warnings.push(
      `fcm_tokens lookup threw: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  }

  resolved.tokens = [
    ...new Set([...resolved.fromProfile, ...resolved.fromTable]),
  ];
  return resolved;
}

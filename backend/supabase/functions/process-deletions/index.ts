import { serve } from "https://deno.land/std@0.192.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.33.1";

/**
 * 🌟 process-deletions — the backend half of the 3-day grace-period account
 * deletion. Invoked daily by pg_cron (see migration
 * 20990101000004_account_deletion_grace_period.sql) with the shared
 * x-webhook-secret, and safe to invoke manually for testing.
 *
 * For every profile whose deletion_scheduled_at <= now(), honours the mode
 * chosen in choose_deletion_mode() (migration 20990101000006):
 *
 *   hard (default when unset):
 *   1. remove its storage media ({uid}/… folders in every media bucket —
 *      storage rows do NOT cascade from auth.users),
 *   2. auth.admin.deleteUser(uid) — which cascades through
 *      profiles → posts/comments/post_likes/notifications/reports via FK.
 *
 *   soft — the "Ghost Migration" (migration 20990101000007):
 *   1. purge only the avatar (profile PII) — post media stays live,
 *   2. close_account_ghost() RPC atomically removes the user's likes and
 *      comments (decrementing the denormalized post counters), anonymizes
 *      the profile row, flags status = 'closed', stores the SHA-256 ghost
 *      hash of the real email and keeps the @handle locked,
 *   3. detach the real email from auth.users — replaced by an irreversible
 *      dummy under @ghost.agora (GoTrue syncs the email identity too, see
 *      internal/api/admin.go) so the person can register a fresh account
 *      with their real address later — and permanently ban the ghost
 *      (NEVER deleteUser: the profiles FK cascade would erase the kept
 *      posts).
 *
 * Works regardless of session state: a user who logs out during the grace
 * window is still processed on schedule.
 */

const MEDIA_BUCKETS = ["avatars", "post-media", "post_images"];

type StorageClient = ReturnType<typeof createClient>["storage"];

/** Recursively deletes every object under `prefix` in `bucket`. */
async function deleteFolder(storage: StorageClient, bucket: string, prefix: string) {
  const { data: entries, error } = await storage.from(bucket).list(prefix, { limit: 1000 });
  if (error) {
    // A missing bucket/prefix is not fatal — log and move on.
    console.warn(`list failed for ${bucket}/${prefix}: ${error.message}`);
    return;
  }
  if (!entries || entries.length === 0) return;

  const filePaths: string[] = [];
  const subFolders: string[] = [];
  for (const entry of entries) {
    const fullPath = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.id === null) subFolders.push(fullPath);
    else filePaths.push(fullPath);
  }

  if (filePaths.length > 0) {
    const { error: removeError } = await storage.from(bucket).remove(filePaths);
    if (removeError) {
      console.warn(`remove failed for ${bucket} (${filePaths.length} objects): ${removeError.message}`);
    }
  }
  for (const sub of subFolders) {
    await deleteFolder(storage, bucket, sub);
  }
}

/** Lowercase hex SHA-256 of `input` — must match
 *  encode(extensions.digest(input, 'sha256'), 'hex') in Postgres. Callers
 *  normalize (trim + lowercase) before hashing, same as the SQL side. */
async function sha256Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

serve(async (req) => {
  const expectedWebhookSecret = Deno.env.get("WEBHOOK_SECRET");
  const receivedWebhookSecret = req.headers.get("x-webhook-secret");

  if (!expectedWebhookSecret || receivedWebhookSecret !== expectedWebhookSecret) {
    return new Response(JSON.stringify({ error: "Unauthorized" }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const supabase = createClient(supabaseUrl, supabaseServiceKey, {
      auth: { autoRefreshToken: false, persistSession: false },
    });

    const now = new Date().toISOString();
    const { data: dueProfiles, error: dueError } = await supabase
      .from("profiles")
      .select("id, deletion_mode")
      .not("deletion_scheduled_at", "is", null)
      .lte("deletion_scheduled_at", now);

    if (dueError) throw dueError;

    const deleted: string[] = [];
    const closed: string[] = [];
    const failed: string[] = [];

    for (const row of dueProfiles ?? []) {
      const uid = row.id as string;
      // Missing/unknown mode falls back to the full erase (pre-mode behaviour).
      const mode = (row.deletion_mode as string | null) ?? "hard";
      try {
        if (mode === "soft") {
          // SOFT — the "Ghost Migration": close the account, keep its posts
          // under the "Removed User" alias, and detach the real email so it
          // can register a brand-new account later.
          // 1. The avatar is profile PII — purge it. Post media stays live.
          await deleteFolder(supabase.storage, "avatars", uid);

          // 2. Resolve the account's real email for the ghost hash:
          //    profiles.email first, GoTrue as fallback (covers partial
          //    re-runs where the profile row was already anonymized).
          const { data: profileRow } = await supabase
            .from("profiles")
            .select("email")
            .eq("id", uid)
            .maybeSingle();
          let realEmail = ((profileRow?.email as string | null) ?? "").trim().toLowerCase();
          if (!realEmail) {
            const { data: authRow } = await supabase.auth.admin.getUserById(uid);
            const candidate = (authRow?.user?.email ?? "").trim().toLowerCase();
            if (candidate && !candidate.endsWith("@ghost.agora")) realEmail = candidate;
          }
          const emailHash = realEmail ? await sha256Hex(realEmail) : null;

          // 3. Atomic DB-side close: likes/comments removed (post counters
          //    decremented), profile anonymized + flagged closed, ghost hash
          //    stored, handle kept & locked, deadline cleared so cron never
          //    reprocesses this row.
          const { error: closeError } = await supabase.rpc("close_account_ghost", {
            p_uid: uid,
            p_email_hash: emailHash,
          });
          if (closeError) throw closeError;

          // 4. Detach the real email from auth.users — replaced by an
          //    irreversible dummy under @ghost.agora (admin update syncs the
          //    email identity too, so signup's duplicate check no longer sees
          //    the real address) — and permanently ban the ghost. NEVER
          //    deleteUser here: profiles reference auth.users ON DELETE
          //    CASCADE, which would erase the posts we must keep.
          const ghostEmail =
            `ghost_${(await sha256Hex(`${uid}:${Date.now()}`)).slice(0, 40)}@ghost.agora`;
          const { error: banError } = await supabase.auth.admin.updateUserById(uid, {
            email: ghostEmail,
            email_confirm: true,
            ban_duration: "none",
          });
          if (banError) throw banError;

          closed.push(uid);
          console.log(
            `Ghosted expired account ${uid} (posts kept, email ${emailHash ? "detached" : "already absent"})`,
          );
        } else {
          // HARD: full, permanent erase.
          // 1. Storage media first — these rows never cascade from auth.users.
          for (const bucket of MEDIA_BUCKETS) {
            await deleteFolder(supabase.storage, bucket, uid);
          }

          // 2. Delete the auth user; FK cascades erase profiles and all
          //    attached rows (posts, comments, likes, notifications, reports).
          const { error: deleteError } = await supabase.auth.admin.deleteUser(uid);
          if (deleteError) throw deleteError;

          deleted.push(uid);
          console.log(`Erased expired account ${uid} (hard delete)`);
        }
      } catch (perUserError) {
        console.error(`Failed to process account ${uid} (${mode}):`, perUserError);
        failed.push(uid);
      }
    }

    return new Response(
      JSON.stringify({
        due: (dueProfiles ?? []).length,
        deleted,
        closed,
        failed,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  } catch (error) {
    console.error("process-deletions fatal:", error);
    return new Response(JSON.stringify({ error: String(error) }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});

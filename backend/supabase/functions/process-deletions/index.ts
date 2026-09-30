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
 *   soft (close account, keep posts):
 *   1. purge only the avatar (profile PII) — post media stays live,
 *   2. anonymize the profile row and flag status = 'closed' so clients
 *      mask the author as "Removed User" while keeping the @handle,
 *   3. permanently ban the auth user (NEVER deleteUser — the profiles FK
 *      cascades from auth.users and would erase the posts we must keep).
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
          // SOFT: close the account but keep its posts under a
          // "Removed User" alias.
          // 1. The avatar is profile PII — purge it. Post media stays live.
          await deleteFolder(supabase.storage, "avatars", uid);

          // 2. Anonymize the profile row and flag it closed so clients mask
          //    the author. The handle stays in place (locked to the removed
          //    user) and the deadline is cleared so cron never reprocesses.
          const { error: closeError } = await supabase
            .from("profiles")
            .update({
              status: "closed",
              first_name: null,
              last_name: null,
              avatar_url: null,
              gender: null,
              dob: null,
              email: null,
              fcm_token: null,
              current_device_id: null,
              active_session_id: null,
              deletion_scheduled_at: null,
            })
            .eq("id", uid);
          if (closeError) throw closeError;

          // 3. Permanently ban the auth user. NEVER deleteUser here —
          //    profiles reference auth.users ON DELETE CASCADE, which would
          //    erase the profile row and (via its own cascades) every post.
          const { error: banError } = await supabase.auth.admin.updateUserById(
            uid,
            { ban_duration: "none" },
          );
          if (banError) throw banError;

          closed.push(uid);
          console.log(`Closed expired account ${uid} (soft delete — posts kept)`);
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

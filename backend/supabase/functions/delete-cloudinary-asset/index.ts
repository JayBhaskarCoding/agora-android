/**
 * 🌟 delete-cloudinary-asset — Supabase Edge Function.
 *
 * Destroys the Cloudinary assets belonging to a post, using the Cloudinary
 * *admin* API secret. The secret lives ONLY here (as a function secret), never
 * in the Android app: anything compiled into an APK can be extracted, and this
 * key can delete every asset in the account.
 *
 * ── WHY THIS REPLACED ON-DEVICE SIGNING ─────────────────────────────────────
 * The app used to compute `SHA-1(public_id=…&timestamp=…<secret>)` itself,
 * which required shipping CLOUDINARY_API_SECRET in BuildConfig. One unzip of
 * the APK and an attacker owns the media library. Signing moved server-side.
 *
 * ── AUTHORISATION ───────────────────────────────────────────────────────────
 * Three independent checks, all server-side:
 *   1. the caller's JWT must be valid (resolved via auth.getUser);
 *   2. the post must belong to that user (`posts.user_id`);
 *   3. every requested `public_id` must appear in the post's own `media_urls`.
 * Check 3 is what stops a logged-in user from destroying somebody else's asset
 * by guessing a public_id — the client cannot widen the blast radius, because
 * the allowed set is re-derived from the database, not trusted from the body.
 *
 * ⚠️ Because of check 2 the post row MUST still exist when this is called.
 * The app therefore invokes this function BEFORE deleting the row: once the row
 * is gone there is nothing left to authorise against, and "trust the client's
 * public_ids" is exactly the vulnerability this function exists to avoid.
 * A missing row returns 409 with an explicit hint.
 *
 * ── SECRETS (supabase secrets set …) ────────────────────────────────────────
 *   CLOUDINARY_CLOUD_NAME    e.g. h9cocag7
 *   CLOUDINARY_API_KEY       api key
 *   CLOUDINARY_API_SECRET    api secret (never leaves the server)
 *   SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY are injected by the platform.
 *
 * Deploy:  supabase functions deploy delete-cloudinary-asset
 * Verify:  supabase functions invoke delete-cloudinary-asset --body '{"post_id":"…"}'
 */

import { serve } from "https://deno.land/std@0.192.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.33.1";

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

/** Refuses absurd payloads instead of looping over an attacker-sized array. */
const MAX_ASSETS = 20;

interface AssetRef {
  public_id: string;
  resource_type: string;
}

interface RequestBody {
  post_id?: string;
  assets?: AssetRef[];
}

function json(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// Cloudinary URL parsing — mirrors CloudinaryUrlHelper.kt on the client.
// Duplicated deliberately: the server must NOT trust the client's parsing, so
// it re-derives the allowed public_ids from `posts.media_urls` independently.
// ─────────────────────────────────────────────────────────────────────────────

const UPLOAD_MARKER = "/upload/";

const TRANSFORM_KEYS = new Set([
  "a", "ac", "af", "ar", "b", "bo", "br", "c", "co", "cs", "d", "dl", "dn",
  "dpr", "du", "e", "eo", "f", "fl", "fn", "g", "h", "if", "ki", "l", "o",
  "p", "pg", "q", "r", "s", "so", "sp", "sr", "t", "u", "vc", "w", "x", "y", "z",
]);

function isTransformSegment(segment: string): boolean {
  if (!segment || segment.includes(".")) return false;
  const tokens = segment.split(",");
  if (tokens.length === 0) return false;
  return tokens.every((token) => {
    const underscore = token.indexOf("_");
    if (underscore <= 0) return false;
    const key = token.slice(0, underscore);
    return TRANSFORM_KEYS.has(key) && token.length > key.length + 1;
  });
}

function publicIdOf(url: string): string | null {
  const index = url.indexOf(UPLOAD_MARKER);
  if (index < 0) return null;

  const segments = url
    .slice(index + UPLOAD_MARKER.length)
    .split("?")[0]
    .split("#")[0]
    .split("/")
    .filter((part) => part.length > 0);

  if (segments.length === 0) return null;
  if (segments.length > 1 && isTransformSegment(segments[0])) segments.shift();
  if (segments.length > 1 && /^v\d+$/.test(segments[0])) segments.shift();
  if (segments.length === 0) return null;

  const last = segments.length - 1;
  const dot = segments[last].lastIndexOf(".");
  if (dot > 0) {
    const ext = segments[last].slice(dot + 1);
    if (ext.length >= 2 && ext.length <= 5 && /^[A-Za-z0-9]+$/.test(ext)) {
      segments[last] = segments[last].slice(0, dot);
    }
  }
  const id = segments.join("/");
  return id.length > 0 ? id : null;
}

function resourceTypeOf(url: string): string | null {
  const index = url.indexOf(UPLOAD_MARKER);
  if (index < 0) return null;
  const before = url.slice(0, index).replace(/\/+$/, "");
  const type = before.slice(before.lastIndexOf("/") + 1).toLowerCase();
  return ["image", "video", "raw"].includes(type) ? type : null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Cloudinary signing (server-side)
// ─────────────────────────────────────────────────────────────────────────────

async function sha1Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(input));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

/** `SHA-1(<sorted k=v pairs, api_key excluded><api_secret>)`. */
async function cloudinarySignature(
  params: Record<string, string>,
  apiSecret: string,
): Promise<string> {
  const payload = Object.keys(params)
    .filter((key) => key !== "api_key" && key !== "file")
    .sort()
    .map((key) => `${key}=${params[key]}`)
    .join("&");
  return await sha1Hex(payload + apiSecret);
}

async function destroyAsset(
  cloudName: string,
  apiKey: string,
  apiSecret: string,
  asset: AssetRef,
): Promise<{ public_id: string; resource_type: string; result: string }> {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const params: Record<string, string> = {
    invalidate: "true",
    public_id: asset.public_id,
    timestamp,
  };
  const signature = await cloudinarySignature(params, apiSecret);

  const form = new URLSearchParams({
    public_id: asset.public_id,
    timestamp,
    invalidate: "true",
    api_key: apiKey,
    signature,
  });

  const response = await fetch(
    `https://api.cloudinary.com/v1_1/${cloudName}/${asset.resource_type}/destroy`,
    { method: "POST", body: form },
  );

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`cloudinary ${response.status}: ${text.slice(0, 300)}`);
  }
  let result = "unknown";
  try {
    result = JSON.parse(text).result ?? "unknown";
  } catch {
    // A non-JSON 200 is still a failure worth surfacing.
    throw new Error(`cloudinary returned non-JSON: ${text.slice(0, 200)}`);
  }
  return { public_id: asset.public_id, resource_type: asset.resource_type, result };
}

// ─────────────────────────────────────────────────────────────────────────────

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }
  if (req.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const cloudName = Deno.env.get("CLOUDINARY_CLOUD_NAME");
  const apiKey = Deno.env.get("CLOUDINARY_API_KEY");
  const apiSecret = Deno.env.get("CLOUDINARY_API_SECRET");

  if (!supabaseUrl || !serviceRoleKey) {
    console.error("missing SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY");
    return json({ error: "server_misconfigured" }, 500);
  }
  if (!cloudName || !apiKey || !apiSecret) {
    // Loud on purpose: silent "deleted but not really" is worse than a 500.
    console.error("missing CLOUDINARY_CLOUD_NAME / CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET");
    return json({ error: "cloudinary_not_configured" }, 500);
  }

  // ── 1. authenticate the caller ───────────────────────────────────────────
  const authHeader = req.headers.get("Authorization") ?? "";
  const jwt = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!jwt) return json({ error: "missing_authorization" }, 401);

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data: userData, error: userError } = await admin.auth.getUser(jwt);
  if (userError || !userData?.user) {
    return json({ error: "invalid_jwt" }, 401);
  }
  const callerId = userData.user.id;

  // ── 2. parse the request ─────────────────────────────────────────────────
  let body: RequestBody;
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const postId = (body.post_id ?? "").trim();
  if (!postId) return json({ error: "missing_post_id" }, 400);

  // ── 3. authorise against the database, not the request ───────────────────
  const { data: post, error: postError } = await admin
    .from("posts")
    .select("id,user_id,media_urls")
    .eq("id", postId)
    .maybeSingle();

  if (postError) {
    console.error(`post lookup failed for ${postId}: ${postError.message}`);
    return json({ error: "post_lookup_failed" }, 500);
  }
  if (!post) {
    return json({
      error: "post_not_found",
      hint: "invoke this function BEFORE deleting the row — ownership cannot " +
        "be verified once the post is gone",
    }, 409);
  }
  if (post.user_id !== callerId) {
    console.warn(`user ${callerId} tried to purge media of post ${postId} (owner ${post.user_id})`);
    return json({ error: "not_post_owner" }, 403);
  }

  // Allowed set, re-derived from the row. The client's list is only a hint.
  const allowed = new Map<string, string>(); // public_id -> resource_type
  for (const url of (post.media_urls ?? []) as string[]) {
    if (typeof url !== "string") continue;
    const id = publicIdOf(url);
    const type = resourceTypeOf(url);
    if (id && type) allowed.set(id, type);
  }

  const requested: AssetRef[] = Array.isArray(body.assets) ? body.assets : [];
  if (requested.length > MAX_ASSETS) {
    return json({ error: "too_many_assets", max: MAX_ASSETS }, 400);
  }

  // Fall back to the whole post if the client sent nothing usable.
  const targets: AssetRef[] = requested.length > 0
    ? requested
        .filter((asset) => typeof asset?.public_id === "string")
        .map((asset) => ({
          public_id: asset.public_id,
          resource_type: asset.resource_type ?? allowed.get(asset.public_id) ?? "",
        }))
    : Array.from(allowed, ([public_id, resource_type]) => ({ public_id, resource_type }));

  const rejected: string[] = [];
  const approved = targets.filter((asset) => {
    const permitted = allowed.get(asset.public_id);
    const ok = permitted !== undefined && permitted === asset.resource_type;
    if (!ok) rejected.push(asset.public_id);
    return ok;
  });

  if (rejected.length > 0) {
    // Partial trust is not allowed: an id outside the post is an attempt, not a typo.
    console.warn(`rejected assets not belonging to post ${postId}: ${rejected.join(", ")}`);
    return json({ error: "asset_not_owned_by_post", rejected }, 403);
  }

  // ── 4. destroy ───────────────────────────────────────────────────────────
  const destroyed: unknown[] = [];
  const failed: unknown[] = [];

  for (const asset of approved) {
    try {
      const outcome = await destroyAsset(cloudName, apiKey, apiSecret, asset);
      // "not found" is success: the desired end state already holds
      // (double delete, or an asset removed by hand in the dashboard).
      if (outcome.result === "ok" || outcome.result === "not found") {
        destroyed.push(outcome);
      } else {
        failed.push({ ...outcome, error: `unexpected result: ${outcome.result}` });
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`destroy failed for ${asset.resource_type}/${asset.public_id}: ${message}`);
      failed.push({ ...asset, error: message });
    }
  }

  console.log(
    `post ${postId}: destroyed ${destroyed.length}/${approved.length}, failed ${failed.length}`,
  );

  // 207 when partially done — the client logs it and moves on (best effort).
  return json({ destroyed, failed, rejected }, failed.length > 0 ? 207 : 200);
});

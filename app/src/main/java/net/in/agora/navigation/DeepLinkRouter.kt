package net.in.agora.navigation

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * Single entry point for "open this post" navigation requests.
 *
 * FCM notification taps (background), foreground in-app banner taps, shared
 * `https://agora.in.net/post/{id}` links and cold/warm launch intents all funnel
 * through here. [net.in.agora.ui.MainScreen] observes [pending] and performs
 * the NavController navigation once the NavHost exists, which makes routing
 * deterministic regardless of whether the process was dead, the app was mid-auth,
 * or the notification was tapped while the feed was already on screen.
 *
 * Because [pending] is a StateFlow, a deep link submitted in
 * [net.in.agora.MainActivity.onCreate] (before the user is authenticated and
 * before MainScreen composes) is not lost — it is replayed to the first observer.
 *
 * ── SUPPORTED SHAPES ────────────────────────────────────────────────────────
 * ```
 * https://agora.in.net/post/{postId}[?commentId=…]     App Link + share link
 * https://agora.in.net/p/{postId}                      short alias (/p/)
 * http://agora.in.net/post/{postId}                    defensive (see below)
 * https://auth-agora.info/post/{postId}                LEGACY host, fallback only
 * agora://post/{postId}[?commentId=…]                  notifications, in-app
 * agora:///post/{postId}                               empty-authority variant
 * net.in.agora://post/{postId}                         applicationId-derived
 * <any of the above>?post_id={postId}                  legacy query fallback
 * Intent extras post_id / comment_id                   FCM data payload
 * ```
 *
 * ── WHY THE PARSER IS THIS DEFENSIVE ────────────────────────────────────────
 * A shared link is a *public* contract: it is copied into chats, pasted into
 * browsers, re-shared by link shorteners and re-serialised by Chrome before it
 * ever reaches [parse]. The old parser compared `uri.host` with `==` against a
 * single literal and only understood the `agora` scheme, so an uppercase host, a
 * `www.` prefix, an `http://` downgrade, or the declared-but-unhandled
 * `net.in.agora://` scheme all silently returned null — the user tapped a post
 * link and landed on the feed with no error anywhere.
 */
object DeepLinkRouter {

    /**
     * ✦ The ONE place the public web origin is written in Kotlin.
     *
     * Kept in sync with:
     *   - AndroidManifest.xml (`android:host` of the App Links filter)
     *   - Supabase dashboard → Authentication → URL Configuration (site_url +
     *     redirect allow-list, written by backend/setup_agora_config.ps1)
     *   - https://agora.in.net/.well-known/assetlinks.json
     *
     * If this changes, old shared links die — keep the previous host in the
     * Supabase redirect allow-list for at least one release cycle.
     */
    const val WEB_HOST = "agora.in.net"

    /**
     * The pre-migration public origin. Links with this host were shared in
     * group chats, posted on other platforms and delivered in push payloads
     * before the move to [WEB_HOST]; they must keep resolving forever, because
     * there is no way to recall a link that is already out in the world.
     *
     * ⚠️ Do NOT add this to the `autoVerify` filter in AndroidManifest.xml —
     * on Android 8–11 verification is all-or-nothing per app, so a host that
     * cannot serve `assetlinks.json` (this one is retired) would un-verify
     * `agora.in.net` too. It is declared in its own, non-verifying filter so
     * the OS can still offer Agora for those URLs.
     */
    private const val LEGACY_WEB_HOST = "auth-agora.info"

    /**
     * Hosts accepted by the parser.
     *
     * `www.agora.in.net` is handled here but deliberately NOT declared in
     * AndroidManifest.xml (same all-or-nothing trap as [LEGACY_WEB_HOST]): what
     * matters is that a user who types or pastes it still gets the post.
     */
    private val WEB_HOSTS = setOf(WEB_HOST, "www.$WEB_HOST", LEGACY_WEB_HOST)

    /** Custom schemes declared in the manifest and allow-listed in Supabase. */
    private val CUSTOM_SCHEMES = setOf("agora", "net.in.agora")

    /** `https://agora.in.net/post/{id}` — canonical. */
    private const val POST_SEGMENT = "post"

    /**
     * `https://agora.in.net/p/{id}` — short alias for places where the length
     * of a shared link matters (chats, QR codes, SMS). Web only: the custom
     * schemes have a stable `post` grammar and are not shareable URLs.
     */
    private const val POST_SEGMENT_ALIAS = "p"
    private const val EXTRA_POST_ID = "post_id"
    private const val EXTRA_COMMENT_ID = "comment_id"

    private const val MAX_POST_ID_LENGTH = 64

    /** A parsed "open post" request. */
    data class PendingPostLink(
        val postId: String,
        val commentId: String? = null
    ) {
        /** Route string matching MainScreen's `post/{postId}?commentId={commentId}` destination. */
        fun toRoute(): String =
            if (commentId.isNullOrBlank()) "post/$postId"
            else "post/$postId?commentId=${encodeQueryValue(commentId)}"
    }

    private val _pending = MutableStateFlow<PendingPostLink?>(null)
    val pending: StateFlow<PendingPostLink?> = _pending.asStateFlow()

    fun submit(postId: String?, commentId: String? = null) {
        val link = buildLink(postId, commentId) ?: return
        _pending.value = link
    }

    /** Called by MainScreen after the link has been routed (or skipped as a duplicate). */
    fun consume(link: PendingPostLink) {
        _pending.compareAndSet(link, null)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts a post deep link from an [Intent].
     *
     * The FCM data payload (`post_id` / `comment_id` extras) wins over `data`,
     * because a notification intent carries extras and no URI. Everything else
     * goes through [parseUri].
     *
     * Returns null for any other URI — notably Supabase auth callbacks, which
     * must reach `handleDeeplinks` untouched.
     */
    fun parse(intent: Intent): PendingPostLink? {
        val extraPostId = intent.getStringExtra(EXTRA_POST_ID)
        if (!extraPostId.isNullOrBlank()) {
            return buildLink(
                extraPostId,
                intent.getStringExtra(EXTRA_COMMENT_ID)
                    ?: intent.data?.getQueryParameter("commentId")
                    ?: intent.data?.getQueryParameter("comment_id")
            )
        }

        val data = intent.data ?: return null
        return parseUri(data)
    }

    /**
     * Android-[Uri] overload used by [parse] and by instrumentation tests.
     * [Uri] has already decoded `pathSegments` and query values for us.
     */
    fun parseUri(uri: Uri): PendingPostLink? = match(
        scheme = uri.scheme,
        host = uri.host,
        pathSegments = uri.pathSegments,
        commentId = uri.getQueryParameter("commentId")
            ?: uri.getQueryParameter("comment_id"),
        queryPostId = uri.getQueryParameter("post_id")
            ?: uri.getQueryParameter("postId")
    )

    /**
     * Pure-JVM overload ([java.net.URI], no Android dependencies) so the rules in
     * [match] can be unit-tested on the JVM without Robolectric.
     *
     * Accepts a `String?` on purpose: callers pass `intent.dataString` or a
     * captured URL straight from a log line.
     */
    fun parseUri(raw: String?): PendingPostLink? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val parsed = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return null
        }

        // getPath() is percent-DECODED, rawQuery is not — decode the values.
        val segments = (parsed.path ?: "")
            .split('/')
            .filter { it.isNotEmpty() }

        val params = parseQuery(parsed.rawQuery)

        return match(
            scheme = parsed.scheme,
            host = parsed.host,
            pathSegments = segments,
            commentId = params["commentId"] ?: params["comment_id"],
            queryPostId = params["post_id"] ?: params["postId"]
        )
    }

    /**
     * The single decision table shared by both overloads.
     *
     *  - custom schemes: host must be `post` (`agora://post/{id}`), or the host
     *    may be empty when the path starts with `post` (`agora:///post/{id}`).
     *  - web: host must be [WEB_HOSTS] (case-insensitive) and the first path
     *    segment must be `post` or its short alias `p`. `http` is accepted only
     *    so a downgraded share link is not a dead end; the manifest only
     *    declares `https`, so this branch is defensive.
     *  - anything else: the `?post_id=` query fallback, then null.
     */
    private fun match(
        scheme: String?,
        host: String?,
        pathSegments: List<String>,
        commentId: String?,
        queryPostId: String?
    ): PendingPostLink? {
        val normalizedScheme = scheme?.trim()?.lowercase() ?: return null
        val segments = pathSegments.map { it.trim() }.filter { it.isNotEmpty() }
        val normalizedHost = host?.trim()?.lowercase()

        val pathPostId: String? = when {
            normalizedScheme in CUSTOM_SCHEMES -> when {
                normalizedHost == POST_SEGMENT -> segments.firstOrNull()

                // agora:///post/{id} — browsers drop the authority for unknown
                // schemes when normalising a hand-typed link.
                normalizedHost.isNullOrEmpty() &&
                    segments.firstOrNull()?.equals(POST_SEGMENT, ignoreCase = true) == true ->
                    segments.getOrNull(1)

                else -> null
            }

            normalizedScheme == "https" || normalizedScheme == "http" ->
                if (normalizedHost != null && normalizedHost in WEB_HOSTS && isWebPostPath(segments)) {
                    segments.getOrNull(1)
                } else {
                    null
                }

            else -> null
        }

        // Path wins; the query is a legacy fallback for links shaped
        // `…?post_id={id}` produced by older share flows.
        return buildLink(pathPostId ?: queryPostId, commentId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates and constructs a link, or null.
     *
     * `posts.id` is a Postgres `uuid` column, so anything that is not a UUID
     * cannot be fetched — navigating to it would open the post screen and show
     * "post not found". Rejecting it here keeps the app on the feed instead.
     */
    private fun buildLink(postId: String?, commentId: String?): PendingPostLink? {
        val cleanId = postId?.trim().orEmpty()
        if (!isValidPostId(cleanId)) return null
        return PendingPostLink(
            postId = cleanId.lowercase(),
            commentId = commentId?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * `post/{id}` or `p/{id}` — the two web path shapes.
     *
     * Kept separate from the custom-scheme branch on purpose: `agora://p/{id}`
     * is meaningless (`p` would be read as the authority) and accepting it
     * would widen the public contract for no benefit.
     */
    private fun isWebPostPath(segments: List<String>): Boolean {
        val first = segments.firstOrNull() ?: return false
        return first.equals(POST_SEGMENT, ignoreCase = true) ||
            first.equals(POST_SEGMENT_ALIAS, ignoreCase = true)
    }

    /**
     * True for a canonical UUID. `UUID.fromString` also accepts short groups
     * ("1-1-1-1-1"), so the round-trip check is what actually enforces the
     * `8-4-4-4-12` shape.
     */
    private fun isValidPostId(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_POST_ID_LENGTH) return false
        val parsed = try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return parsed.toString().equals(value, ignoreCase = true)
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// Small JVM-only helpers.
//
// Deliberately file-level `private` rather than members of DeepLinkRouter:
// PendingPostLink (a nested class) calls encodeQueryValue from toRoute(), and a
// top-level private function is unambiguously visible to every declaration in
// this file — no reliance on nested-class access to the enclosing object.
// ─────────────────────────────────────────────────────────────────────────────

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split('&')
        .mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val key = pair.substringBefore('=', "")
            if (key.isEmpty()) return@mapNotNull null
            val value = pair.substringAfter('=', "")
            decodeQueryValue(key) to decodeQueryValue(value)
        }
        .toMap()
}

private fun decodeQueryValue(value: String): String =
    try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

/**
 * Percent-encodes a query value for the NavHost route. `URLEncoder` emits `+`
 * for spaces, which a URI parser reads as a literal plus, so it is converted
 * back to `%20`.
 */
private fun encodeQueryValue(value: String): String =
    try {
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        value
    }

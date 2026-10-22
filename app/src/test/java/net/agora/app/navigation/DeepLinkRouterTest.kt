package net.agora.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [DeepLinkRouter.parseUri] — no Robolectric, no device.
 *
 * These are the rules that decide whether a shared link opens the post or drops
 * the user on the feed, so every shape that Google Chrome, a chat client or an
 * old FCM payload can hand to the app is pinned here.
 */
class DeepLinkRouterTest {

    /** The UUID used in the bug report / share-link format. */
    private val postId = "0c298d8b-c2f0-4f38-8585-7199f41c7769"

    // ── The canonical share link ────────────────────────────────────────────

    @Test
    fun httpsAppLinkResolvesThePostId() {
        val link = DeepLinkRouter.parseUri("https://agora.in.net/post/$postId")

        assertNotNull(link)
        assertEquals(postId, link!!.postId)
        assertNull(link.commentId)
        assertEquals("post/$postId", link.toRoute())
    }

    @Test
    fun httpsAppLinkWithCommentResolvesBothIds() {
        val commentId = "0b3f0f8e-1e0c-4a4e-9a1e-0b2c3d4e5f60"
        val link = DeepLinkRouter.parseUri(
            "https://agora.in.net/post/$postId?commentId=$commentId"
        )

        assertEquals(postId, link!!.postId)
        assertEquals(commentId, link.commentId)
        assertEquals("post/$postId?commentId=$commentId", link.toRoute())
    }

    @Test
    fun commentIdSnakeCaseAliasIsAccepted() {
        val link = DeepLinkRouter.parseUri("https://agora.in.net/post/$postId?comment_id=abc123")
        assertEquals("abc123", link!!.commentId)
    }

    @Test
    fun trackingParametersAreIgnored() {
        val link = DeepLinkRouter.parseUri(
            "https://agora.in.net/post/$postId?utm_source=whatsapp&utm_medium=share"
        )
        assertEquals(postId, link!!.postId)
        assertNull(link.commentId)
    }

    @Test
    fun trailingSlashIsTolerated() {
        val link = DeepLinkRouter.parseUri("https://agora.in.net/post/$postId/")
        assertEquals(postId, link!!.postId)
    }

    @Test
    fun fragmentIsTolerated() {
        val link = DeepLinkRouter.parseUri("https://agora.in.net/post/$postId#comments")
        assertEquals(postId, link!!.postId)
    }

    // ── Host normalisation ──────────────────────────────────────────────────

    @Test
    fun hostComparisonIsCaseInsensitive() {
        val link = DeepLinkRouter.parseUri("https://AGORA.IN.NET/post/$postId")
        assertEquals(postId, link!!.postId)
    }

    @Test
    fun pathSegmentComparisonIsCaseInsensitive() {
        val link = DeepLinkRouter.parseUri("https://agora.in.net/Post/$postId")
        assertEquals(postId, link!!.postId)
    }

    @Test
    fun wwwHostIsAcceptedByTheParser() {
        // Deliberately NOT declared in the manifest (App Links verification is
        // all-or-nothing per app), but a redirect can still deliver this shape.
        val link = DeepLinkRouter.parseUri("https://www.agora.in.net/post/$postId")
        assertEquals(postId, link!!.postId)
    }

    // ── Short alias (/p/) ───────────────────────────────────────────────────

    @Test
    fun shortAliasResolvesThePostId() {
        val link = DeepLinkRouter.parseUri("https://agora.in.net/p/$postId")

        assertNotNull(link)
        assertEquals(postId, link!!.postId)
        assertEquals("post/$postId", link.toRoute())
    }

    @Test
    fun shortAliasWithCommentResolvesBothIds() {
        val commentId = "0b3f0f8e-1e0c-4a4e-9a1e-0b2c3d4e5f60"
        val link = DeepLinkRouter.parseUri(
            "https://agora.in.net/p/$postId?commentId=$commentId"
        )

        assertEquals(postId, link!!.postId)
        assertEquals(commentId, link.commentId)
    }

    @Test
    fun shortAliasStillRequiresAUuid() {
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/p/not-a-uuid"))
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/p/"))
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/p"))
    }

    @Test
    fun shortAliasIsNotAcceptedForCustomSchemes() {
        // `agora://p/{id}` would make `p` the authority; the custom-scheme
        // grammar stays `post`-only so the two schemes cannot drift.
        assertNull(DeepLinkRouter.parseUri("agora://p/$postId"))
        assertNull(DeepLinkRouter.parseUri("net.agora.app://p/$postId"))
    }

    // ── Retired origins: must NOT resolve ───────────────────────────────────

    @Test
    fun retiredOriginsAreRejected() {
        // auth-agora.info is retired: it was accepted for one release as a
        // courtesy to links already in circulation, the domain 301s to
        // agora.in.net, and the app no longer carries a second, unverifiable
        // host. auth-agora.online is retired too (never used in production),
        // and com.example.agora is the retired template package.
        assertNull(DeepLinkRouter.parseUri("https://auth-agora.info/post/$postId"))
        assertNull(DeepLinkRouter.parseUri("https://auth-agora.info/p/$postId"))
        assertNull(DeepLinkRouter.parseUri("https://auth-agora.online/post/$postId"))
        assertNull(DeepLinkRouter.parseUri("https://com.example.agora/post/$postId"))
    }

    @Test
    fun httpIsAcceptedDefensively() {
        val link = DeepLinkRouter.parseUri("http://agora.in.net/post/$postId")
        assertEquals(postId, link!!.postId)
    }

    // ── Custom schemes ──────────────────────────────────────────────────────

    @Test
    fun agoraSchemeResolvesThePostId() {
        val link = DeepLinkRouter.parseUri("agora://post/$postId")
        assertEquals(postId, link!!.postId)
    }

    @Test
    fun agoraSchemeWithEmptyAuthorityResolvesThePostId() {
        // chrome/browsers normalise agora://post/x to agora:///post/x in some flows
        val link = DeepLinkRouter.parseUri("agora:///post/$postId")
        assertEquals(postId, link!!.postId)
    }

    @Test
    fun applicationIdSchemeResolvesThePostId() {
        // `net.agora.app` is BOTH the applicationId and the Kotlin namespace
        // (one identity, docs §17), so an intent:// URI built from the package
        // name uses this scheme. Declared in the manifest, allow-listed in
        // Supabase.
        val link = DeepLinkRouter.parseUri("net.agora.app://post/$postId")
        assertEquals(postId, link!!.postId)

        // Browsers drop the authority when normalising an unknown scheme.
        val emptyAuthority = DeepLinkRouter.parseUri("net.agora.app:///post/$postId")
        assertEquals(postId, emptyAuthority!!.postId)
    }

    @Test
    fun applicationIdSchemeFromNotificationIsAccepted() {
        val link = DeepLinkRouter.parseUri(
            "net.agora.app://post/$postId?commentId=0b3f0f8e-1e0c-4a4e-9a1e-0b2c3d4e5f60"
        )
        assertEquals(postId, link!!.postId)
        assertEquals("0b3f0f8e-1e0c-4a4e-9a1e-0b2c3d4e5f60", link.commentId)
    }

    @Test
    fun legacyApplicationIdSchemeStillResolvesThePostId() {
        // Builds up to the identity unification shipped as `net.in.agora`, so
        // notifications they already delivered carry that scheme. It is
        // accepted (never emitted) so those taps do not dead-end on the feed.
        // Safe to delete this test together with the manifest entry once no
        // such link can reach a user.
        val link = DeepLinkRouter.parseUri("net.in.agora://post/$postId")
        assertEquals(postId, link!!.postId)

        val emptyAuthority = DeepLinkRouter.parseUri("net.in.agora:///post/$postId")
        assertEquals(postId, emptyAuthority!!.postId)
    }

    // ── Legacy query fallback ───────────────────────────────────────────────

    @Test
    fun postIdQueryParameterIsAFallback() {
        val link = DeepLinkRouter.parseUri("https://agora.in.net/?post_id=$postId")
        assertEquals(postId, link!!.postId)
    }

    @Test
    fun uuidIsLowercased() {
        val link = DeepLinkRouter.parseUri(
            "https://agora.in.net/post/0C298D8B-C2F0-4F38-8585-7199F41C7769"
        )
        assertEquals(postId, link!!.postId)
    }

    // ── Rejections: these must NOT navigate ─────────────────────────────────

    @Test
    fun supabaseAuthCallbackIsNotAPostLink() {
        assertNull(
            DeepLinkRouter.parseUri(
                "https://agora.in.net/auth-callback?code=abc&state=xyz"
            )
        )
    }

    @Test
    fun loginCallbackIsNotAPostLink() {
        assertNull(
            DeepLinkRouter.parseUri("https://agora.in.net/login-callback#access_token=abc")
        )
    }

    @Test
    fun otherHostsAreRejected() {
        assertNull(DeepLinkRouter.parseUri("https://evil.example/post/$postId"))
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net.evil.example/post/$postId"))
        assertNull(DeepLinkRouter.parseUri("https://notagora.in.net/post/$postId"))
    }

    @Test
    fun otherSchemesAreRejected() {
        assertNull(DeepLinkRouter.parseUri("ftp://agora.in.net/post/$postId"))
        assertNull(DeepLinkRouter.parseUri("intent://post/$postId#Intent;end"))
        assertNull(DeepLinkRouter.parseUri("mailto:someone@agora.in.net"))
    }

    @Test
    fun missingPostIdIsRejected() {
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/post/"))
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/post"))
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/"))
        assertNull(DeepLinkRouter.parseUri("agora://post"))
        assertNull(DeepLinkRouter.parseUri("agora://post/"))
    }

    @Test
    fun nonUuidPostIdsAreRejected() {
        // posts.id is a uuid column: anything else can never be fetched, so
        // navigating would only show the "post not found" screen.
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/post/not-a-uuid"))
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/post/12345"))
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/post/1-1-1-1-1"))
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/post/..%2F..%2Fetc%2Fpasswd"))
    }

    @Test
    fun blankAndNullInputsAreRejected() {
        assertNull(DeepLinkRouter.parseUri(null as String?))
        assertNull(DeepLinkRouter.parseUri(""))
        assertNull(DeepLinkRouter.parseUri("   "))
    }

    @Test
    fun malformedUriDoesNotThrow() {
        assertNull(DeepLinkRouter.parseUri("https://agora.in.net/post/has space"))
        assertNull(DeepLinkRouter.parseUri("://"))
    }

    // ── submit() is the other public entry point ────────────────────────────

    @Test
    fun submitIgnoresInvalidIds() {
        // Reset the singleton first: earlier tests in this class may have left a
        // valid link behind, which would make the assertions below vacuous.
        DeepLinkRouter.pending.value?.let { DeepLinkRouter.consume(it) }

        DeepLinkRouter.submit("not-a-uuid", null)
        assertNull(DeepLinkRouter.pending.value)

        DeepLinkRouter.submit(null, null)
        assertNull(DeepLinkRouter.pending.value)

        DeepLinkRouter.submit("", null)
        assertNull(DeepLinkRouter.pending.value)
    }

    @Test
    fun submitPublishesAValidLink() {
        DeepLinkRouter.submit(postId, null)
        val pending = DeepLinkRouter.pending.value
        assertEquals(postId, pending?.postId)
        DeepLinkRouter.consume(pending!!)
        assertNull(DeepLinkRouter.pending.value)
    }

    // ── Route building ──────────────────────────────────────────────────────

    @Test
    fun routeOmitsCommentWhenBlank() {
        assertEquals("post/$postId", DeepLinkRouter.PendingPostLink(postId).toRoute())
        assertEquals(
            "post/$postId",
            DeepLinkRouter.PendingPostLink(postId, "   ").toRoute()
        )
    }

    @Test
    fun routeEncodesUnsafeCommentIds() {
        val route = DeepLinkRouter.PendingPostLink(postId, "a b&c").toRoute()
        assertTrue(route.startsWith("post/$postId?commentId="))
        assertTrue("+ must not be used for spaces", !route.contains("+"))
        assertTrue(route.contains("%20"))
        assertTrue(route.contains("%26"))
    }
}

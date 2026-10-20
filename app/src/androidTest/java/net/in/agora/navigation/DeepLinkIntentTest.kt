package net.in.agora.navigation

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation coverage for the *Android* half of deep-link handling:
 * [DeepLinkRouter.parse] and [DeepLinkRouter.parseUri] both take real
 * `android.net.Uri` values here, so the behaviour is verified against the same
 * URI parser the manifest-matching code uses.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest --tests '*DeepLinkIntentTest'
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkIntentTest {

    private val postId = "0c298d8b-c2f0-4f38-8585-7199f41c7769"
    private val commentId = "0b3f0f8e-1e0c-4a4e-9a1e-0b2c3d4e5f60"

    private fun viewIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    @Test
    fun httpsAppLinkIntentResolvesThePost() {
        val link = DeepLinkRouter.parse(viewIntent("https://agora.in.net/post/$postId"))

        assertNotNull(link)
        assertEquals(postId, link!!.postId)
        assertEquals("post/$postId", link.toRoute())
    }

    @Test
    fun httpsAppLinkIntentWithCommentResolvesBoth() {
        val link = DeepLinkRouter.parse(
            viewIntent("https://agora.in.net/post/$postId?commentId=$commentId")
        )

        assertEquals(postId, link!!.postId)
        assertEquals(commentId, link.commentId)
    }

    @Test
    fun agoraSchemeIntentResolvesThePost() {
        val link = DeepLinkRouter.parse(viewIntent("agora://post/$postId"))
        assertEquals(postId, link!!.postId)
    }

    @Test
    fun applicationIdSchemeIntentResolvesThePost() {
        val link = DeepLinkRouter.parse(viewIntent("net.in.agora://post/$postId"))
        assertEquals(postId, link!!.postId)
    }

    @Test
    fun fcmExtrasResolveWithoutAnyUri() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            putExtra("post_id", postId)
            putExtra("comment_id", commentId)
        }

        val link = DeepLinkRouter.parse(intent)
        assertEquals(postId, link!!.postId)
        assertEquals(commentId, link.commentId)
    }

    @Test
    fun fcmExtrasWinOverAContradictoryUri() {
        val intent = viewIntent("https://agora.in.net/post/$commentId").apply {
            putExtra("post_id", postId)
        }
        assertEquals(postId, DeepLinkRouter.parse(intent)!!.postId)
    }

    @Test
    fun supabaseCallbackIntentIsLeftAlone() {
        assertNull(
            DeepLinkRouter.parse(
                viewIntent("https://agora.in.net/auth-callback?code=abc&state=xyz")
            )
        )
    }

    @Test
    fun launcherIntentWithoutDataIsNotAPostLink() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        assertNull(DeepLinkRouter.parse(intent))
    }

    /**
     * `Uri` exposes decoded [Uri.getPathSegments]; `java.net.URI` needs manual
     * splitting/decoding. The two entry points must agree, otherwise unit tests
     * and production behave differently.
     */
    @Test
    fun uriOverloadAgreesWithStringOverload() {
        val samples = listOf(
            "https://agora.in.net/post/$postId",
            "https://agora.in.net/post/$postId?commentId=$commentId",
            "https://AGORA.IN.NET/post/$postId/",
            "https://agora.in.net/post/$postId?utm_source=whatsapp",
            "https://agora.in.net/p/$postId",
            "https://auth-agora.info/post/$postId",
            "agora://post/$postId",
            "agora:///post/$postId",
            "net.in.agora://post/$postId?comment_id=$commentId",
            "https://agora.in.net/auth-callback?code=abc",
            "https://agora.in.net/post/not-a-uuid",
            "https://evil.example/post/$postId"
        )

        for (raw in samples) {
            val fromString = DeepLinkRouter.parseUri(raw)
            val fromUri = DeepLinkRouter.parseUri(Uri.parse(raw))
            assertEquals("postId mismatch for $raw", fromString?.postId, fromUri?.postId)
            assertEquals("commentId mismatch for $raw", fromString?.commentId, fromUri?.commentId)
        }
    }

    /**
     * The generated share URL must round-trip: what `FeedScreen` copies is what
     * the app can route.
     */
    @Test
    fun shortAliasIntentResolvesThePost() {
        assertEquals(
            postId,
            DeepLinkRouter.parse(viewIntent("https://agora.in.net/p/$postId"))!!.postId
        )
    }

    @Test
    fun legacyHostIntentResolvesThePost() {
        // Pre-migration links live in group chats forever; the retired host is
        // still declared in its own (non-verifying) manifest filter and must
        // route exactly like the canonical one.
        assertEquals(
            postId,
            DeepLinkRouter.parse(viewIntent("https://auth-agora.info/post/$postId"))!!.postId
        )
    }

    @Test
    fun generatedShareUrlRoundTrips() {
        val shareUrl = "https://${DeepLinkRouter.WEB_HOST}/post/$postId"
        val link = DeepLinkRouter.parse(viewIntent(shareUrl))
        assertEquals(postId, link!!.postId)
    }
}

package net.agora.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CloudinaryUrlHelper] — no device, no network.
 *
 * Two things are being protected here, and both are silent when wrong:
 *
 *  - **URL transforms**: a malformed one returns a 404 from Cloudinary, which in
 *    the UI looks like "this post has no media";
 *  - **public_id parsing**: a wrong value makes the destruction API answer
 *    `"not found"`, i.e. deleting a post *reports* success while the asset stays
 *    in Cloudinary forever — the exact bug this change exists to fix.
 */
class CloudinaryUrlHelperTest {

    private val imageUrl =
        "https://res.cloudinary.com/h9cocag7/image/upload/v1712345678/agora/posts/a1b2c3.jpg"

    private val videoUrl =
        "https://res.cloudinary.com/h9cocag7/video/upload/v1712345678/agora/posts/v9x8y7.mp4"

    // ── Feed image ──────────────────────────────────────────────────────────

    @Test
    fun feedImageInjectsTransformAndForcesWebp() {
        assertEquals(
            "https://res.cloudinary.com/h9cocag7/image/upload/f_auto,q_auto:eco,w_600/v1712345678/agora/posts/a1b2c3.webp",
            CloudinaryUrlHelper.feedImageUrl(imageUrl)
        )
    }

    @Test
    fun fullImageUses1440() {
        val result = CloudinaryUrlHelper.fullImageUrl(imageUrl)
        assertTrue(result.contains("f_auto,q_auto:good,w_1440"))
        assertTrue(result.endsWith(".webp"))
        // The version segment survives: it is part of the asset identity and
        // dropping it would let a stale CDN copy win.
        assertTrue(result.contains("/v1712345678/"))
    }

    @Test
    fun transformIsIdempotent() {
        // Applying the same transform twice must not stack a second segment.
        val once = CloudinaryUrlHelper.feedImageUrl(imageUrl)
        val twice = CloudinaryUrlHelper.feedImageUrl(once)
        assertEquals(once, twice)
    }

    @Test
    fun existingTransformIsReplacedNotStacked() {
        val withTransform = CloudinaryUrlHelper.feedImageUrl(imageUrl)
        // Switching from the 600 px preset to the 1440 px one replaces it.
        val upgraded = CloudinaryUrlHelper.fullImageUrl(withTransform)
        assertTrue(upgraded.contains("w_1440"))
        assertFalse(upgraded.contains("w_600"))
        assertEquals(1, Regex("/upload/").findAll(upgraded).count())
    }

    // ── Video ───────────────────────────────────────────────────────────────

    @Test
    fun videoPosterIsAStillImageOfTheVideo() {
        val poster = CloudinaryUrlHelper.videoPosterUrl(videoUrl)
        assertEquals(
            "https://res.cloudinary.com/h9cocag7/video/upload/so_auto,f_auto,q_auto:eco,w_600/v1712345678/agora/posts/v9x8y7.webp",
            poster
        )
        // Must stay under /video/upload/ and must not be handed to ExoPlayer.
        assertTrue(poster.contains("/video/upload/"))
        assertFalse(poster.endsWith(".mp4"))
    }

    @Test
    fun optimizedVideoKeepsPlayableExtensionAndCapsBitrate() {
        val stream = CloudinaryUrlHelper.optimizedVideoUrl(videoUrl)
        assertEquals(
            "https://res.cloudinary.com/h9cocag7/video/upload/f_auto,q_auto:good,vc_auto,w_720,br_2m/v1712345678/agora/posts/v9x8y7.mp4",
            stream
        )
        // A .webp here would return a poster frame and ExoPlayer would fail.
        assertTrue(stream.endsWith(".mp4"))
    }

    @Test
    fun optimizedVideoIsIdempotent() {
        val once = CloudinaryUrlHelper.optimizedVideoUrl(videoUrl)
        assertEquals(once, CloudinaryUrlHelper.optimizedVideoUrl(once))
    }

    // ── Non-Cloudinary URLs must be untouched ───────────────────────────────

    @Test
    fun nonCloudinaryUrlsPassThroughUnchanged() {
        val catbox = "https://files.catbox.moe/abc123.mp4"
        val supabase = "https://xyz.supabase.co/storage/v1/object/public/avatars/u/1.jpg"

        assertEquals(catbox, CloudinaryUrlHelper.feedImageUrl(catbox))
        assertEquals(catbox, CloudinaryUrlHelper.videoPosterUrl(catbox))
        assertEquals(catbox, CloudinaryUrlHelper.optimizedVideoUrl(catbox))
        assertEquals(supabase, CloudinaryUrlHelper.fullImageUrl(supabase))
        assertFalse(CloudinaryUrlHelper.isCloudinaryUrl(supabase))
        assertNull(CloudinaryUrlHelper.publicId(supabase))
    }

    // ── public_id / resource_type (destruction API) ─────────────────────────

    @Test
    fun publicIdStripsVersionAndExtension() {
        // The canonical case from the bug report.
        assertEquals("agora/posts/a1b2c3", CloudinaryUrlHelper.publicId(imageUrl))
        assertEquals("agora/posts/v9x8y7", CloudinaryUrlHelper.publicId(videoUrl))
    }

    @Test
    fun publicIdStripsAnExistingTransform() {
        val transformed = "https://res.cloudinary.com/h9cocag7/image/upload/f_auto,w_600/v17/agora/posts/x1.jpg"
        assertEquals("agora/posts/x1", CloudinaryUrlHelper.publicId(transformed))
    }

    @Test
    fun publicIdSurvivesFoldersDotsAndMissingVersion() {
        // No version segment.
        assertEquals(
            "agora/posts/x1",
            CloudinaryUrlHelper.publicId("https://res.cloudinary.com/h9cocag7/image/upload/agora/posts/x1.png")
        )
        // A public_id that itself looks like a transform must NOT be stripped.
        assertEquals(
            "my_photo",
            CloudinaryUrlHelper.publicId("https://res.cloudinary.com/h9cocag7/image/upload/my_photo.jpg")
        )
        // Dots inside the name are preserved; only the extension goes.
        assertEquals(
            "agora/posts/v1.2.final",
            CloudinaryUrlHelper.publicId("https://res.cloudinary.com/h9cocag7/image/upload/v99/agora/posts/v1.2.final.webp")
        )
    }

    @Test
    fun publicIdIgnoresQueryString() {
        assertEquals(
            "agora/posts/x1",
            CloudinaryUrlHelper.publicId("https://res.cloudinary.com/h9cocag7/image/upload/v1/agora/posts/x1.jpg?_a=BATA")
        )
    }

    @Test
    fun resourceTypeIsReadFromThePath() {
        assertEquals("image", CloudinaryUrlHelper.resourceType(imageUrl))
        assertEquals("video", CloudinaryUrlHelper.resourceType(videoUrl))
        assertEquals(
            "raw",
            CloudinaryUrlHelper.resourceType("https://res.cloudinary.com/h9cocag7/raw/upload/v1/docs/a.pdf")
        )
        assertNull(CloudinaryUrlHelper.resourceType("https://files.catbox.moe/abc.mp4"))
    }
}

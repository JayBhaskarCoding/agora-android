package net.agora.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for `res/layout/view_inline_video_player.xml`.
 *
 * The inline preview is center-cropped with
 * `AspectRatioFrameLayout.RESIZE_MODE_ZOOM`, which measures the video *larger*
 * than the 360 dp media plate (a 9:16 source becomes 360x640 dp). Only a surface
 * that is drawn as ordinary view content can be clipped back down to the
 * plate's rounded outline, so the player must be inflated as a **TextureView**.
 *
 * A `SurfaceView` (media3's default `surface_type`) is composited by
 * SurfaceFlinger in its own layer: neither `clipChildren`, nor `clipToOutline`
 * on an ancestor, nor Compose's `Modifier.clip()` can clip it — the video then
 * paints outside the post card. That regression is invisible in code review
 * (both are "a PlayerView"), so it is pinned here.
 *
 * Pure JVM test on purpose: no device, no Robolectric, runs in milliseconds.
 */
class InlineVideoPlayerLayoutContractTest {

    private fun layoutFile(): File {
        // Gradle runs unit tests with the module directory as the working
        // directory; walk up a few levels so the test also works when run from
        // the repository root or an IDE scratch directory.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "src/main/res/layout/view_inline_video_player.xml"
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, "app/$relative").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError(
            "Could not locate $relative from ${System.getProperty("user.dir")}. " +
                "The inline video layout was renamed or deleted — if that was " +
                "intentional, update this contract test and docs §12 together."
        )
    }

    /** XML with comments removed, so prose about the old bug cannot match. */
    private val markup: String
        get() = layoutFile().readText()
            .replace(Regex("(?s)<!--.*?-->"), "")

    @Test
    fun inlinePlayerUsesATextureView() {
        assertTrue(
            "The inline PlayerView must set app:surface_type=\"texture_view\"; " +
                "a SurfaceView cannot be clipped to the media plate.",
            markup.contains("""app:surface_type="texture_view""")
        )
    }

    @Test
    fun inlinePlayerNeverUsesASurfaceView() {
        assertFalse(
            "surface_type=\"surface_view\" reintroduces the video-overflow bug.",
            markup.contains("""app:surface_type="surface_view""")
        )
    }

    @Test
    fun inlinePlayerHasNoController() {
        // The preview shows a single centered play button (FeedVideoPlayer);
        // media3's controller would draw a second set of controls on top.
        assertTrue(markup.contains("""app:use_controller="false"""))
    }
}

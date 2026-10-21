package net.agora.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the launcher icon, the themed (monochrome) layer and the notification
 * icon.
 *
 * All three fail the same way: **silently, and only on a real device.** There is
 * no compile error, no lint error and no crash when an adaptive icon is used
 * where a status-bar icon is expected — the tray just draws a white square. This
 * repo has shipped that bug, plus a `<monochrome>` layer pointing at a bitmap,
 * which Android renders as a solid blob because a raster has no transparent
 * interior to punch the counter out with.
 *
 * The rules encoded here:
 *
 *  1. `<monochrome>` must be a **vector**. Android 13 themed icons are drawn
 *     from the layer's alpha channel; a WebP monochrome flattens the mark.
 *  2. The notification small icon must be a **dedicated, non-adaptive** drawable.
 *     `setSmallIcon(R.mipmap.ic_launcher)` is the classic bug: the system masks
 *     and tints the alpha, so an adaptive icon becomes a solid rectangle.
 *  3. FCM's background path must be told the icon too. When a push carries a
 *     top-level `notification` block and the app is backgrounded, FCM renders
 *     the tray entry itself and `PushNotificationService` never runs — so
 *     `setSmallIcon` alone is not enough.
 *  4. The legacy raster icons must exist for every density, because API 25 and
 *     below ignore `mipmap-anydpi-v26/` entirely.
 *
 * Pure JVM test: reads files, no device, no Robolectric.
 */
class LauncherIconContractTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate app/src/main from ${System.getProperty("user.dir")}")
    }

    private fun res(relative: String): File = File(repoRoot(), "app/src/main/res/$relative")

    private val manifest: String
        get() = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()

    private fun sourceFiles(): List<File> =
        File(repoRoot(), "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    // ── adaptive icon ───────────────────────────────────────────────────────

    @Test
    fun adaptiveIconDeclaresBackgroundForegroundAndMonochrome() {
        listOf("mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml").forEach { path ->
            val xml = res(path).readText()
            listOf("background", "foreground", "monochrome").forEach { layer ->
                assertTrue("$path is missing the <$layer> layer", xml.contains("<$layer "))
            }
        }
    }

    /**
     * The regression: `@mipmap/ic_launcher_foreground` made the themed icon a
     * solid blob, because WebP has no transparent counter to subtract.
     */
    @Test
    fun monochromeLayerIsAVector() {
        val xml = res("mipmap-anydpi-v26/ic_launcher.xml").readText()
        val drawable = Regex("""<monochrome\s+android:drawable="@([\w./]+)"""")
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?: throw AssertionError("<monochrome> has no android:drawable")

        assertFalse(
            "Themed icons are tinted from the layer's alpha channel, so <monochrome> " +
                "must be a vector; `$drawable` is a raster mipmap.",
            drawable.startsWith("mipmap/") || drawable.endsWith(".webp") || drawable.endsWith(".png")
        )

        val kind = drawable.substringBefore('/')
        val name = drawable.substringAfter('/')
        val file = listOf("xml", "webp", "png")
            .map { res("$kind/$name.$it") }
            .firstOrNull { it.isFile }
            ?: throw AssertionError("@$drawable does not resolve to a file under res/")
        assertEquals("@$drawable must be XML", "xml", file.extension)
        assertTrue("@$drawable must be a <vector>", file.readText().contains("<vector"))
    }

    /**
     * The counter and the accent must be holes, not painted shapes.
     *
     * The supplied monochrome drew the mark white over an opaque black 512x512
     * backdrop and painted the counter/accent opaque black. Alpha is then 255
     * everywhere inside the backdrop, so the system — which tints themed icons
     * from alpha alone — fills that whole square with the tint and the mark is
     * invisible (measured: 0.551 of the layer was opaque).
     */
    @Test
    fun monochromePunchesTheCounterOutWithEvenOddFill() {
        val xml = res("drawable/ic_launcher_monochrome.xml").readText()
        assertTrue(
            "ic_launcher_monochrome.xml must use android:fillType=\"evenOdd\" so the " +
                "counter and accent become holes instead of being painted.",
            xml.contains("android:fillType=\"evenOdd\"")
        )
        assertEquals(
            "The monochrome mark is two paths: ring+counter and arc+accent, each with " +
                "its counter/accent appended as a subpath.",
            2,
            Regex("android:pathData=\"").findAll(xml).count()
        )
        assertFalse(
            "The opaque 512x512 backdrop must not come back: it makes the whole layer " +
                "opaque, so the system tints a solid square.",
            xml.contains("m512,0v512h-512v-512z")
        )
    }

    /**
     * Confirms the monochrome actually reaches the layer's edge as a *hole*.
     * A cheap structural proxy: no path may paint a full-canvas rectangle.
     */
    @Test
    fun monochromeHasNoFullCanvasFill() {
        val xml = res("drawable/ic_launcher_monochrome.xml").readText()
        val fullCanvas = Regex("""pathData="[mM]512|pathData="[mM]0,0[vV]512""")
        assertFalse(
            "ic_launcher_monochrome.xml paints a full-canvas rectangle. Themed icons are " +
                "tinted from alpha, so this fills the entire icon with the tint.",
            fullCanvas.containsMatchIn(xml)
        )
    }

    // ── notification icon ───────────────────────────────────────────────────

    @Test
    fun notificationIconIsADedicatedNonAdaptiveDrawable() {
        val icon = res("drawable/ic_notification.xml")
        assertTrue("drawable/ic_notification.xml is missing", icon.isFile)

        val xml = icon.readText()
        assertTrue("ic_notification.xml must be a <vector>", xml.contains("<vector"))
        assertTrue(
            "ic_notification.xml must set an explicit 24dp size, or the system " +
                "scales it unpredictably in the status bar.",
            xml.contains("android:width=\"24dp\"") && xml.contains("android:height=\"24dp\"")
        )
        assertTrue(
            "ic_notification.xml must use evenOdd to keep the counter transparent.",
            xml.contains("android:fillType=\"evenOdd\"")
        )
    }

    /** No source file may hand an adaptive icon to setSmallIcon/setLargeIcon. */
    @Test
    fun notificationBuilderNeverUsesTheAdaptiveLauncherIcon() {
        val offenders = sourceFiles().filter { file ->
            Regex("""set(Small|Large)Icon\(\s*R\.mipmap\.ic_launcher""").containsMatchIn(file.readText())
        }
        assertTrue(
            "These files pass an adaptive icon to a notification Builder: " +
                "${offenders.map { it.name }}. The system draws notification icons as a " +
                "tinted alpha mask, so an adaptive icon renders as a solid white shape. " +
                "Use R.drawable.ic_notification instead.",
            offenders.isEmpty()
        )
    }

    @Test
    fun pushServiceUsesTheNotificationIcon() {
        val service = File(
            repoRoot(),
            "app/src/main/java/net/agora/app/service/PushNotificationService.kt"
        ).readText()
        assertTrue(
            "PushNotificationService must use R.drawable.ic_notification for setSmallIcon.",
            service.contains("setSmallIcon(R.drawable.ic_notification)")
        )
    }

    // ── FCM background path ─────────────────────────────────────────────────

    @Test
    fun manifestDeclaresFcmDefaultNotificationIcon() {
        assertTrue(
            "The backend sends a top-level `notification` block, so FCM renders the " +
                "tray entry itself while the app is backgrounded and setSmallIcon never " +
                "runs. AndroidManifest must therefore declare " +
                "com.google.firebase.messaging.default_notification_icon.",
            manifest.contains("com.google.firebase.messaging.default_notification_icon")
        )
        assertTrue(
            "The default notification icon must point at the non-adaptive drawable.",
            Regex(
                "default_notification_icon\"[\\s\\S]{0,200}?@drawable/ic_notification"
            ).containsMatchIn(manifest)
        )
        assertTrue(
            "com.google.firebase.messaging.default_notification_color should be set so " +
                "the accent matches the brand instead of FCM's grey default.",
            manifest.contains("com.google.firebase.messaging.default_notification_color")
        )
    }

    // ── legacy rasters ──────────────────────────────────────────────────────

    @Test
    fun legacyIconsExistForEveryDensity() {
        val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        densities.forEach { density ->
            listOf("ic_launcher", "ic_launcher_round").forEach { name ->
                assertTrue(
                    "mipmap-$density/$name.webp is missing. API 25 and below ignore " +
                        "mipmap-anydpi-v26/, so without it those devices fall back to a " +
                        "system default icon.",
                    res("mipmap-$density/$name.webp").isFile
                )
            }
        }
    }

    /**
     * A `mipmap/ic_launcher_foreground` raster shadows `drawable/ic_launcher_foreground`
     * and reintroduces the bitmap-monochrome bug through the back door.
     */
    @Test
    fun noRasterForegroundLayerShadowsTheVector() {
        val stale = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
            .map { res("mipmap-$it/ic_launcher_foreground.webp") }
            .filter { it.isFile }
        assertTrue(
            "Raster foreground layers are back: ${stale.map { it.parentFile.name }}. " +
                "They shadow drawable/ic_launcher_foreground.xml and break themed icons.",
            stale.isEmpty()
        )
    }

    @Test
    fun manifestStillPointsAtTheAdaptiveIcon() {
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }
}

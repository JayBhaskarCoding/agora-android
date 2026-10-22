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

    // ── splash screen ───────────────────────────────────────────────────────
    //
    // The splash icon is the app icon, drawn once, and it fails quietly in both
    // directions: an extra element on it reads as a loading spinner bolted to
    // the logo, and a mark scaled too large silently crowds the badge. Neither
    // shows up in a build log, so both are pinned here.

    /**
     * The mark's ink box, measured from its own path data with control points
     * included — i.e. a superset of the drawn ink, which makes the padding
     * assertion in [splashMarkIsInsetInsideTheBadge] conservative. It is safe to
     * hardcode because [splashKeepsTheLauncherMark] fails if the path data ever
     * changes.
     */
    private val markBox = floatArrayOf(107.15f, 104.70f, 392.59f, 391.07f)

    /** The badge's box in the same 288x288 space, before the badge group scale. */
    private val badgeBox = floatArrayOf(70.005f, 69.016f, 216.884f, 219.005f)

    /**
     * Returns the body of `<group android:name="...">`, honouring nesting.
     *
     * A regex cannot match balanced tags, and the splash nests three groups, so
     * the scan below is deliberate rather than lazy.
     */
    private fun groupBody(xml: String, name: String): String {
        val open = Regex("<group[^>]*android:name=\"" + name + "\"[^>]*>").find(xml)
            ?: throw AssertionError("agora_splash.xml has no <group android:name=\"$name\">")
        var depth = 1
        var i = open.range.last + 1
        val bodyStart = i
        while (true) {
            val nextOpen = xml.indexOf("<group", i)
            val nextClose = xml.indexOf("</group>", i)
            if (nextClose < 0) throw AssertionError("unbalanced <group> for \"$name\"")
            if (nextOpen in 0 until nextClose) {
                depth++
                i = nextOpen + "<group".length
            } else {
                depth--
                if (depth == 0) return xml.substring(bodyStart, nextClose)
                i = nextClose + "</group>".length
            }
        }
    }

    private fun floatAttr(block: String, name: String): Float {
        val raw = Regex("android:" + name + "=\"(-?[0-9.]+)\"").find(block)?.groupValues?.get(1)
            ?: throw AssertionError("missing android:$name in:\n$block")
        return raw.toFloat()
    }

    /**
     * There is no ring on the splash.
     *
     * An earlier revision drew a 280-degree gold arc around the badge and spun
     * *that* while the logo stood still — which is the opposite of what a splash
     * logo entrance should be, and reads as a progress spinner stuck to the
     * icon. The whole palette is pinned instead of merely banning known golds,
     * so any new decoration fails here rather than shipping.
     */
    @Test
    fun splashDrawsNothingButTheAppIcon() {
        val xml = res("drawable/agora_splash.xml").readText()
        val colours = Regex("#[0-9A-Fa-f]{6,8}").findAll(xml)
            .map { it.value.uppercase() }
            .toSet()

        assertEquals(
            "The splash may only paint the badge gradient and the white mark. Found $colours — " +
                "anything else is decoration on the app icon (the removed gold ring was one).",
            setOf("#FF4A49DF", "#FF322EBA", "#FFFEFEFE"),
            colours
        )
        assertFalse(
            "agora_splash.xml still declares a group named \"gold\"; that was the rotating ring.",
            Regex("android:name=\"gold").containsMatchIn(xml)
        )
        assertFalse(
            "agora_splash.xml still contains a stroked arc; the ring was the only stroked shape.",
            xml.contains("android:strokeColor")
        )
    }

    /**
     * The LOGO rotates — badge and mark together — exactly once.
     *
     * The single `<target>` must name a group that actually contains both
     * shapes. A target pointing at an empty wrapper would satisfy "has a
     * rotation" while leaving the icon static, which is precisely the bug this
     * replaces.
     */
    @Test
    fun splashRotatesTheWholeLogoExactlyOnce() {
        val xml = res("drawable/agora_splash.xml").readText()

        val targets = Regex("<target\\s+android:name=\"([\\w]+)\"\\s+android:animation=\"@animator/([\\w]+)\"")
            .findAll(xml)
            .toList()
        assertEquals(
            "The splash must animate exactly one thing — the logo. Found " +
                "${targets.map { it.groupValues[1] }}.",
            1,
            targets.size
        )
        assertEquals("logo", targets[0].groupValues[1])

        val logo = groupBody(xml, "logo")
        assertTrue(
            "The rotating `logo` group does not contain the badge, so the badge would stay " +
                "static while something else turned.",
            logo.contains("android:name=\"tile\"")
        )
        assertTrue(
            "The rotating `logo` group does not contain the mark, so the mark would stay " +
                "static while the badge turned.",
            logo.contains("android:name=\"glyph_ring\"") && logo.contains("android:name=\"glyph_arc\"")
        )

        val animator = res("animator/" + targets[0].groupValues[2] + ".xml").readText()
        assertTrue(
            "The splash animator does not animate `rotation`.",
            Regex("android:propertyName=\"rotation\"").containsMatchIn(animator)
        )
        assertTrue(
            "The logo must rotate a full turn: rotation 0 -> 360.",
            Regex("android:propertyName=\"rotation\"[\\s\\S]{0,200}?android:valueFrom=\"0\"").containsMatchIn(animator) &&
                Regex("android:propertyName=\"rotation\"[\\s\\S]{0,200}?android:valueTo=\"360\"").containsMatchIn(animator)
        )
        assertFalse(
            "android:repeatCount makes the logo keep spinning; the rotation must happen exactly " +
                "once. Omitting the attribute entirely means play-once.",
            animator.contains("android:repeatCount")
        )
        assertTrue(
            "The rotation must declare android:valueType=\"floatType\". Without it the platform " +
                "infers the type from the literals and can treat 0 -> 360 as an integer step.",
            Regex("android:propertyName=\"rotation\"[\\s\\S]{0,300}?android:valueType=\"floatType\"")
                .containsMatchIn(animator)
        )
        assertTrue(
            "The entrance must ease out rather than run linearly.",
            animator.contains("fast_out_slow_in")
        )
    }

    /**
     * The splash draws the LAUNCHER's mark, not a second copy of it.
     *
     * The splash carries the launcher's two white shapes with the launcher's two
     * accent subpaths appended (that is what makes the counters holes under
     * `fillType="evenOdd"`), so each splash path must *start with* the launcher
     * path verbatim. Redrawing the mark by hand is how the two drift apart.
     */
    @Test
    fun splashKeepsTheLauncherMark() {
        val splash = res("drawable/agora_splash.xml").readText()
        val launcher = res("drawable/ic_launcher_foreground.xml").readText()

        val launcherPaths = Regex("android:pathData=\"([^\"]+)\"").findAll(launcher)
            .map { it.groupValues[1] }
            .toList()
        val splashPaths = Regex("android:pathData=\"([^\"]+)\"").findAll(splash)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            "Expected the badge plus the mark's two shapes in agora_splash.xml, found " +
                "${splashPaths.size} paths.",
            3,
            splashPaths.size
        )
        assertTrue(
            "The splash's ring shape is not the launcher's ring shape. The splash must reuse " +
                "drawable/ic_launcher_foreground.xml's geometry, never a redrawn mark.",
            splashPaths[1].startsWith(launcherPaths[0])
        )
        assertTrue(
            "The splash's arc shape is not the launcher's arc shape.",
            splashPaths[2].startsWith(launcherPaths[2])
        )
    }

    /**
     * The mark sits comfortably inside the badge.
     *
     * The regression: the mark's own coordinate space runs to ~392 units, and at
     * the previous scale it covered 84% of the badge — its lower-right strokes
     * came within ~8% of the edge and looked about to collide with it. The
     * target band is 55-70%, matching the margin the adaptive launcher icon
     * keeps, with at least 12% clear on every side.
     */
    @Test
    fun splashMarkIsInsetInsideTheBadge() {
        val xml = res("drawable/agora_splash.xml").readText()
        val glyphTag = Regex("<group[^>]*android:name=\"glyph\"[^>]*>").find(xml)!!.value
        val badgeTag = Regex("<group[^>]*android:name=\"badge\"[^>]*>").find(xml)!!.value

        val pivotX = floatAttr(glyphTag, "pivotX")
        val pivotY = floatAttr(glyphTag, "pivotY")
        val scaleX = floatAttr(glyphTag, "scaleX")
        val scaleY = floatAttr(glyphTag, "scaleY")
        val translateX = floatAttr(glyphTag, "translateX")
        val translateY = floatAttr(glyphTag, "translateY")
        val badgeScale = floatAttr(badgeTag, "scaleX")

        /* VectorDrawable applies a group transform as
         *   translate(pivot) -> scale -> translate(-pivot) + translateXY
         * and the enclosing `badge` group then scales about (144,144).
         *
         * The badge itself is NOT inside the glyph group, so it gets the badge
         * scale only — mapping it through the glyph matrix would shrink it by
         * 0.32 and make this test pass on an icon that actually overflows. */
        fun glyphX(x: Float) = 144f + (pivotX + (x - pivotX) * scaleX + translateX - 144f) * badgeScale
        fun glyphY(y: Float) = 144f + (pivotY + (y - pivotY) * scaleY + translateY - 144f) * badgeScale
        fun badgeX(x: Float) = 144f + (x - 144f) * badgeScale
        fun badgeY(y: Float) = 144f + (y - 144f) * badgeScale

        val markLeft = glyphX(markBox[0]); val markRight = glyphX(markBox[2])
        val markTop = glyphY(markBox[1]); val markBottom = glyphY(markBox[3])
        val badgeLeft = badgeX(badgeBox[0]); val badgeRight = badgeX(badgeBox[2])
        val badgeTop = badgeY(badgeBox[1]); val badgeBottom = badgeY(badgeBox[3])

        val badgeWidth = badgeRight - badgeLeft
        val badgeHeight = badgeBottom - badgeTop

        val widthRatio = (markRight - markLeft) / badgeWidth
        assertTrue(
            "The mark covers ${"%.1f".format(widthRatio * 100)}% of the badge width. It must stay " +
                "in 55-70% so no stroke comes near the badge edge.",
            widthRatio in 0.55f..0.70f
        )

        val pads = listOf(
            "left" to ((markLeft - badgeLeft) / badgeWidth),
            "right" to ((badgeRight - markRight) / badgeWidth),
            "top" to ((markTop - badgeTop) / badgeHeight),
            "bottom" to ((badgeBottom - markBottom) / badgeHeight)
        )
        pads.forEach { (side, fraction) ->
            assertTrue(
                "Only ${"%.1f".format(fraction * 100)}% clear padding on the $side of the badge. " +
                    "The mark must sit inset on all four sides, not nearly touching the edge.",
                fraction >= 0.12f
            )
        }

        val markCentreX = (markLeft + markRight) / 2f
        val markCentreY = (markTop + markBottom) / 2f
        val badgeCentreX = (badgeLeft + badgeRight) / 2f
        val badgeCentreY = (badgeTop + badgeBottom) / 2f
        assertTrue(
            "The mark is off-centre in the badge: mark ($markCentreX, $markCentreY) vs badge " +
                "($badgeCentreX, $badgeCentreY).",
            kotlin.math.abs(markCentreX - badgeCentreX) < 1f &&
                kotlin.math.abs(markCentreY - badgeCentreY) < 1f
        )
    }
}

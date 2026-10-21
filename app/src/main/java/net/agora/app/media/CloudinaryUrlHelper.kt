package net.agora.app.media

/**
 * Every Cloudinary URL transformation the app uses, in one place.
 *
 * ── WHY URL TRANSFORMS AND NOT RE-UPLOADS ───────────────────────────────────
 * Cloudinary derives transformations on the fly and caches them at the CDN edge,
 * so asking for a 600 px WebP costs one signature-free URL change and zero
 * server work. Three consequences matter for this app:
 *
 *  - the feed never downloads a full-size asset (a 3 MB photo becomes ~30 KB);
 *  - a video's poster frame is a *still image* derived server-side, so the feed
 *    can show a thumbnail without transferring a single byte of video;
 *  - the original upload is never modified, so `public_id` stays stable and the
 *    destruction API keeps working later.
 *
 * ── URL SHAPE ───────────────────────────────────────────────────────────────
 * ```
 * https://res.cloudinary.com/<cloud>/<resource>/upload/<transforms?>/v<version>/<public_id>.<ext>
 *                                                         ^^^^^^^^^^  ^^^^^^^^^^  ^^^^^^^^^^^^^^
 * ```
 * [withTransform] replaces the transform segment (it does not stack a second
 * one, so calling it twice is idempotent) and can force the extension.
 *
 * ⚠️ URLs that are not Cloudinary (legacy Catbox links, Supabase Storage
 * avatars) are returned **unchanged** — the helper must never corrupt a URL it
 * does not own.
 *
 * ⚠️ There is deliberately NO signing code here. Destroying an asset needs the
 * account's API secret, which must never ship in an APK; the signed call lives
 * in the `delete-cloudinary-asset` Edge Function (docs §13).
 *
 * Pure Kotlin on purpose (no Android, no network): every rule below is unit
 * tested on the JVM in `CloudinaryUrlHelperTest`.
 */
object CloudinaryUrlHelper {

    /** Cloud name segment of every asset URL this app uploads. */
    const val CLOUD_NAME = "h9cocag7"

    private const val UPLOAD_MARKER = "/upload/"
    private const val CLOUDINARY_HOST = "res.cloudinary.com"

    // ── Transform presets ───────────────────────────────────────────────────
    //
    // f_auto      -> best format the client accepts (WebP on Android)
    // q_auto:*    -> perceptually lossless-ish compression, `eco` for thumbs
    // w_*         -> width cap; Cloudinary keeps the aspect ratio
    // so_auto     -> *video only*: pick a representative poster frame
    // vc_auto     -> *video only*: let Cloudinary choose the codec
    // br_2m       -> *video only*: target ~2 Mbps, enough for 720p H.264

    /** Feed/single-post image preview: ~30 KB per cell. */
    private const val FEED_IMAGE_TRANSFORM = "f_auto,q_auto:eco,w_600"

    /** Full-screen image: sharp on a 1440 px-wide device without shipping the original. */
    private const val FULL_IMAGE_TRANSFORM = "f_auto,q_auto:good,w_1440"

    /** Video poster frame — a still image, never the video stream. */
    private const val VIDEO_POSTER_TRANSFORM = "so_auto,f_auto,q_auto:eco,w_600"

    /** Streamed video: 720p-capped, ~2 Mbps, adaptive codec. */
    private const val VIDEO_STREAM_TRANSFORM = "f_auto,q_auto:good,vc_auto,w_720,br_2m"

    /**
     * Cloudinary transformation keys. Used to decide whether the first segment
     * after `/upload/` is a transformation to be replaced or part of the
     * `public_id` (a folder/file name may legitimately look like `my_photo`).
     */
    private val TRANSFORM_KEYS = setOf(
        "a", "ac", "af", "ar", "b", "bo", "br", "c", "co", "cs", "d", "dl", "dn",
        "dpr", "du", "e", "eo", "f", "fl", "fn", "g", "h", "if", "ki", "l", "o",
        "p", "pg", "q", "r", "s", "so", "sp", "sr", "t", "u", "vc", "w", "x", "y", "z"
    )

    private val VERSION_SEGMENT = Regex("^v\\d+$")

    // ── Public URL builders ─────────────────────────────────────────────────

    /** `…/image/upload/f_auto,q_auto:eco,w_600/<public_id>.webp` */
    fun feedImageUrl(url: String): String =
        withTransform(url, FEED_IMAGE_TRANSFORM, extension = "webp")

    /** `…/image/upload/f_auto,q_auto:good,w_1440/<public_id>.webp` */
    fun fullImageUrl(url: String): String =
        withTransform(url, FULL_IMAGE_TRANSFORM, extension = "webp")

    /**
     * Poster frame for a video: the **image** derived from the first meaningful
     * frame, not the video itself. This is what makes the feed thumbnail-first:
     * an `<img>` request of ~20–40 KB instead of a multi-MB MP4.
     */
    fun videoPosterUrl(url: String): String =
        withTransform(url, VIDEO_POSTER_TRANSFORM, extension = "webp")

    /** Compressed stream URL handed to ExoPlayer *only after* the user taps play. */
    fun optimizedVideoUrl(url: String): String =
        withTransform(url, VIDEO_STREAM_TRANSFORM, extension = null)

    /** True when [url] is an asset this app can transform / destroy. */
    fun isCloudinaryUrl(url: String): Boolean =
        url.contains(CLOUDINARY_HOST) && url.contains(UPLOAD_MARKER)

    // ── Destruction API support ─────────────────────────────────────────────

    /**
     * `"images"`/`"videos"` → `"image"`/`"video"`, i.e. the `<resource_type>`
     * path segment of the destruction endpoint. Read from the URL itself rather
     * than guessed from the extension: Cloudinary decides it at upload time, and
     * `/raw/` assets have no image/video semantics at all.
     *
     * Returns null when the URL is not a Cloudinary upload URL.
     */
    fun resourceType(url: String): String? {
        val index = url.indexOf(UPLOAD_MARKER)
        if (index < 0) return null
        val before = url.substring(0, index).trimEnd('/')
        val type = before.substringAfterLast('/').lowercase()
        return type.takeIf { it.isNotEmpty() && it in setOf("image", "video", "raw") }
    }

    /**
     * Extracts the exact `public_id` — folder prefix included, version segment,
     * transformation segment and file extension removed.
     *
     * ```
     * https://res.cloudinary.com/h9cocag7/image/upload/f_auto,w_600/v1712/agora/posts/a1b2.jpg
     *                                  -> "agora/posts/a1b2"
     * ```
     *
     * `public_id` is the only identifier the destruction API accepts, so this
     * must be exact: a wrong value destroys nothing and returns `"not found"`.
     */
    fun publicId(url: String): String? {
        val index = url.indexOf(UPLOAD_MARKER)
        if (index < 0) return null

        val segments = url.substring(index + UPLOAD_MARKER.length)
            .substringBefore('?')
            .substringBefore('#')
            .split('/')
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (segments.isEmpty()) return null

        // 1. drop the transformation segment, if any
        if (segments.size > 1 && isTransformSegment(segments.first())) {
            segments.removeAt(0)
        }
        // 2. drop the version segment (`v1712…`), which is not part of public_id
        if (segments.size > 1 && VERSION_SEGMENT.matches(segments.first())) {
            segments.removeAt(0)
        }
        if (segments.isEmpty()) return null

        // 3. drop the file extension from the LAST segment only — folders may
        //    contain dots, and the public_id itself may too (`my.post`).
        val last = segments.lastIndex
        val name = segments[last]
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val ext = name.substring(dot + 1)
            if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) {
                segments[last] = name.substring(0, dot)
            }
        }

        val id = segments.joinToString("/")
        return id.takeIf { it.isNotBlank() }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Replaces (never stacks) the transform segment after `/upload/`.
     *
     * @param extension `null` keeps the original extension — required for video
     *   streams, where changing `.mp4` to `.webp` would request a poster frame
     *   instead of playable media.
     */
    private fun withTransform(url: String, transform: String, extension: String?): String {
        val index = url.indexOf(UPLOAD_MARKER)
        if (index < 0) return url // not Cloudinary: leave untouched

        val prefix = url.substring(0, index + UPLOAD_MARKER.length)
        val segments = url.substring(index + UPLOAD_MARKER.length)
            .split('/')
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (segments.isEmpty()) return url

        if (isTransformSegment(segments.first())) segments.removeAt(0)
        if (segments.isEmpty()) return url

        val last = segments.lastIndex
        if (extension != null) {
            val name = segments[last]
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            segments[last] = "$stem.$extension"
        }

        return prefix + transform + "/" + segments.joinToString("/")
    }

    /**
     * A transformation segment is a comma-separated list of `<key>_<value>`
     * tokens whose keys are all known Cloudinary keys. Requiring *every* key to
     * be known is what stops `my_photo.jpg` (a plausible public_id) from being
     * mistaken for a transform and deleted from the path.
     */
    private fun isTransformSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment.contains('.')) return false
        val tokens = segment.split(',')
        if (tokens.isEmpty()) return false
        return tokens.all { token ->
            val key = token.substringBefore('_', missingDelimiterValue = "")
            key in TRANSFORM_KEYS && token.contains('_') && token.length > key.length + 1
        }
    }
}

package net.agora.app.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import java.io.File

/**
 * Drops local copies of a deleted post's media.
 *
 * Removing the database row is not the end of the story on-device: the image and
 * video bytes stay in Coil's disk cache and in Media3's `SimpleCache`, so a
 * deleted post's photo can still be shown from cache (and still occupies the
 * user's storage) until the LRU evictor happens to reach it. That is both a
 * privacy smell and wasted space inside the 60 MB budget.
 *
 * Both caches are keyed by URL **string**, and the app never requests a raw URL:
 * it requests one of the four Cloudinary renditions. So every variant is evicted,
 * not just the one stored in the post row.
 */
object MediaCacheCleaner {

    private const val TAG = "MediaCacheCleaner"

    /**
     * Evicts [mediaUrls] and their derived renditions from Coil and Media3.
     * Best-effort: a cache miss or an eviction race is not an error.
     */
    fun evict(context: Context, mediaUrls: List<String>) {
        if (mediaUrls.isEmpty()) return
        val appContext = context.applicationContext
        val variants = mediaUrls.flatMap { url ->
            listOf(
                url,
                CloudinaryUrlHelper.feedImageUrl(url),
                CloudinaryUrlHelper.fullImageUrl(url),
                CloudinaryUrlHelper.videoPosterUrl(url),
                CloudinaryUrlHelper.optimizedVideoUrl(url)
            )
        }.toSet()

        evictCoil(appContext, variants)
        evictVideoCache(appContext, variants)
    }

    private fun evictCoil(context: Context, urls: Set<String>) {
        try {
            val loader = context.imageLoader
            val diskCache = loader.diskCache
            var removed = 0
            urls.forEach { url ->
                // Coil's cache key is the request data (the URL string) — the
                // same value ImageRequest.Builder.data(...) receives.
                if (diskCache?.remove(url) == true) removed++
                runCatching {
                    loader.memoryCache?.remove(coil.memory.MemoryCache.Key(url))
                }
            }
            if (removed > 0) Log.d(TAG, "evicted $removed image cache entr(ies)")
        } catch (e: Exception) {
            Log.w(TAG, "image cache eviction failed: ${e.localizedMessage}", e)
        }
    }

    // SimpleCache and its keys()/removeResource() are @UnstableApi (ERROR-level
    // opt-in), so the opt-in has to be declared at the call site, not just inside
    // VideoCache itself.
    @OptIn(UnstableApi::class)
    private fun evictVideoCache(context: Context, urls: Set<String>) {
        try {
            val cache = VideoCache.getCache(context)
            // SimpleCache has no "remove by prefix"; the key IS the URI, so match
            // the exact strings. Guarded because a key can vanish between the
            // enumeration and removeResource(), and that is not an error for us.
            //
            // `Cache.getKeys()` is a JAVA getter, so Kotlin surfaces it as the
            // `keys` property — `cache.keys()` is not a function call, and the
            // compiler cannot bind a type argument for it. Call the method by
            // its real name and pin the platform type on the way in, so nothing
            // downstream has to infer anything.
            val cachedKeys: Set<String> = cache.getKeys()
            val toRemove: List<String> = cachedKeys.filter { cachedKey ->
                urls.any { url -> cachedKey == url || cachedKey.startsWith(url) }
            }
            toRemove.forEach { key ->
                runCatching { cache.removeResource(key) }
            }
            if (toRemove.isNotEmpty()) Log.d(TAG, "evicted ${toRemove.size} video cache entr(ies)")
        } catch (e: Exception) {
            Log.w(TAG, "video cache eviction failed: ${e.localizedMessage}", e)
        }
    }

    /**
     * Older builds wrote upload temp files next to the caches; deleting a post
     * cannot have left any of those behind, but a sweep here keeps the "clean up
     * local references" step honest without waiting for the next app start.
     */
    fun sweepUploadTemps(context: Context) {
        try {
            val dir: File = context.applicationContext.cacheDir
            dir.listFiles()
                ?.filter { TempFileSweeper.isMediaTempFile(it) }
                ?.forEach { file -> if (!file.delete()) Log.w(TAG, "could not delete ${file.name}") }
        } catch (e: Exception) {
            Log.w(TAG, "temp sweep failed: ${e.localizedMessage}", e)
        }
    }
}

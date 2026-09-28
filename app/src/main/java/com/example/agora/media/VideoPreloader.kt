package com.example.agora.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Best-effort pre-caching of the next feed videos into [VideoCache]'s
 * SimpleCache, so playback (or the fullscreen player) starts instantly instead
 * of waiting on the network.
 *
 * Reads the first [PRECACHE_BYTES] of each URL through a [CacheDataSource] —
 * every byte read is written into the shared SimpleCache as a side effect.
 * Work is single-threaded and cancels the previous batch when the user scrolls
 * to a new position, so a fast scroll never builds a download queue.
 */
@OptIn(UnstableApi::class)
object VideoPreloader {

    /** How much of each upcoming video to warm (first ~3 MB ≈ several seconds of 720p). */
    private const val PRECACHE_BYTES = 3L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null

    /** Tracks URLs already warmed this session to avoid redundant work. */
    private val warmedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun prefetch(context: Context, urls: List<String>) {
        val appContext = context.applicationContext
        val targets = urls.filter { it.isNotBlank() && it !in warmedUrls }
        if (targets.isEmpty()) return

        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            for (url in targets) {
                try {
                    warmUp(appContext, url)
                    warmedUrls.add(url)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Best effort: a failed prefetch must never surface to the user.
                }
            }
        }
    }

    fun isWarmed(url: String): Boolean = url in warmedUrls

    private fun warmUp(context: Context, url: String) {
        val dataSource = VideoCache.getCacheDataSourceFactory(context).createDataSource()
        val dataSpec = androidx.media3.datasource.DataSpec(
            Uri.parse(url),
            /* position = */ 0L,
            /* length = */ PRECACHE_BYTES
        )
        try {
            dataSource.open(dataSpec)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < PRECACHE_BYTES) {
                val toRead = minOf(buffer.size.toLong(), PRECACHE_BYTES - total).toInt()
                val read = dataSource.read(buffer, 0, toRead)
                if (read < 0) break
                total += read
            }
        } finally {
            try {
                dataSource.close()
            } catch (_: Exception) {
            }
        }
    }
}

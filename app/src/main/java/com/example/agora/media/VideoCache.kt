package com.example.agora.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@OptIn(UnstableApi::class)
object VideoCache {

    private const val MAX_CACHE_SIZE_BYTES: Long = 200 * 1024 * 1024 // 200MB Cache Limit

    /**
     * Browser User-Agent used for every upstream media request.
     *
     * Catbox (and most free file hosts behind bot protection) rejects the default
     * ExoPlayer UA (`ExoPlayerLib/<version>`) with a 403 + HTML body. Because the
     * HTTP layer has no bytes to hand back, the ProgressiveMediaSource fails before
     * a single byte reaches [SimpleCache] — which is exactly why
     * `cache/media3_video_cache` stays at 0 bytes while the surface renders black.
     * Presenting a normal desktop Chrome UA gets the real MP4.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var cacheDataSourceFactory: CacheDataSource.Factory? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        return simpleCache ?: synchronized(this) {
            simpleCache ?: run {
                val cacheDir = File(context.cacheDir, "media3_video_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE_BYTES)
                val databaseProvider = StandaloneDatabaseProvider(context)
                SimpleCache(cacheDir, evictor, databaseProvider).also {
                    simpleCache = it
                }
            }
        }
    }

    @Synchronized
    fun getCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        return cacheDataSourceFactory ?: synchronized(this) {
            cacheDataSourceFactory ?: run {
                val cache = getCache(context)

                // Upstream (network) source for cache misses. The browser UA is what
                // stops Catbox from answering 403, and cross-protocol redirects cover
                // http -> https (or a CDN host hop) during the initial handshake.
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)

                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).also {
                        cacheDataSourceFactory = it
                    }
            }
        }
    }
}

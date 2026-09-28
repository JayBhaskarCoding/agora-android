package com.example.agora.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@OptIn(UnstableApi::class)
object VideoCache {

    private const val MAX_CACHE_SIZE_BYTES: Long = 200 * 1024 * 1024 // 200MB Cache Limit

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

                // Upstream (network) source for cache misses: OkHttp-backed, browser UA,
                // automatic retry on stale pooled connections. See [MediaHttpClient] for
                // why DefaultHttpDataSource/HttpURLConnection is not good enough here.
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(MediaHttpClient.dataSourceFactory())
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).also {
                        cacheDataSourceFactory = it
                    }
            }
        }
    }
}

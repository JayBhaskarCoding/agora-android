package net.agora.app.media

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

    /**
     * Hard ceiling for on-disk video bytes.
     *
     * 35 MB is deliberate: Coil's image cache is capped at 25 MB
     * (`AgoraApplication`), so total media cache tops out at ~60 MB on every
     * device. The previous 200 MB ceiling is what let three short clips plus
     * prefetching push `cacheDir` past 85 MB — the evictor cannot shrink a cache
     * it is allowed to grow.
     *
     * [LeastRecentlyUsedCacheEvictor] trims to this size on every write, at
     * cache open, and after a crash, so the limit holds without manual cleanup.
     */
    private const val MAX_CACHE_SIZE_BYTES: Long = 35L * 1024 * 1024 // 35 MB

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

package net.agora.app.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Deletes leftover media temp files from `cacheDir` at app start.
 *
 * Every temp file the upload pipeline writes uses one of [PREFIXES], and the
 * normal paths delete them in a `finally` ([MediaCompressor]). The gap that
 * remains is the process being killed mid-export — an ANR, a swipe from recents,
 * a low-memory kill during a 720p transcode. Those files are invisible to the
 * user and, because they sit in `cacheDir`, are never surfaced by the system's
 * storage UI either. On a device that records 4K video they are tens of MB each.
 *
 * [STALE_AFTER_MS] exists so a sweep can never race an upload that is running in
 * another process (the FCM service) with a file that was just created.
 */
object TempFileSweeper {

    private const val TAG = "TempFileSweeper"

    /** Anything in `cacheDir` starting with one of these belongs to the media pipeline. */
    private val PREFIXES = listOf("upload_", "compressed_", "compressed_upload_")

    /** A file younger than this may belong to an in-flight upload. */
    private const val STALE_AFTER_MS = 60 * 60 * 1000L // 1 hour

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs off the main thread — startup must not block on a directory listing
     * (a cache with thousands of Coil entries is slow to walk on cheap storage).
     */
    fun sweepAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch { sweep(appContext) }
    }

    /** Synchronous sweep; exposed for tests and for a manual "free space" action. */
    fun sweep(context: Context): Int {
        val cacheDir = context.applicationContext.cacheDir ?: return 0
        val now = System.currentTimeMillis()
        var deleted = 0
        var freed = 0L

        val files = try {
            cacheDir.listFiles()
        } catch (e: Exception) {
            Log.w(TAG, "could not list cacheDir: ${e.localizedMessage}", e)
            null
        } ?: return 0

        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (PREFIXES.none { name.startsWith(it) }) continue
            if (now - file.lastModified() < STALE_AFTER_MS) continue

            val size = file.length()
            if (file.delete()) {
                deleted++
                freed += size
            } else {
                Log.w(TAG, "could not delete stale temp file: $name")
            }
        }

        if (deleted > 0) {
            Log.d(TAG, "swept $deleted stale temp file(s), ${freed / 1024} KB reclaimed")
        }
        return deleted
    }

    /** True when [file] is one of ours — used to keep the sweeper in sync with the writers. */
    fun isMediaTempFile(file: File): Boolean = PREFIXES.any { file.name.startsWith(it) }
}

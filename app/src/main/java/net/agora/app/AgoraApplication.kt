package net.agora.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import net.agora.app.data.DeviceIdProvider
import net.agora.app.data.OnboardingStateStore
import net.agora.app.data.RecentVerificationStore
import net.agora.app.data.ThemePreferenceStore
import net.agora.app.data.initializeSupabase
import net.agora.app.media.MediaHttpClient
import net.agora.app.media.TempFileSweeper

class AgoraApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 🌟 Initialize Supabase at Application process startup before any Activity or Service runs.
        //    Kept synchronous on purpose: PushNotificationService can run in a process that never
        //    touches MainActivity, and `supabaseClient` is a lateinit global. Client creation is
        //    cheap (no network I/O); the expensive deferred work (FCM token sync, topic
        //    subscription, feed fetch) already runs lazily after auth resolves.
        initializeSupabase(this)

        // Per-install device identity for the single-device login policy (one prefs read).
        DeviceIdProvider.init(this)

        // Remembers which account was verified on this device, so a cold start
        // before onboarding finishes resumes it instead of wiping it.
        RecentVerificationStore.init(this)

        // Remembers which account FINISHED onboarding on this device. This is the
        // tie-breaker when the profiles read is inconclusive on a cold start —
        // without it, a completed account could be re-derived as "new" from a
        // single empty query and sent back to Create Your Profile.
        OnboardingStateStore.init(this)

        // 🌟 Load the theme choice before the first Activity is created so the
        //    splash/window background and the first Compose frame agree.
        ThemePreferenceStore.init(this)

        // 🌟 Reclaim cache accidentally left behind by a previous run that was
        //    killed mid-upload (a 720p transcode can leave tens of MB behind,
        //    invisible in cacheDir). Off the main thread; never blocks startup.
        TempFileSweeper.sweepAsync(this)
    }

    override fun newImageLoader(): ImageLoader {
        // Lazily created by Coil on first image request — never on the startup critical path.
        return ImageLoader.Builder(this)
            // Thumbnails are decoded from the same Catbox URLs ExoPlayer plays, so they
            // share the media HTTP stack: browser UA (Catbox rejects both Media3's
            // `ExoPlayerLib/<v>` and Coil's `okhttp/<v>`), timeouts and retry behaviour.
            .okHttpClient(MediaHttpClient.okHttpClient)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            // ✦ HARD DISK CEILING — 25 MB.
            //   Cloudinary URLs are requested as ~30 KB thumbnails, so this holds
            //   hundreds of feed images. Together with VideoCache's 35 MB the
            //   app can never exceed ~60 MB of media on disk, on any device.
            //   An explicit cache also stops Coil's default (2% of free space,
            //   up to 250 MB) from growing on a large-storage phone.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(25L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}

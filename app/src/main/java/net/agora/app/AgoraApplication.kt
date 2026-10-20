package net.agora.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import net.agora.app.data.DeviceIdProvider
import net.agora.app.data.RecentVerificationStore
import net.agora.app.data.ThemePreferenceStore
import net.agora.app.data.initializeSupabase
import net.agora.app.media.MediaHttpClient

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

        // 🌟 Load the theme choice before the first Activity is created so the
        //    splash/window background and the first Compose frame agree.
        ThemePreferenceStore.init(this)
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
            .crossfade(true)
            .build()
    }
}

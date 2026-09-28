package com.example.agora

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.example.agora.data.initializeSupabase

class AgoraApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 🌟 Initialize Supabase at Application process startup before any Activity or Service runs
        initializeSupabase(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}

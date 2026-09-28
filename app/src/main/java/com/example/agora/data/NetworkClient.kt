package com.example.agora.data

import android.content.Context
import com.example.agora.BuildConfig
import com.russhwolf.settings.SharedPreferencesSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json

@Volatile
lateinit var supabaseClient: SupabaseClient
    private set

@Synchronized
fun initializeSupabase(context: Context) {
    if (::supabaseClient.isInitialized) return

    val sharedPreferences = context.applicationContext.getSharedPreferences("agora_session", Context.MODE_PRIVATE)
    val settings = SharedPreferencesSettings(sharedPreferences)

    val customJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        isLenient = true
    }

    supabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        httpEngine = OkHttp.create()
        defaultSerializer = KotlinXSerializer(customJson)

        install(Postgrest)
        install(Auth) {
            sessionManager = SettingsSessionManager(settings)
        }
        install(Realtime)
        install(Storage)
    }
}

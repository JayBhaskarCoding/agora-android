package com.example.agora.data

import android.content.Context
import java.util.UUID

/**
 * Stable per-install device identity used by the single-device login policy.
 *
 * A UUID is generated on first launch and persisted in SharedPreferences, so the
 * id survives process death and re-logins. Call [init] once from
 * [com.example.agora.AgoraApplication.onCreate].
 */
object DeviceIdProvider {

    private const val PREFS_NAME = "agora_device"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var cachedDeviceId: String? = null

    /** Must be called before [deviceId] is read (Application.onCreate). */
    fun init(context: Context) {
        if (cachedDeviceId != null) return
        synchronized(this) {
            if (cachedDeviceId != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) {
                cachedDeviceId = existing
            } else {
                val generated = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
                cachedDeviceId = generated
            }
        }
    }

    /** The persistent, unique id of this install. Throws if [init] was never called. */
    val deviceId: String
        get() = cachedDeviceId
            ?: error("DeviceIdProvider.init(context) must be called from Application.onCreate")
}

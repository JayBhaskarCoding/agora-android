package net.agora.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.agora.app.BuildConfig
import net.agora.app.media.CloudinaryUrlHelper
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object CloudinaryClient {

    private const val TAG = "CloudinaryUpload"
    private const val CLOUD_NAME = CloudinaryUrlHelper.CLOUD_NAME
    private const val UPLOAD_PRESET = "xybn3qbo"
    private const val CLOUDINARY_UPLOAD_URL = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/auto/upload"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        mimeType: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null

            uploadBytes(bytes, mimeType)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Cloudinary upload error: ${e.localizedMessage}", e)
            null
        }
    }

    suspend fun uploadBytes(
        bytes: ByteArray,
        mimeType: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (bytes.isEmpty()) return@withContext null

            val isVideo = mimeType.startsWith("video")
            val extension = if (isVideo) "mp4" else "webp"
            val fileName = "upload_${UUID.randomUUID()}.$extension"

            val mediaType = mimeType.toMediaTypeOrNull()
            val fileRequestBody = bytes.toRequestBody(mediaType)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", UPLOAD_PRESET)
                .addFormDataPart("file", fileName, fileRequestBody)
                .build()

            val request = Request.Builder()
                .url(CLOUDINARY_UPLOAD_URL)
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()?.trim()
                if (!responseBody.isNullOrBlank()) {
                    val json = JSONObject(responseBody)
                    val secureUrl = json.optString("secure_url", null)
                    if (!secureUrl.isNullOrBlank()) {
                        Log.d(TAG, "Successfully uploaded to Cloudinary: $secureUrl")
                        secureUrl
                    } else {
                        Log.e(TAG, "Cloudinary response missing secure_url: $responseBody")
                        null
                    }
                } else {
                    Log.e(TAG, "Cloudinary upload response body is blank")
                    null
                }
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "Cloudinary upload HTTP failed with code ${response.code}: $errorBody")
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Cloudinary upload bytes error: ${e.localizedMessage}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deletion (Cloudinary REST "destroy")
    // ─────────────────────────────────────────────────────────────────────────

    private const val TAG_DELETE = "CloudinaryDelete"

    /**
     * Destroys the Cloudinary asset behind [mediaUrl].
     *
     * `POST https://api.cloudinary.com/v1_1/<cloud>/<resource_type>/destroy`
     * with `public_id`, `timestamp`, `invalidate=true`, `api_key` and a SHA-1
     * `signature` over the same parameters.
     *
     * [invalidate] tells Cloudinary to purge the CDN copy as well — without it
     * the asset is gone from storage but keeps being served from edge caches,
     * which looks exactly like "deleting did nothing".
     *
     * ⚠️ SECURITY — read `docs/engineering-notes.md` §13. A mobile app cannot
     * keep a secret: the API secret compiled into `BuildConfig` is extractable
     * from the APK, and it grants delete rights on the whole Cloudinary account.
     * This is implemented as specified, but the production-grade fix is to proxy
     * the call through a Supabase Edge Function that holds the secret. Until
     * then, restrict the API key in the Cloudinary console.
     *
     * Returns true only when Cloudinary answers `{"result":"ok"}`. Every failure
     * mode is logged with the exact reason and returns false — callers must treat
     * media cleanup as best-effort and never block the user-facing action on it.
     */
    suspend fun deleteFromCloudinary(mediaUrl: String): Boolean = withContext(Dispatchers.IO) {
        val publicId = CloudinaryUrlHelper.publicId(mediaUrl)
        val resourceType = CloudinaryUrlHelper.resourceType(mediaUrl)
        if (publicId == null || resourceType == null) {
            Log.w(TAG_DELETE, "not a Cloudinary upload URL, skipping: $mediaUrl")
            return@withContext false
        }

        val apiKey = BuildConfig.CLOUDINARY_API_KEY
        val apiSecret = BuildConfig.CLOUDINARY_API_SECRET
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            Log.w(
                TAG_DELETE,
                "CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET are not set — " +
                    "cannot destroy $publicId. Add them to local.properties " +
                    "(see local.properties.example). The asset is now ORPHANED " +
                    "in Cloudinary and must be cleaned up manually."
            )
            return@withContext false
        }

        try {
            val timestamp = (System.currentTimeMillis() / 1000L).toString()
            val params = linkedMapOf(
                "invalidate" to "true",
                "public_id" to publicId,
                "timestamp" to timestamp
            )
            val signature = CloudinaryUrlHelper.signature(params, apiSecret)

            val body = FormBody.Builder()
                .add("public_id", publicId)
                .add("timestamp", timestamp)
                .add("invalidate", "true")
                .add("api_key", apiKey)
                .add("signature", signature)
                .build()

            val request = Request.Builder()
                .url("https://api.cloudinary.com/v1_1/$CLOUD_NAME/$resourceType/destroy")
                .post(body)
                .build()

            // `use` is the value of the whole withContext block, so every
            // branch below must be an expression returning Boolean.
            okHttpClient.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> {
                        Log.e(TAG_DELETE, "destroy HTTP ${response.code} for $publicId: $payload")
                        false
                    }
                    else -> {
                        val result = JSONObject(payload).optString("result")
                        if (result == "ok") {
                            Log.d(TAG_DELETE, "destroyed $resourceType/$publicId")
                            true
                        } else {
                            // "not found" is the desired end state already true
                            // (double delete, or an asset removed by hand).
                            Log.w(TAG_DELETE, "destroy returned result=$result for $publicId: $payload")
                            result == "not found"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_DELETE, "destroy failed for $publicId: ${e.localizedMessage}", e)
            false
        }
    }
}

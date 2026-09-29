package com.example.agora.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private const val CLOUD_NAME = "h9cocag7"
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
}

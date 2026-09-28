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
import java.util.UUID
import java.util.concurrent.TimeUnit

object CatboxClient {

    private const val CATBOX_API_URL = "https://catbox.moe/user/api.php"

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
            Log.e("CatboxUpload", "Catbox upload error: ${e.localizedMessage}", e)
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
                .addFormDataPart("reqtype", "fileupload")
                .addFormDataPart("fileToUpload", fileName, fileRequestBody)
                .build()

            val request = Request.Builder()
                .url(CATBOX_API_URL)
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()?.trim()
                if (!responseBody.isNullOrBlank() && responseBody.startsWith("http")) {
                    Log.d("CatboxUpload", "Successfully uploaded to Catbox: $responseBody")
                    responseBody
                } else {
                    Log.e("CatboxUpload", "Catbox upload failed with response body: $responseBody")
                    null
                }
            } else {
                Log.e("CatboxUpload", "Catbox upload HTTP failed with code: ${response.code}")
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("CatboxUpload", "Catbox upload bytes error: ${e.localizedMessage}", e)
            null
        }
    }
}

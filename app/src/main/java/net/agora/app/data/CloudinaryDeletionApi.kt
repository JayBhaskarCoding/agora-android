package net.agora.app.data

import android.util.Log
import io.github.jan.supabase.functions.functions
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.agora.app.media.CloudinaryUrlHelper

/**
 * Client half of the `delete-cloudinary-asset` Edge Function.
 *
 * ── WHY A SERVER CALL ───────────────────────────────────────────────────────
 * Destroying a Cloudinary asset needs the account's API **secret**. Signing on
 * the device meant shipping that secret in `BuildConfig`, where anyone holding
 * the APK can read it and wipe the whole media library. The secret now lives
 * only in Supabase function secrets; the app just says "this post is gone",
 * authenticated by the user's own JWT.
 *
 * ── AUTHORISATION HAPPENS SERVER-SIDE ───────────────────────────────────────
 * The function re-derives the allowed `public_id`s from `posts.media_urls` and
 * checks `posts.user_id = auth.uid()`. The list sent here is a convenience, not
 * a permission: anything outside the post is rejected with 403.
 *
 * ⚠️ Call this **before** deleting the post row. Ownership is verified against
 * that row; once it is gone the function cannot tell whose media it is and
 * answers 409 rather than trusting the client.
 */
object CloudinaryDeletionApi {

    private const val TAG = "CloudinaryDelete"
    private const val FUNCTION = "delete-cloudinary-asset"

    /** Mirrors `MAX_ASSETS` in the Edge Function. */
    private const val MAX_ASSETS = 20

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class AssetRef(
        @SerialName("public_id") val publicId: String,
        @SerialName("resource_type") val resourceType: String
    )

    @Serializable
    private data class Request(
        @SerialName("post_id") val postId: String,
        val assets: List<AssetRef>
    )

    @Serializable
    private data class DeletedAsset(
        @SerialName("public_id") val publicId: String = "",
        @SerialName("resource_type") val resourceType: String = "",
        val result: String = ""
    )

    @Serializable
    private data class Response(
        val destroyed: List<DeletedAsset> = emptyList(),
        val failed: List<DeletedAsset> = emptyList(),
        val rejected: List<String> = emptyList(),
        val error: String? = null,
        val hint: String? = null
    )

    /** Outcome of one cleanup call — logged, never surfaced as a user-facing error. */
    data class Result(
        val destroyed: Int,
        val failed: Int,
        val httpStatus: Int
    ) {
        val ok: Boolean get() = failed == 0
    }

    /**
     * Extracts the `public_id` / `resource_type` pairs the function needs from a
     * post's media URLs. Non-Cloudinary URLs (legacy Catbox links, Supabase
     * Storage) are skipped: the function only destroys what it is allowed to.
     */
    fun assetsFor(mediaUrls: List<String>): List<AssetRef> = mediaUrls.mapNotNull { url ->
        val id = CloudinaryUrlHelper.publicId(url) ?: return@mapNotNull null
        val type = CloudinaryUrlHelper.resourceType(url) ?: return@mapNotNull null
        AssetRef(publicId = id, resourceType = type)
    }

    /**
     * Asks the server to destroy [assets] for [postId].
     *
     * Never throws: deletion of the database row is the user's intent and must
     * not be blocked by a media-cleanup failure. Every outcome is logged under
     * `CloudinaryDelete` so orphans stay diagnosable.
     */
    suspend fun deleteAssets(postId: String, assets: List<AssetRef>): Result? {
        return try {
            // Explicit Content-Type: the typed `invoke(function, body)` overload
            // serializes but does not set it, and an Edge Function gateway on a
            // body-less content type can reject the call before it reaches code.
            // A post with more assets than the server accepts is not an error:
            // an empty `assets` array tells the function to derive the whole set
            // from the post row itself, which is bounded by the row, not by us.
            val payload = if (assets.size > MAX_ASSETS) {
                Log.w(TAG, "post=$postId has ${assets.size} assets; asking server to derive the set")
                Request(postId = postId, assets = emptyList())
            } else {
                Request(postId = postId, assets = assets)
            }

            val response = supabaseClient.functions.invoke(FUNCTION) {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(payload))
            }
            val status = response.status.value
            val text = response.bodyAsText()
            val parsed = runCatching { json.decodeFromString<Response>(text) }.getOrNull()

            when {
                status in 200..299 -> {
                    val destroyed = parsed?.destroyed?.size ?: 0
                    val failed = parsed?.failed?.size ?: 0
                    Log.d(TAG, "post=$postId destroyed=$destroyed failed=$failed")
                    Result(destroyed = destroyed, failed = failed, httpStatus = status)
                }

                else -> {
                    Log.w(
                        TAG,
                        "post=$postId http=$status error=${parsed?.error} " +
                            "hint=${parsed?.hint} body=${text.take(300)}"
                    )
                    Result(destroyed = 0, failed = assets.size, httpStatus = status)
                }
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: the caller's scope is going away.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "post=$postId invoke failed: ${e.localizedMessage}", e)
            null
        }
    }
}

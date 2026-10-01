package com.example.agora.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.agora.data.CloudinaryClient
import com.example.agora.data.supabaseClient
import com.example.agora.model.Comment
import com.example.agora.model.CommentInsertRequest
import com.example.agora.model.NotificationInsert
import com.example.agora.model.Post
import com.example.agora.model.PostLike
import com.example.agora.model.Profile
import com.example.agora.model.ReactorDetails
import com.example.agora.utils.handleAppError
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

@Immutable
data class EditMediaItem(
    val id: String = UUID.randomUUID().toString(),
    val remoteUrl: String? = null,
    val localUri: Uri? = null,
    val isVideo: Boolean = false
) {
    val displayModel: Any? get() = localUri ?: remoteUrl
}

@Serializable
data class PostInsertRequest(
    @SerialName("user_id") val userId: String,
    val content: String,
    @SerialName("media_urls") val mediaUrls: List<String> = emptyList(),
    @SerialName("image_urls") val imageUrls: List<String> = emptyList()
)

@Serializable
data class PostUpdateRequest(
    val content: String,
    @SerialName("media_urls") val mediaUrls: List<String> = emptyList(),
    @SerialName("image_urls") val imageUrls: List<String> = emptyList()
)

@Immutable
sealed class UploadState {
    @Immutable
    data object Idle : UploadState()

    @Immutable
    data class Uploading(val progress: Float, val message: String) : UploadState()

    @Immutable
    data class Success(val message: String) : UploadState()

    @Immutable
    data class Error(val message: String) : UploadState()
}

class FeedViewModel : ViewModel() {

    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _feedError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _feedError.asStateFlow()

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    val currentUserId: String?
        get() = supabaseClient.auth.currentUserOrNull()?.id

    var activeCommentPostId: String? = null

    private val _reactorsList = MutableStateFlow<List<ReactorDetails>>(emptyList())
    val reactorsList: StateFlow<List<ReactorDetails>> = _reactorsList.asStateFlow()

    /** True only while a reactors fetch is in flight — the sheet shows its
     *  spinner exclusively on this flag, so an empty result can render the
     *  "no reactions yet" state instead of spinning forever. */
    private val _reactorsLoading = MutableStateFlow(false)
    val reactorsLoading: StateFlow<Boolean> = _reactorsLoading.asStateFlow()

    /** Non-null when the reactors fetch FAILED — the sheet must show this
     *  (with a retry) instead of masquerading a failure as "no reactions". */
    private val _reactorsError = MutableStateFlow<String?>(null)
    val reactorsError: StateFlow<String?> = _reactorsError.asStateFlow()

    /** 🌟 Event-style UI messages (post-action failures). A SharedFlow with no
     *  replay: undelivered events are dropped instead of replaying stale onto
     *  the next screen that collects — no clearError dance needed. */
    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    var selectedPostIdForReactors by mutableStateOf<String?>(null)

    fun loadReactors(postId: String) {
        selectedPostIdForReactors = postId
        viewModelScope.launch {
            _reactorsList.value = emptyList()
            _reactorsError.value = null
            _reactorsLoading.value = true
            try {
                val fetchedReactors = withContext(Dispatchers.IO) {
                    // Newest first, capped at 50 rows to keep the sheet light.
                    // The join carries profiles.status so closed (ghost)
                    // accounts can be masked as "Removed User" here too.
                    val rawList = try {
                        supabaseClient.from("post_likes").select(
                            columns = Columns.raw("reaction_type, user_id, profiles!post_likes_user_id_fkey(id, first_name, last_name, handle, avatar_url, status)")
                        ) {
                            filter { eq("post_id", postId) }
                            order("created_at", Order.DESCENDING)
                            limit(50)
                        }.decodeList<JsonObject>()
                    } catch (schemaError: PostgrestRestException) {
                        // Graceful degradation: on a live DB that predates the
                        // created_at (20990101000009) / status (20990101000006)
                        // migrations the full query 400s. Retry the plain shape —
                        // unordered, no ghost masking — so real reactions still
                        // render instead of a false "no reactions yet".
                        Log.w(
                            "FeedViewModel",
                            "loadReactors: full query rejected (${schemaError.message}); falling back to plain shape"
                        )
                        supabaseClient.from("post_likes").select(
                            columns = Columns.raw("reaction_type, user_id, profiles!post_likes_user_id_fkey(id, first_name, last_name, handle, avatar_url)")
                        ) {
                            filter { eq("post_id", postId) }
                            limit(50)
                        }.decodeList<JsonObject>()
                    }

                    rawList.map { json ->
                        // contentOrNull (not content): SQL NULLs arrive as JsonNull
                        // whose .content is the literal string "null".
                        val reactionType = json["reaction_type"]?.jsonPrimitive?.contentOrNull ?: "❤️"
                        val userId = json["user_id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val profileObj = json["profiles"] as? JsonObject
                        val isClosed = profileObj?.get("status")?.jsonPrimitive?.contentOrNull == "closed"
                        val firstName = profileObj?.get("first_name")?.jsonPrimitive?.contentOrNull ?: "User"
                        val lastName = profileObj?.get("last_name")?.jsonPrimitive?.contentOrNull ?: ""
                        val handle = profileObj?.get("handle")?.jsonPrimitive?.contentOrNull ?: "user"
                        val avatarUrl = profileObj?.get("avatar_url")?.jsonPrimitive?.contentOrNull

                        val fullName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "User" }

                        ReactorDetails(
                            userId = userId,
                            displayName = if (isClosed) "Removed User" else fullName,
                            username = "@$handle",
                            avatarUrl = if (isClosed) null else avatarUrl,
                            reactionType = reactionType
                        )
                    }
                }
                _reactorsList.value = fetchedReactors
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("FeedViewModel", "loadReactors failed for post $postId: ${e.localizedMessage}", e)
                _reactorsList.value = emptyList()
                _reactorsError.value = "Couldn't load reactions. Please try again."
            } finally {
                _reactorsLoading.value = false
            }
        }
    }

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    // 🌟 Task 1: explicit composer lock — true from the Post tap until Supabase
    // confirms (released just before onSuccess) or the attempt fails (finally).
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    // 🌟 Same lock for the EDIT flow — true from the Save tap until the UPDATE
    // (plus any new media uploads) confirms or fails.
    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Profile>>(emptyList())
    val searchResults: StateFlow<List<Profile>> = _searchResults.asStateFlow()

    private var autoRefreshJob: Job? = null
    private var searchJob: Job? = null
    private var realtimeChannel: RealtimeChannel? = null

    init {
        // 🌟 Executed ONCE on ViewModel creation to prevent recomposition loops
        fetchPostsFromCloud()
        setupRealtimeListener()
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(30000.milliseconds) // 30s auto-refresh interval
                if (_feedError.value == null) {
                    fetchPostsFromCloud()
                    activeCommentPostId?.let { postId ->
                        fetchCommentsForPost(postId)
                    }
                }
            }
        }
    }

    private fun setupRealtimeListener() {
        // 🌟 TEMPORARILY DISABLED REALTIME: Bypasses websocket connections during billing/server restrictions
        val enableRealtime = false
        if (!enableRealtime) return

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    supabaseClient.realtime.connect()
                }
                val channel = supabaseClient.channel("public-db-changes")
                realtimeChannel = channel

                val postsFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "posts" }
                val likesFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "post_likes" }
                val commentsFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") { table = "comments" }

                channel.subscribe()

                launch { postsFlow.collect { fetchPostsFromCloud() } }
                launch { likesFlow.collect { fetchPostsFromCloud() } }
                launch {
                    commentsFlow.collect {
                        fetchPostsFromCloud()
                        activeCommentPostId?.let { fetchCommentsForPost(it) }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
            }
        }
    }

    fun searchusers(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            try {
                delay(300) // Debounce 300ms
                val results = withContext(Dispatchers.IO) {
                    supabaseClient.from("profiles").select {
                        filter {
                            or {
                                ilike("first_name", "%$query%")
                                ilike("last_name", "%$query%")
                                ilike("handle", "%$query%")
                            }
                        }
                    }.decodeList<Profile>()
                }

                val sortedResults = results.sortedByDescending { profile ->
                    val q = query.lowercase()
                    val handle = profile.handle.lowercase()
                    val firstName = (profile.firstName ?: "").lowercase()
                    val lastName = (profile.lastName ?: "").lowercase()

                    when {
                        handle.startsWith(q) -> 4
                        firstName.startsWith(q) -> 3
                        lastName.startsWith(q) -> 2
                        handle.contains(q) || firstName.contains(q) -> 1
                        else -> 0
                    }
                }

                _searchResults.value = sortedResults
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _searchResults.value = emptyList()
            }
        }
    }

    fun fetchPostsFromCloud() {
        viewModelScope.launch {
            // 🌟 Only show the full-screen loading state on the FIRST load; background
            //    auto-refreshes must not flip global flags (recomposition churn every 30s).
            val isFirstLoad = _posts.value.isEmpty()
            try {
                if (isFirstLoad) {
                    _isLoading.value = true
                }
                _feedError.value = null

                val user = supabaseClient.auth.currentUserOrNull()

                val fetchedPosts = withContext(Dispatchers.IO) {
                    supabaseClient.from("posts").select(
                        columns = Columns.raw("*, profiles!fk_posts_user_id(*)")
                    ) {
                        order("created_at", order = Order.DESCENDING)
                    }.decodeList<Post>()
                }

                val merged = if (user != null) {
                    val myLikes = try {
                        withContext(Dispatchers.IO) {
                            supabaseClient.from("post_likes")
                                .select { filter { eq("user_id", user.id) } }.decodeList<PostLike>()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }

                    fetchedPosts.map { post ->
                        val myLike = myLikes.find { it.postId == post.id }
                        post.copy(
                            isLikedByMe = (myLike != null),
                            myReaction = myLike?.reactionType
                        )
                    }
                } else {
                    fetchedPosts
                }

                // 🌟 Skip the emission when nothing changed — avoids recomposing the
                //    whole feed on every periodic refresh.
                if (merged != _posts.value) {
                    _posts.value = merged
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                _feedError.value = "Failed to load posts. Pull to refresh."
            } finally {
                if (isFirstLoad) {
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Mirrors a post edited (e.g. liked) on the single-post screen back into the
     * feed list without any network round-trip, so both screens stay consistent.
     */
    fun syncPostState(post: Post) {
        _posts.update { list ->
            if (list.none { it.id == post.id }) list
            else list.map { if (it.id == post.id) post else it }
        }
    }

    // 🌟 DETERMINISTIC LIKE & REACTION STATUS UPDATER (SUPABASE post_likes TABLE)
    fun setLikeStatus(postId: String, shouldBeLiked: Boolean, emoji: String = "❤️") {
        viewModelScope.launch {
            val user = supabaseClient.auth.currentUserOrNull() ?: return@launch
            val currentUserId = user.id

            val currentList = _posts.value
            val post = currentList.find { it.id == postId } ?: return@launch
            val currentlyLiked = post.isLikedByMe
            val currentReaction = post.myReaction

            // 🌟 Guard: If requested like state AND reaction match current state, do nothing
            if (currentlyLiked == shouldBeLiked && currentReaction == emoji) {
                return@launch
            }

            // Optimistic UI Update
            _posts.update { list ->
                list.map {
                    if (it.id == postId) {
                        it.copy(
                            isLikedByMe = shouldBeLiked,
                            myReaction = if (shouldBeLiked) emoji else null,
                            likes = if (shouldBeLiked && !currentlyLiked) it.likes + 1 else if (!shouldBeLiked && currentlyLiked) (it.likes - 1).coerceAtLeast(0) else it.likes
                        )
                    } else it
                }
            }

            try {
                withContext(Dispatchers.IO) {
                    if (!shouldBeLiked) {
                        // DELETE from post_likes table
                        supabaseClient.from("post_likes").delete {
                            filter {
                                eq("user_id", currentUserId)
                                eq("post_id", postId)
                            }
                        }
                    } else if (currentlyLiked) {
                        // 🌟 UPDATE reaction_type in post_likes table if post was already liked
                        supabaseClient.from("post_likes").update(
                            mapOf("reaction_type" to emoji)
                        ) {
                            filter {
                                eq("user_id", currentUserId)
                                eq("post_id", postId)
                            }
                        }
                    } else {
                        // 🌟 INSERT new PostLike record into post_likes table
                        val newLike = PostLike(userId = currentUserId, postId = postId, reactionType = emoji)
                        supabaseClient.from("post_likes").insert(newLike)

                        if (currentUserId != post.userId) {
                            try {
                                val senderName = getCurrentUserName(currentUserId)
                                val preview = truncateContent(post.content)
                                val notification = NotificationInsert(
                                    recipientId = post.userId,
                                    title = "New Reaction",
                                    body = "$senderName reacted $emoji to your post: \"$preview\"",
                                    data = mapOf(
                                        "sender_id" to currentUserId,
                                        "post_id" to post.id
                                    )
                                )
                                supabaseClient.from("notifications").insert(notification)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                // Revert optimistic update on error
                _posts.update { list ->
                    list.map {
                        if (it.id == postId) {
                            it.copy(
                                isLikedByMe = currentlyLiked,
                                myReaction = currentReaction,
                                likes = if (currentlyLiked) (it.likes + 1) else (it.likes - 1).coerceAtLeast(0)
                            )
                        } else it
                    }
                }
            }
        }
    }

    fun toggleLike(postId: String) {
        viewModelScope.launch {
            val user = supabaseClient.auth.currentUserOrNull() ?: return@launch
            val currentUserId = user.id

            val currentList = _posts.value
            val post = currentList.find { it.id == postId } ?: return@launch
            val wasLiked = post.isLikedByMe

            // Optimistic UI Update
            _posts.update { list ->
                list.map {
                    if (it.id == postId) {
                        it.copy(
                            isLikedByMe = !wasLiked,
                            likes = if (wasLiked) (it.likes - 1).coerceAtLeast(0) else it.likes + 1
                        )
                    } else it
                }
            }

            try {
                withContext(Dispatchers.IO) {
                    if (wasLiked) {
                        supabaseClient.from("post_likes").delete {
                            filter {
                                eq("user_id", currentUserId)
                                eq("post_id", postId)
                            }
                        }
                    } else {
                        val newLike = PostLike(userId = currentUserId, postId = postId)
                        supabaseClient.from("post_likes").insert(newLike)

                        // 🌟 Send push notification if not liking own post
                        if (currentUserId != post.userId) {
                            try {
                                val senderName = getCurrentUserName(currentUserId)
                                val preview = truncateContent(post.content)
                                val notification = NotificationInsert(
                                    recipientId = post.userId,
                                    title = "New Like",
                                    body = "$senderName liked your post: \"$preview\"",
                                    data = mapOf(
                                        "sender_id" to currentUserId,
                                        "post_id" to post.id
                                    )
                                )
                                supabaseClient.from("notifications").insert(notification)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                // Revert optimistic update on error
                _posts.update { list ->
                    list.map {
                        if (it.id == postId) {
                            it.copy(
                                isLikedByMe = wasLiked,
                                likes = if (wasLiked) it.likes + 1 else (it.likes - 1).coerceAtLeast(0)
                            )
                        } else it
                    }
                }
            }
        }
    }

    // 🌟 TASK 1: CLIENT-SIDE IMAGE COMPRESSION (WEBP / 80% QUALITY)
    suspend fun compressImage(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } ?: return@withContext context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

            val outputStream = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, 80, outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        }
    }

    // 🌟 TASK 2: CLIENT-SIDE VIDEO COMPRESSION (MEDIA3 TRANSFORMER / 720P / H.264)
    @OptIn(UnstableApi::class)
    suspend fun compressVideo(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(context.cacheDir, "compressed_upload_${UUID.randomUUID()}.mp4")
            val outputUri = Uri.fromFile(outputFile)

            val resultUri = suspendCancellableCoroutine<Uri?> { continuation ->
                val mediaItem = MediaItem.fromUri(uri)
                val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(720))))
                    .build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(outputUri)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                    .build()

                try {
                    transformer.start(editedMediaItem, outputFile.absolutePath)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    try { transformer.cancel() } catch (_: Exception) {}
                }
            }

            if (resultUri != null && outputFile.exists()) {
                val bytes = outputFile.readBytes()
                outputFile.delete() // 🌟 Delete temporary local file to prevent storage leaks
                bytes
            } else {
                if (outputFile.exists()) outputFile.delete()
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        }
    }

    suspend fun uploadPostImage(context: Context, imageUri: Uri): String? {
        val bytes = compressImage(context, imageUri)
        return if (bytes.isNotEmpty()) {
            CloudinaryClient.uploadBytes(bytes, "image/webp")
        } else null
    }

    suspend fun uploadPostVideo(context: Context, videoUri: Uri): String? {
        val bytes = compressVideo(context, videoUri)
        return if (bytes.isNotEmpty()) {
            CloudinaryClient.uploadBytes(bytes, "video/mp4")
        } else null
    }

    // 🌟 TASK 3: COMPRESSED MULTI-MEDIA POST CREATION PIPELINE
    fun createPost(
        context: Context,
        content: String,
        mediaUris: List<Uri> = emptyList(),
        onSuccess: () -> Unit = {}
    ) {
        // 🌟 Task 3: a duplicate tap while an upload is already in flight must
        // never start a second pipeline (double posts / double DB rows).
        if (_isUploading.value) return
        viewModelScope.launch {
            try {
                _isUploading.value = true
                _uploadState.value = UploadState.Uploading(0.1f, "Preparing post...")
                val appContext = context.applicationContext

                val uploadedUrls = if (mediaUris.isNotEmpty()) {
                    _uploadState.value = UploadState.Uploading(0.3f, "Compressing & uploading media...")
                    withContext(Dispatchers.IO) {
                        mediaUris.map { uri ->
                            async {
                                val isVideo = appContext.contentResolver.getType(uri)?.startsWith("video") == true || uri.toString().contains(".mp4")
                                
                                // 🌟 Pass through client-side compression before uploading bytes
                                val bytes = if (isVideo) {
                                    compressVideo(appContext, uri)
                                } else {
                                    compressImage(appContext, uri)
                                }

                                if (bytes.isNotEmpty()) {
                                    val mimeType = if (isVideo) "video/mp4" else "image/webp"
                                    CloudinaryClient.uploadBytes(bytes, mimeType)
                                } else null
                            }
                        }.awaitAll().filterNotNull()
                    }
                } else {
                    emptyList()
                }

                // A failed upload used to be dropped silently by `filterNotNull()`, so the
                // post was still inserted — with empty media_urls. On the author's device the
                // media looked fine (it was still a local Uri / in Coil's cache); on any other
                // device the post rendered with no image and a black video, permanently. Refuse
                // to persist a post whose media did not land.
                if (mediaUris.isNotEmpty() && uploadedUrls.size < mediaUris.size) {
                    Log.e(
                        "FeedUpload",
                        "Aborting createPost: only ${uploadedUrls.size}/${mediaUris.size} media " +
                            "items uploaded (media host unreachable?) — not saving a post with " +
                            "lost media"
                    )
                    _uploadState.value = UploadState.Error(
                        "Couldn't upload ${mediaUris.size - uploadedUrls.size} of " +
                            "${mediaUris.size} media item(s). Nothing was posted — check your " +
                            "connection and try again."
                    )
                    delay(3500.milliseconds)
                    _uploadState.value = UploadState.Idle
                    return@launch
                }

                _uploadState.value = UploadState.Uploading(0.85f, "Saving post to database...")
                val userId = currentUserId
                if (userId == null) {
                    // A silent return here used to strand the upload banner forever.
                    _uploadState.value =
                        UploadState.Error("Your session expired — please sign in again to post.")
                    delay(3000.milliseconds)
                    _uploadState.value = UploadState.Idle
                    return@launch
                }

                val insertedPosts = withContext(Dispatchers.IO) {
                    val newPost = PostInsertRequest(
                        userId = userId,
                        content = content,
                        mediaUrls = uploadedUrls,
                        imageUrls = uploadedUrls
                    )
                    supabaseClient.from("posts").insert(newPost) {
                        select(columns = Columns.raw("*, profiles!fk_posts_user_id(*)"))
                    }.decodeList<Post>()
                }

                val newlyCreatedPost = insertedPosts.firstOrNull()

                if (newlyCreatedPost != null) {
                    _posts.update { currentList ->
                        listOf(newlyCreatedPost) + currentList.filter { it.id != newlyCreatedPost.id }
                    }
                }

                _uploadState.value = UploadState.Uploading(1.0f, "Refreshing feed...")
                fetchPostsFromCloud()

                _uploadState.value = UploadState.Success("Posted successfully!")
                // Upload + insert confirmed — release the composer lock BEFORE
                // onSuccess so the dialog's guarded clear-and-dismiss actually runs.
                _isUploading.value = false
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
                delay(2500.milliseconds)
                _uploadState.value = UploadState.Idle
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("FeedViewModel", "createPost failed: ${e.localizedMessage}", e)
                _uploadState.value = UploadState.Error(handleAppError(e))
                delay(3000.milliseconds)
                _uploadState.value = UploadState.Idle
            } finally {
                // Failure path lands here too: composer unlocks, draft stays intact.
                _isUploading.value = false
            }
        }
    }

    fun addPostWithMedia(
        context: Context,
        content: String,
        imageUris: List<Uri> = emptyList(),
        videoUri: Uri? = null,
        onSuccess: () -> Unit = {}
    ) {
        val allUris = imageUris + listOfNotNull(videoUri)
        createPost(context, content, allUris, onSuccess)
    }

    fun addPost(
        context: Context,
        content: String,
        imageUris: List<Uri> = emptyList(),
        onSuccess: () -> Unit = {}
    ) {
        createPost(context, content, imageUris, onSuccess)
    }

    fun deletePost(postId: String) {
        viewModelScope.launch {
            try {
                val userId = currentUserId ?: return@launch

                withContext(Dispatchers.IO) {
                    supabaseClient.from("posts").delete {
                        filter {
                            eq("id", postId)
                            eq("user_id", userId)
                        }
                    }
                }

                fetchPostsFromCloud()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("FeedViewModel", "deletePost failed: ${e.localizedMessage}", e)
                _uiMessages.tryEmit(handleAppError(e))
            }
        }
    }

    // 🌟 UNIFIED SAVE PIPELINE FOR EDIT POST SCREEN
    fun savePostChanges(
        context: Context,
        postId: String,
        updatedCaption: String,
        currentItems: List<EditMediaItem>,
        onSuccess: () -> Unit = {}
    ) {
        // 🌟 Task 3: duplicate-tap guard — one UPDATE pipeline at a time.
        if (_isUpdating.value) return
        viewModelScope.launch {
            try {
                _isUpdating.value = true
                _uploadState.value = UploadState.Uploading(0.1f, "Preparing post update...")
                val appContext = context.applicationContext

                val keptRemoteUrls = currentItems.mapNotNull { it.remoteUrl }
                val newLocalUris = currentItems.mapNotNull { it.localUri }

                val newUploadedUrls = if (newLocalUris.isNotEmpty()) {
                    _uploadState.value = UploadState.Uploading(0.3f, "Compressing & uploading new media...")
                    withContext(Dispatchers.IO) {
                        newLocalUris.map { uri ->
                            async {
                                val isVideo = appContext.contentResolver.getType(uri)?.startsWith("video") == true || uri.toString().contains(".mp4")
                                
                                val bytes = if (isVideo) {
                                    compressVideo(appContext, uri)
                                } else {
                                    compressImage(appContext, uri)
                                }

                                if (bytes.isNotEmpty()) {
                                    val mimeType = if (isVideo) "video/mp4" else "image/webp"
                                    CloudinaryClient.uploadBytes(bytes, mimeType)
                                } else null
                            }
                        }.awaitAll().filterNotNull()
                    }
                } else {
                    emptyList()
                }

                val finalMediaUrls = keptRemoteUrls + newUploadedUrls
                val userId = currentUserId
                if (userId == null) {
                    // A silent return here strands the upload banner forever.
                    _uploadState.value =
                        UploadState.Error("Your session expired — please sign in again to save changes.")
                    delay(3000)
                    _uploadState.value = UploadState.Idle
                    return@launch
                }

                // Same silent-loss guard as createPost: never save an edit that drops media.
                if (newLocalUris.isNotEmpty() && newUploadedUrls.size < newLocalUris.size) {
                    Log.e(
                        "FeedUpload",
                        "Aborting savePostChanges: only ${newUploadedUrls.size}/" +
                            "${newLocalUris.size} new media items uploaded"
                    )
                    _uploadState.value = UploadState.Error(
                        "Couldn't upload ${newLocalUris.size - newUploadedUrls.size} of " +
                            "${newLocalUris.size} media item(s). Your changes were not saved."
                    )
                    delay(3500.milliseconds)
                    _uploadState.value = UploadState.Idle
                    return@launch
                }

                _uploadState.value = UploadState.Uploading(0.85f, "Saving changes...")
                withContext(Dispatchers.IO) {
                    val updateRequest = PostUpdateRequest(
                        content = updatedCaption,
                        mediaUrls = finalMediaUrls,
                        imageUrls = finalMediaUrls
                    )
                    supabaseClient.from("posts").update(updateRequest) {
                        filter {
                            eq("id", postId)
                            eq("user_id", userId)
                        }
                    }
                }

                _uploadState.value = UploadState.Uploading(1.0f, "Refreshing feed...")
                fetchPostsFromCloud()

                _uploadState.value = UploadState.Success("Post updated successfully!")
                // UPDATE confirmed — release the editor lock BEFORE onSuccess so
                // the dialog's guarded dismissal actually runs.
                _isUpdating.value = false
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
                delay(2500)
                _uploadState.value = UploadState.Idle
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("FeedViewModel", "post update failed: ${e.localizedMessage}", e)
                _uploadState.value = UploadState.Error(handleAppError(e))
                delay(3000)
                _uploadState.value = UploadState.Idle
            } finally {
                // Failure path lands here too: editor unlocks, content stays intact.
                _isUpdating.value = false
            }
        }
    }

    fun reportPost(postId: String, reason: String) {
        viewModelScope.launch {
            try {
                val userId = currentUserId ?: return@launch

                val reportData = mapOf(
                    "post_id" to postId,
                    "reporter_id" to userId,
                    "reason" to reason
                )

                withContext(Dispatchers.IO) {
                    supabaseClient.from("reports").insert(reportData)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("FeedViewModel", "reportPost failed: ${e.localizedMessage}", e)
                _uiMessages.tryEmit(handleAppError(e))
            }
        }
    }

    fun fetchCommentsForPost(postId: String) {
        activeCommentPostId = postId
        viewModelScope.launch {
            try {
                val cloudComments = withContext(Dispatchers.IO) {
                    supabaseClient.from("comments")
                        .select { filter { eq("post_id", postId) } }
                        .decodeList<Comment>()
                }

                val userIds = cloudComments.map { it.userId }.distinct()
                val authorProfiles = if (userIds.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        supabaseClient.from("profiles")
                            .select { filter { isIn("id", userIds) } }
                            .decodeList<Profile>()
                    }
                } else {
                    emptyList()
                }

                val mergedComments = cloudComments.map { comment ->
                    val authorProfile = authorProfiles.find { it.id == comment.userId }
                    comment.copy(
                        userAvatarUrl = authorProfile?.avatarUrl,
                        displayName = authorProfile?.firstName ?: "User",
                        handle = authorProfile?.handle ?: "user"
                    )
                }
                _comments.value = mergedComments.sortedBy { it.createdAt.ifBlank { it.id } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
            }
        }
    }

    fun addComment(postId: String, content: String) {
        viewModelScope.launch {
            try {
                val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return@launch
                val post = _posts.value.find { it.id == postId }

                withContext(Dispatchers.IO) {
                    val newComment = CommentInsertRequest(
                        postId = postId,
                        userId = userId,
                        content = content
                    )

                    supabaseClient.from("comments").insert(newComment)

                    // 🌟 Send push notification if not commenting on own post
                    if (post != null && userId != post.userId) {
                        try {
                            val senderName = getCurrentUserName(userId)
                            val preview = truncateContent(post.content)
                            val notification = NotificationInsert(
                                recipientId = post.userId,
                                title = "New Comment",
                                body = "$senderName commented on your post: \"$preview\"",
                                data = mapOf(
                                    "sender_id" to userId,
                                    "post_id" to post.id
                                )
                            )
                            supabaseClient.from("notifications").insert(notification)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                fetchCommentsForPost(postId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
            }
        }
    }

    private fun truncateContent(content: String, maxLength: Int = 30): String {
        val clean = content.trim()
        return if (clean.length > maxLength) {
            clean.take(maxLength) + "..."
        } else {
            clean
        }
    }

    private suspend fun getCurrentUserName(userId: String): String {
        return try {
            val profile = withContext(Dispatchers.IO) {
                supabaseClient.from("profiles")
                    .select { filter { eq("id", userId) } }
                    .decodeSingleOrNull<Profile>()
            }
            profile?.firstName?.ifBlank { null } ?: "Someone"
        } catch (_: Exception) {
            "Someone"
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
        searchJob?.cancel()
        viewModelScope.launch {
            try {
                realtimeChannel?.unsubscribe()
            } catch (_: Exception) {}
        }
    }
}

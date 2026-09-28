package com.example.agora.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agora.data.supabaseClient
import com.example.agora.model.Comment
import com.example.agora.model.Post
import com.example.agora.model.PostLike
import com.example.agora.model.Profile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Immutable UI state for the single-post screen.
 */
@Immutable
sealed interface PostDetailUiState {
    data object Loading : PostDetailUiState

    @Immutable
    data class Loaded(
        val post: Post,
        val comments: List<Comment> = emptyList()
    ) : PostDetailUiState

    @Immutable
    data class Error(val message: String) : PostDetailUiState
}

/**
 * Destination-scoped ViewModel for the `post/{postId}?commentId={commentId}`
 * route.
 *
 * Because this ViewModel is created against the dialog destination's
 * [androidx.navigation.NavBackStackEntry], the nav arguments are delivered
 * through [SavedStateHandle] ("postId", "commentId") — they survive process
 * death and never leak into the shared feed state. The post is fetched
 * directly by id (it may not exist in the feed at all when opened from a
 * notification), on [Dispatchers.IO], and all state lives here so nothing
 * conflicts with [FeedViewModel]'s list.
 */
class PostDetailViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val postId: String? = savedStateHandle.get<String>("postId")?.takeIf { it.isNotBlank() }
    val commentId: String? = savedStateHandle.get<String>("commentId")?.takeIf { it.isNotBlank() }

    val currentUserId: String?
        get() = supabaseClient.auth.currentUserOrNull()?.id

    private val _uiState = MutableStateFlow<PostDetailUiState>(PostDetailUiState.Loading)
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()

    init {
        val id = postId
        if (id == null) {
            _uiState.value = PostDetailUiState.Error("Missing post id")
        } else {
            fetchPost(id)
            fetchComments(id)
        }
    }

    private fun fetchPost(id: String) {
        viewModelScope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) {
                    supabaseClient.from("posts").select(
                        columns = Columns.raw("*, profiles!fk_posts_user_id(*)")
                    ) {
                        filter { eq("id", id) }
                        limit(1)
                    }.decodeSingleOrNull<Post>()
                }

                if (fetched == null) {
                    _uiState.value = PostDetailUiState.Error("Post not found")
                    return@launch
                }

                // Merge "my" like/reaction without touching the shared feed state.
                val myLike = withContext(Dispatchers.IO) {
                    try {
                        val user = supabaseClient.auth.currentUserOrNull() ?: return@withContext null
                        supabaseClient.from("post_likes")
                            .select { filter { eq("user_id", user.id); eq("post_id", id) } }
                            .decodeList<PostLike>()
                            .firstOrNull()
                    } catch (_: Exception) {
                        null
                    }
                }

                val enriched = fetched.copy(
                    isLikedByMe = myLike != null,
                    myReaction = myLike?.reactionType
                )

                _uiState.value = PostDetailUiState.Loaded(enriched)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                _uiState.value = PostDetailUiState.Error("Failed to load post")
            }
        }
    }

    fun fetchComments(id: String) {
        viewModelScope.launch {
            try {
                val cloudComments = withContext(Dispatchers.IO) {
                    supabaseClient.from("comments")
                        .select { filter { eq("post_id", id) } }
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

                val merged = cloudComments.map { comment ->
                    val authorProfile = authorProfiles.find { it.id == comment.userId }
                    comment.copy(
                        userAvatarUrl = authorProfile?.avatarUrl,
                        displayName = authorProfile?.firstName ?: "User",
                        handle = authorProfile?.handle ?: "user"
                    )
                }.sortedBy { it.createdAt.ifBlank { it.id } }

                _uiState.value = when (val current = _uiState.value) {
                    is PostDetailUiState.Loaded -> current.copy(comments = merged)
                    else -> current
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
            }
        }
    }

    fun retry() {
        val id = postId ?: return
        _uiState.value = PostDetailUiState.Loading
        fetchPost(id)
        fetchComments(id)
    }

    /**
     * Optimistic like/reaction toggle scoped to this screen only.
     * Returns the optimistically updated [Post] so the caller can mirror it into
     * the feed (see FeedViewModel.syncPostState) without any fetch.
     */
    fun setLikeStatus(shouldBeLiked: Boolean, emoji: String = "❤️"): Post? {
        val loaded = _uiState.value as? PostDetailUiState.Loaded ?: return null
        val post = loaded.post
        val currentlyLiked = post.isLikedByMe
        val currentReaction = post.myReaction

        if (currentlyLiked == shouldBeLiked && currentReaction == emoji) return post

        val updated = post.copy(
            isLikedByMe = shouldBeLiked,
            myReaction = if (shouldBeLiked) emoji else null,
            likes = when {
                shouldBeLiked && !currentlyLiked -> post.likes + 1
                !shouldBeLiked && currentlyLiked -> (post.likes - 1).coerceAtLeast(0)
                else -> post.likes
            }
        )
        _uiState.value = loaded.copy(post = updated)

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val user = supabaseClient.auth.currentUserOrNull() ?: return@withContext
                    if (!shouldBeLiked) {
                        supabaseClient.from("post_likes").delete {
                            filter {
                                eq("user_id", user.id)
                                eq("post_id", post.id)
                            }
                        }
                    } else if (currentlyLiked) {
                        supabaseClient.from("post_likes").update(
                            mapOf("reaction_type" to emoji)
                        ) {
                            filter {
                                eq("user_id", user.id)
                                eq("post_id", post.id)
                            }
                        }
                    } else {
                        supabaseClient.from("post_likes").insert(
                            PostLike(userId = user.id, postId = post.id, reactionType = emoji)
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                // Revert optimistic update on error.
                _uiState.value = ( _uiState.value as? PostDetailUiState.Loaded )?.copy(post = post)
                    ?: _uiState.value
            }
        }

        return updated
    }

    fun retryComments() {
        postId?.let { fetchComments(it) }
    }
}

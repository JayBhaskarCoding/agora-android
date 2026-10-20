package com.example.agora.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun InstagramLikeButton(
    isLiked: Boolean,
    modifier: Modifier = Modifier,
    initialEmoji: String? = null,
    onLikeChanged: (isLiked: Boolean, emoji: String) -> Unit = { _, _ -> }
) {
    var showPopup by remember { mutableStateOf(false) }
    var activeEmoji by remember { mutableStateOf(if (isLiked) (initialEmoji ?: "❤️") else null) }
    var isBouncing by remember { mutableStateOf(false) }

    // Sync activeEmoji with parent state
    LaunchedEffect(isLiked, initialEmoji) {
        if (!isLiked) {
            activeEmoji = null
        } else {
            activeEmoji = initialEmoji ?: "❤️"
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isBouncing) 1.35f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "InstagramLikeBounce",
        finishedListener = { isBouncing = false }
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Floating Hovering Emoji Tray (Popup)
        ReactionPopup(
            isVisible = showPopup,
            onReactionSelected = { emoji ->
                activeEmoji = emoji
                showPopup = false
                isBouncing = true
                onLikeChanged(true, emoji)
            },
            onDismissRequest = { showPopup = false }
        )

        Box(
            modifier = Modifier
                .scale(scale)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            isBouncing = true
                            if (activeEmoji == null) {
                                activeEmoji = "❤️"
                                onLikeChanged(true, "❤️")
                            } else {
                                val currentEmoji = activeEmoji ?: "❤️"
                                activeEmoji = null
                                onLikeChanged(false, currentEmoji)
                            }
                        },
                        onLongPress = {
                            showPopup = true
                        }
                    )
                }
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (activeEmoji != null) {
                if (activeEmoji == "❤️") {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Heart Like",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Text(
                        text = activeEmoji!!,
                        fontSize = 24.sp
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = "Unlike",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

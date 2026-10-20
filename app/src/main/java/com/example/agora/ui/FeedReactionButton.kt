package com.example.agora.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedReactionButton(
    initialReaction: String? = null,
    initialCount: Int = 0,
    modifier: Modifier = Modifier,
    onReactionChanged: (String?, Int) -> Unit = { _, _ -> }
) {
    var selectedReaction by remember { mutableStateOf(initialReaction) }
    var reactionCount by remember { mutableIntStateOf(initialCount) }
    var isPickerVisible by remember { mutableStateOf(false) }
    var isBouncing by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isBouncing) 1.4f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ButtonBounceAnimation",
        finishedListener = { isBouncing = false }
    )

    Box(modifier = modifier, contentAlignment = Alignment.BottomStart) {

        // Floating Reaction Selector Popup
        ReactionPicker(
            isVisible = isPickerVisible,
            onReactionSelected = { newEmoji ->
                val oldCount = reactionCount
                val newCount = if (selectedReaction == null) oldCount + 1 else oldCount
                selectedReaction = newEmoji
                reactionCount = newCount
                isBouncing = true
                onReactionChanged(newEmoji, newCount)
            },
            onDismissRequest = { isPickerVisible = false },
            modifier = Modifier
                .offset(y = (-48).dp)
                .align(Alignment.TopStart)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .scale(scale)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        isBouncing = true
                        if (selectedReaction == null) {
                            selectedReaction = "❤️"
                            reactionCount += 1
                        } else {
                            selectedReaction = null
                            reactionCount = (reactionCount - 1).coerceAtLeast(0)
                        }
                        onReactionChanged(selectedReaction, reactionCount)
                    },
                    onLongClick = {
                        isPickerVisible = true
                    }
                )
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selectedReaction != null) {
                    if (selectedReaction == "❤️") {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Heart Reaction",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text(
                            text = selectedReaction!!,
                            fontSize = 22.sp
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = "No Reaction",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 🌟 Animated Number Counter
            AnimatedContent(
                targetState = reactionCount,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInVertically { height -> height } + fadeIn() togetherWith slideOutVertically { height -> -height } + fadeOut()
                    } else {
                        slideInVertically { height -> -height } + fadeIn() togetherWith slideOutVertically { height -> height } + fadeOut()
                    }
                },
                label = "ReactionCountAnimation"
            ) { count ->
                Text(
                    text = count.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selectedReaction != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

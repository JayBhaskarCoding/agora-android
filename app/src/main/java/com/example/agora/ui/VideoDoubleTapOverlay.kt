package com.example.agora.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun VideoDoubleTapOverlay(
    modifier: Modifier = Modifier,
    activeEmoji: String = "❤️",
    onTap: () -> Unit = {},
    onDoubleTapReaction: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var isAnimVisible by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0f) }
    val alphaAnim = remember { Animatable(1f) }
    val offsetYAnim = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onTap()
                    },
                    onDoubleTap = {
                        onDoubleTapReaction()

                        // 🌟 Trigger central floating heart/emoji animation
                        coroutineScope.launch {
                            isAnimVisible = true
                            scaleAnim.snapTo(0f)
                            alphaAnim.snapTo(1f)
                            offsetYAnim.snapTo(0f)

                            // 1. Spring scale up from 0 to 1.3 then settle at 1.0
                            launch {
                                scaleAnim.animateTo(
                                    targetValue = 1.3f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                )
                                scaleAnim.animateTo(1.0f)
                            }

                            // 2. Float upward by 50.dp and fade out over 700ms
                            launch {
                                offsetYAnim.animateTo(-50f, animationSpec = tween(700))
                            }

                            launch {
                                delay(350)
                                alphaAnim.animateTo(0f, animationSpec = tween(350))
                            }

                            delay(700)
                            isAnimVisible = false
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content()

        // 🌟 Floating Central Heart/Emoji Animation Overlay
        if (isAnimVisible) {
            Box(
                modifier = Modifier
                    .scale(scaleAnim.value)
                    .alpha(alphaAnim.value)
                    .offset { IntOffset(0, offsetYAnim.value.dp.roundToPx()) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeEmoji,
                    fontSize = 72.sp
                )
            }
        }
    }
}

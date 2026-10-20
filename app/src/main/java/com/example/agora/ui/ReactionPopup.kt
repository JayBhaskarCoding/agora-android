package com.example.agora.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 🌟 Reaction taxonomy — each type owns a signature selection physics played
 * with [Animatable] channels through [Modifier.graphicsLayer] (100% native
 * Compose; no Lottie or other external animation deps).
 *
 *  - Heart ❤️ : classic double-pulse heartbeat scale.
 *  - Laugh 😂 : rapid rotationZ shake while scaling up.
 *  - Wow   😮 : jaw-drop overshoot pop with a small hop.
 *  - Cry   😢 : slow easing teardrop — sinks down while fading out.
 *  - Fire  🔥 : rapid scale flicker while floating upward.
 */
sealed class ReactionType(val emoji: String) {
    data object Heart : ReactionType("❤️")
    data object Laugh : ReactionType("😂")
    data object Wow : ReactionType("😮")
    data object Cry : ReactionType("😢")
    data object Fire : ReactionType("🔥")

    companion object {
        val ALL: List<ReactionType> = listOf(Heart, Laugh, Wow, Cry, Fire)

        fun fromEmoji(emoji: String?): ReactionType? = ALL.firstOrNull { it.emoji == emoji }
    }
}

/** Kept for backward compatibility with earlier call sites/tests. */
val INSTAGRAM_REACTION_EMOJIS: List<String> = ReactionType.ALL.map { it.emoji }

/**
 * The four Animatable channels driving one emoji's graphicsLayer:
 * scale (uniform), rotationZ (degrees), offsetY (px, +down) and alpha.
 */
private class EmojiPhysics {
    val scale = Animatable(1f)
    val rotation = Animatable(0f)
    val offsetY = Animatable(0f)
    val alpha = Animatable(1f)
}

/**
 * ✦ The long-press reaction tray — flagship animation pass.
 *
 * Entrance ("the pop-in"): the whole tray is wrapped in [AnimatedVisibility]
 * with a playful `scaleIn(spring(MediumBouncy, StiffnessLow)) + fadeIn()`, and
 * every emoji additionally staggers in on its own delayed bouncy spring.
 *
 * Selection flow: tap → instant [HapticFeedbackType.LongPress] → the tray is
 * LOCKED (further taps ignored) → the tapped emoji plays its signature
 * [ReactionType] physics while its siblings shrink away → only when the
 * animation coroutine completes does [onReactionSelected] fire (parent then
 * closes the popup and registers the reaction with the backend).
 *
 * Dismissal is never blocked: outside-tap / back-press removes the popup mid
 * animation, cancelling the scope so no reaction is committed — exactly the
 * "tap outside always works" guardrail.
 */
@Composable
fun ReactionPopup(
    isVisible: Boolean,
    onReactionSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    reactions: List<ReactionType> = ReactionType.ALL
) {
    if (!isVisible) return

    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, -110),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // Flip one frame after entry so AnimatedVisibility always plays the
        // pop-in (fresh composition on every long-press — remember resets).
        var contentVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { contentVisible = true }

        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current

        // One physics rig per emoji + the one-shot selection lock.
        val physics = remember(reactions) { reactions.associateWith { EmojiPhysics() } }
        var selected by remember { mutableStateOf<ReactionType?>(null) }

        AnimatedVisibility(
            visible = contentVisible,
            enter = scaleIn(
                initialScale = 0.6f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(),
            exit = scaleOut(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeOut()
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                shadowElevation = 12.dp,
                modifier = modifier
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    reactions.forEachIndexed { index, reaction ->
                        PopupEmojiItem(
                            reaction = reaction,
                            physics = physics.getValue(reaction),
                            enterDelayMs = index * 45,
                            contentVisible = contentVisible,
                            isSelected = selected == reaction,
                            isDimmed = selected != null && selected != reaction,
                            onTap = {
                                // 🔒 One-shot lock — the tray commits a single reaction.
                                if (selected == null) {
                                    // Instant physical acknowledgment of the tap.
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selected = reaction
                                    val liftPx = with(density) { 14.dp.toPx() }
                                    scope.launch {
                                        // Play the signature physics to completion…
                                        playSelectionPhysics(reaction, physics.getValue(reaction), liftPx)
                                        // …THEN close + register (parent handler). If the
                                        // popup was dismissed mid-flight this coroutine is
                                        // cancelled and nothing is committed.
                                        onReactionSelected(reaction.emoji)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One tray emoji: staggered spring entrance, press hover-scale, physics-driven
 * [graphicsLayer] (scale × press, rotationZ, translationY, alpha) and a shrink
 * -away when a sibling gets chosen.
 */
@Composable
private fun PopupEmojiItem(
    reaction: ReactionType,
    physics: EmojiPhysics,
    enterDelayMs: Int,
    contentVisible: Boolean,
    isSelected: Boolean,
    isDimmed: Boolean,
    onTap: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Pre-commit hover enlargement (multiplies with the physics scale so the
    // signature animations are never fought by the press state).
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && !isSelected && !isDimmed) 1.5f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "PopupEmojiPressScale"
    )

    // Unselected siblings shrink out of the way once one is committed.
    LaunchedEffect(isDimmed) {
        if (isDimmed) {
            coroutineScope {
                launch { physics.alpha.animateTo(0f, tween(180)) }
                launch { physics.scale.animateTo(0.4f, tween(180)) }
            }
        }
    }

    // ✦ Stagger without spring(delayMillis = …) — that parameter does not exist
    //   in Compose animation-core (only tween/keyframes accept one). Each emoji
    //   flips its own visibility after an index-based delay instead, so every
    //   item still bounces in on its full MediumBouncy spring, cascading left
    //   to right.
    var itemVisible by remember { mutableStateOf(false) }
    LaunchedEffect(contentVisible) {
        if (contentVisible) {
            delay(enterDelayMs.toLong())
            itemVisible = true
        } else {
            itemVisible = false
        }
    }

    AnimatedVisibility(
        visible = itemVisible,
        enter = scaleIn(
            initialScale = 0.2f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(tween(200)),
        exit = fadeOut(tween(120))
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = physics.scale.value * pressScale
                    scaleY = physics.scale.value * pressScale
                    rotationZ = physics.rotation.value
                    translationY = physics.offsetY.value
                    alpha = physics.alpha.value
                }
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTap
                )
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = reaction.emoji,
                fontSize = 24.sp
            )
        }
    }
}

/**
 * The signature selection physics per [ReactionType] — pure [Animatable]
 * choreography. `liftPx` is a density-correct 14dp in pixels (upward = negative
 * translationY). Suspends until the whole choreography has finished, so the
 * caller can commit the reaction only after the animation lands.
 */
private suspend fun playSelectionPhysics(
    reaction: ReactionType,
    p: EmojiPhysics,
    liftPx: Float
) {
    when (reaction) {
        ReactionType.Fire -> coroutineScope {
            // 🔥 Flicker: rapid scale oscillation while floating upward.
            launch {
                listOf(1.28f, 0.94f, 1.2f, 0.98f, 1.12f, 1f).forEach { v ->
                    p.scale.animateTo(v, keyframes { durationMillis = 65 })
                }
            }
            launch { p.offsetY.animateTo(-liftPx, tween(420, easing = EaseOut)) }
        }

        ReactionType.Laugh -> coroutineScope {
            // 😂 Shaking with laughter: rapid rotationZ waggle + scale-up.
            launch {
                listOf(-16f, 14f, -11f, 8f, -4f, 0f).forEach { v ->
                    p.rotation.animateTo(v, keyframes { durationMillis = 65 })
                }
            }
            launch {
                p.scale.animateTo(
                    1.3f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        }

        ReactionType.Cry -> coroutineScope {
            // 😢 Teardrop: slow easing drop downward while fading out.
            launch { p.offsetY.animateTo(liftPx * 2.2f, tween(700, easing = EaseIn)) }
            launch { p.alpha.animateTo(0f, tween(700)) }
        }

        ReactionType.Heart -> {
            // ❤️ Classic double-pulse heartbeat: up, down, up, down.
            listOf(1.32f, 1f, 1.26f, 1f).forEach { v ->
                p.scale.animateTo(v, keyframes { durationMillis = 110 })
            }
        }

        ReactionType.Wow -> coroutineScope {
            // 😮 Jaw-drop pop: big overshoot, tiny hop, settle.
            launch {
                listOf(1.5f, 0.92f, 1.15f, 1f).forEach { v ->
                    p.scale.animateTo(v, keyframes { durationMillis = 95 })
                }
            }
            launch {
                p.offsetY.animateTo(-liftPx * 0.6f, tween(180, easing = EaseOut))
                p.offsetY.animateTo(0f, tween(240, easing = EaseIn))
            }
        }
    }
}

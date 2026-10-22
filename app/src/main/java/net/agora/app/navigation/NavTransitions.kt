package net.agora.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * ✦ COMPOSE ADAPTER over [NavMotion].
 *
 * The rules — which move is hierarchical, which is a peer switch, how far the
 * parallax travels — all live in [NavMotion] so they can be unit tested. This
 * file only turns those rules into [EnterTransition] / [ExitTransition] values.
 *
 * ## Why slides do not fade
 *
 * Every destination is an opaque full-screen surface (the feed paints a vertical
 * gradient, Account Details paints `colorScheme.background`, …). Sliding two
 * opaque surfaces means the screen is completely covered at every frame: no
 * background shows through, and the two destinations are never alpha-blended.
 * That is precisely what the default cross-dissolve got wrong.
 *
 * ## Direction
 *
 * The `isPop` flag is passed in explicitly rather than read from the navigator,
 * so the forward and reverse motions are visibly mirror images:
 *
 * ```
 *   push:   [ old ⇒ -25% ]  ← [ new        ]
 *   pop:    [ old        ]  →  [ new -25% ⇒ ]
 * ```
 *
 * Back is the exact inverse of forward, which is what makes the gesture feel
 * like retracing a step rather than a second, unrelated animation.
 */
internal object NavTransitions {

    /** Forward-navigation enter, and (via pop) the reverse. */
    fun enter(
        initial: NavBackStackEntry,
        target: NavBackStackEntry,
        isPop: Boolean
    ): EnterTransition {
        val kind = kindOf(initial, target)
        val slide = tween<IntOffset>(
            durationMillis = NavMotion.DURATION_MS,
            easing = FastOutSlowInEasing
        )
        return when {
            kind == NavMotion.Kind.PEER -> fadeIn(
                animationSpec = tween(
                    durationMillis = NavMotion.PEER_FADE_IN_MS,
                    delayMillis = NavMotion.PEER_FADE_IN_DELAY_MS,
                    easing = FastOutSlowInEasing
                )
            )

            // Returning: the restored screen eases in from the left, but only a
            // quarter of the width — the outgoing screen is the one that moves.
            isPop -> slideInHorizontally(animationSpec = slide) { fullWidth ->
                -fullWidth / NavMotion.PARALLAX_DIVISOR
            }

            // Pushing: the new screen travels the full width, over the old one.
            else -> slideInHorizontally(animationSpec = slide) { fullWidth -> fullWidth }
        }
    }

    /** Forward-navigation exit, and (via pop) the reverse. */
    fun exit(
        initial: NavBackStackEntry,
        target: NavBackStackEntry,
        isPop: Boolean
    ): ExitTransition {
        val kind = kindOf(initial, target)
        val slide = tween<IntOffset>(
            durationMillis = NavMotion.DURATION_MS,
            easing = FastOutSlowInEasing
        )
        return when {
            // A peer switch: the outgoing tab is gone before the incoming one
            // starts, so the two are never both readable at the same time.
            kind == NavMotion.Kind.PEER -> fadeOut(
                animationSpec = tween(
                    durationMillis = NavMotion.PEER_FADE_OUT_MS,
                    easing = FastOutSlowInEasing
                )
            )

            // Popping: this screen leaves to the right, uncovering the one below.
            isPop -> slideOutHorizontally(animationSpec = slide) { fullWidth -> fullWidth }

            // Pushing: the current screen drifts left a quarter width behind the
            // new one — depth without pulling focus from what is arriving.
            else -> slideOutHorizontally(animationSpec = slide) { fullWidth ->
                -fullWidth / NavMotion.PARALLAX_DIVISOR
            }
        }
    }

    private fun kindOf(initial: NavBackStackEntry, target: NavBackStackEntry): NavMotion.Kind =
        NavMotion.kindOf(
            initialRoute = initial.destination.route,
            initialHasUserArgument =
                initial.arguments?.getString(NavMotion.USER_ID_ARG) != null,
            targetRoute = target.destination.route,
            targetHasUserArgument =
                target.arguments?.getString(NavMotion.USER_ID_ARG) != null
        )
}

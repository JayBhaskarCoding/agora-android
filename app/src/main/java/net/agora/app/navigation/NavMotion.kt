package net.agora.app.navigation

/**
 * ✦ NAVIGATION MOTION POLICY — which destinations animate *how*, and why.
 *
 * This is deliberately free of Compose and Android types so it can be unit
 * tested on the JVM: [NavTransitions] is the thin Compose adapter over it.
 *
 * ## The bug this exists to prevent
 *
 * `NavHost` transitions used to be left at the library defaults. Verified in the
 * navigation-compose source (`DefaultNavTransitions`), those defaults are:
 *
 *  * `enterTransition`   = `fadeIn(tween(700))`
 *  * `exitTransition`    = `fadeOut(tween(700))`
 *  * `popEnterTransition` = **the same** fade as `enter`
 *  * `popExitTransition`  = **the same** fade as `exit`
 *
 * So a back press was a 700 ms cross-dissolve with no direction at all. A
 * cross-dissolve renders BOTH full-screen destinations simultaneously at partial
 * alpha for the whole 700 ms — on opaque, gradient-heavy screens like the feed
 * and Account Details that is a double exposure, and it is what reads on device
 * as the page "tearing apart" or flashing. Adding direction (a slide) removes the
 * double exposure entirely, because the incoming screen covers the outgoing one
 * instead of blending with it.
 *
 * ## The two motions
 *
 * [Kind.HIERARCHICAL] — a real push or pop (feed → account details → pending
 * deletion). Movement along the horizontal axis encodes depth: forward slides in
 * from the right, back slides out to the right. The outgoing surface is *not*
 * faded, so at every instant the screen is fully covered by the two layers and
 * there is no background flash.
 *
 * [Kind.PEER] — a lateral move between sibling tabs of the bottom pill
 * (feed ↔ own profile). A horizontal slide here would claim a hierarchy that does
 * not exist, so tabs use a "fade through": the outgoing tab is fully gone before
 * the incoming one arrives, which is the Material pattern for unordered changes.
 *
 * Note the asymmetry is the point — in both cases the two surfaces are never
 * blended at high alpha for long, which is the actual defect being fixed.
 */
internal object NavMotion {

    /** One shared clock for the content transition and the nav pill. */
    const val DURATION_MS = 320

    /**
     * Fade-through timings for peer (tab) moves.
     *
     * [PEER_FADE_IN_DELAY_MS] is deliberately equal to [PEER_FADE_OUT_MS]: the
     * outgoing tab reaches alpha 0 on exactly the frame the incoming one starts
     * to appear, so the two are never both readable. That is the whole point of a
     * fade *through* versus a cross-dissolve.
     */
    const val PEER_FADE_OUT_MS = 90
    const val PEER_FADE_IN_DELAY_MS = 90
    const val PEER_FADE_IN_MS = 210

    /** The outgoing surface travels 1/4 as far as the incoming one (parallax). */
    const val PARALLAX_DIVISOR = 4

    const val FEED_ROUTE = "feed"
    const val PROFILE_ROUTE_PREFIX = "profile"
    const val USER_ID_ARG = "userId"

    enum class Kind {
        /** Push/pop between different levels: directional slide. */
        HIERARCHICAL,

        /** Sibling tabs: fade through, no lateral claim. */
        PEER
    }

    /**
     * True when this destination is one of the two bottom-pill tabs.
     *
     * The profile destination is declared as `profile?userId={userId}`, so the
     * route alone cannot tell "my profile" (a tab) from "someone's profile"
     * (a pushed detail): the presence of the `userId` argument is what makes the
     * difference, and it must be passed in explicitly.
     */
    fun isTab(route: String?, hasUserArgument: Boolean): Boolean = when {
        route == null -> false
        route == FEED_ROUTE -> true
        route.startsWith(PROFILE_ROUTE_PREFIX) -> !hasUserArgument
        else -> false
    }

    /**
     * Classifies a transition. Unresolvable routes fall back to
     * [Kind.HIERARCHICAL], which is the safer of the two: a slide is always
     * readable, whereas showing a fade for a push loses the depth cue.
     */
    fun kindOf(
        initialRoute: String?,
        initialHasUserArgument: Boolean,
        targetRoute: String?,
        targetHasUserArgument: Boolean
    ): Kind =
        if (isTab(initialRoute, initialHasUserArgument) && isTab(targetRoute, targetHasUserArgument)) {
            Kind.PEER
        } else {
            Kind.HIERARCHICAL
        }
}

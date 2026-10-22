package net.agora.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation *decision* rules: which move is hierarchical (directional slide)
 * and which is a peer switch (fade-through).
 *
 * These matter because getting them wrong is not a crash — it is a transition
 * that claims a hierarchy which does not exist (sliding sideways between two
 * sibling tabs) or one that hides depth (fading between a list and its detail).
 * Both look "cheap" without ever failing a build, so they are pinned here.
 */
class NavMotionTest {

    private val feed = NavMotion.FEED_ROUTE
    private val profileRoute = "profile?userId={userId}"

    private fun kindOf(
        initialRoute: String?,
        initialHasUser: Boolean,
        targetRoute: String?,
        targetHasUser: Boolean
    ) = NavMotion.kindOf(initialRoute, initialHasUser, targetRoute, targetHasUser)

    // ── what counts as a tab ─────────────────────────────────────────────────

    @Test
    fun feedIsATab() {
        assertTrue(NavMotion.isTab(feed, hasUserArgument = false))
    }

    @Test
    fun ownProfileIsATabButSomeoneElsesProfileIsNot() {
        assertTrue(
            "The profile destination with no userId is the bottom-pill Profile tab.",
            NavMotion.isTab(profileRoute, hasUserArgument = false)
        )
        assertFalse(
            "profile?userId=… is a pushed detail screen, not a tab — sliding to it " +
                "laterally would claim it is a sibling of the feed.",
            NavMotion.isTab(profileRoute, hasUserArgument = true)
        )
    }

    @Test
    fun pushedScreensAreNotTabs() {
        listOf("account_details", "search", "verify_deletion", "deletion_mode", "pending_deletion")
            .forEach { route ->
                assertFalse("$route must not be classified as a tab", NavMotion.isTab(route, false))
            }
    }

    /** An unknown/absent route must not be mistaken for a tab. */
    @Test
    fun aMissingRouteIsNeverATab() {
        assertFalse(NavMotion.isTab(null, hasUserArgument = false))
        assertFalse(NavMotion.isTab(null, hasUserArgument = true))
    }

    // ── classification ───────────────────────────────────────────────────────

    @Test
    fun switchingBetweenTheTwoTabsIsAPeerSwitch() {
        assertEquals(
            NavMotion.Kind.PEER,
            kindOf(feed, false, profileRoute, false)
        )
        assertEquals(
            NavMotion.Kind.PEER,
            kindOf(profileRoute, false, feed, false)
        )
    }

    @Test
    fun goingFromATabIntoADetailIsHierarchical() {
        assertEquals(NavMotion.Kind.HIERARCHICAL, kindOf(feed, false, "account_details", false))
        assertEquals(NavMotion.Kind.HIERARCHICAL, kindOf(feed, false, "search", false))
        assertEquals(
            "Opening another user's profile from the feed is a push.",
            NavMotion.Kind.HIERARCHICAL,
            kindOf(feed, false, profileRoute, true)
        )
    }

    @Test
    fun comingBackFromADetailToATabIsHierarchical() {
        assertEquals(NavMotion.Kind.HIERARCHICAL, kindOf("account_details", false, feed, false))
        assertEquals(
            NavMotion.Kind.HIERARCHICAL,
            kindOf(profileRoute, true, profileRoute, false)
        )
    }

    /** The account-deletion chain is three pushes deep; all of it is hierarchical. */
    @Test
    fun theDeletionChainIsHierarchicalAtEveryStep() {
        val chain = listOf(
            "account_details" to "verify_deletion",
            "verify_deletion" to "deletion_mode",
            "deletion_mode" to "pending_deletion"
        )
        chain.forEach { (from, to) ->
            assertEquals(
                "$from -> $to must slide, not fade",
                NavMotion.Kind.HIERARCHICAL,
                kindOf(from, false, to, false)
            )
        }
    }

    /**
     * A tab switch must never be inferred from a route we cannot classify: the
     * fallback is the slide, because a slide is always readable while a fade on a
     * push silently loses the depth cue.
     */
    @Test
    fun unresolvableRoutesFallBackToTheSlide() {
        assertEquals(NavMotion.Kind.HIERARCHICAL, kindOf(null, false, null, false))
        assertEquals(NavMotion.Kind.HIERARCHICAL, kindOf(null, false, feed, false))
        assertEquals(NavMotion.Kind.HIERARCHICAL, kindOf(feed, false, null, false))
    }

    // ── timing invariants ────────────────────────────────────────────────────

    @Test
    fun theSlideHasANonZeroDuration() {
        assertTrue("A 0 ms transition would be a snap.", NavMotion.DURATION_MS > 0)
    }

    /**
     * The peer fade must never be a cross-dissolve: the outgoing tab has to be
     * fully gone before the incoming one begins to appear. If the delay ever
     * drops below the fade-out, both tabs are semi-visible at once — exactly the
     * double exposure this whole change removes.
     */
    @Test
    fun theOutgoingTabIsGoneBeforeTheIncomingOneAppears() {
        assertTrue(
            "PEER_FADE_IN_DELAY_MS (${NavMotion.PEER_FADE_IN_DELAY_MS}) must be >= " +
                "PEER_FADE_OUT_MS (${NavMotion.PEER_FADE_OUT_MS}), or the two tabs overlap.",
            NavMotion.PEER_FADE_IN_DELAY_MS >= NavMotion.PEER_FADE_OUT_MS
        )
    }

    /** A tab switch must not feel slower than the slide it replaces. */
    @Test
    fun aTabSwitchIsNoSlowerThanASlide() {
        val peerTotal = NavMotion.PEER_FADE_IN_DELAY_MS + NavMotion.PEER_FADE_IN_MS
        assertTrue(
            "The fade-through takes ${peerTotal}ms but the slide takes " +
                "${NavMotion.DURATION_MS}ms; a lateral tab change should not feel heavier " +
                "than a push.",
            peerTotal <= NavMotion.DURATION_MS
        )
    }

    /**
     * The parallax is the visual proof that the outgoing screen is *behind* the
     * incoming one. At a divisor of 1 it would travel the full width too and the
     * two layers would read as a flat conveyor belt.
     */
    @Test
    fun theOutgoingScreenTravelsLessThanTheIncomingOne() {
        assertTrue(
            "PARALLAX_DIVISOR must be at least 2 so the outgoing screen visibly lags.",
            NavMotion.PARALLAX_DIVISOR >= 2
        )
    }
}

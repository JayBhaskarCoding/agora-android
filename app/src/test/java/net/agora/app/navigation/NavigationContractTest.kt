package net.agora.app.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards on the one NavHost.
 *
 * [NavMotionTest] covers the *policy*; this covers the *wiring*, because every
 * failure mode here is silent: no compile error, no crash, no lint warning —
 * just a transition that quietly reverts to the library's 700 ms cross-dissolve,
 * or a Back handler that resets the graph instead of popping it. Both have
 * shipped in this app before, which is why they are now asserted.
 */
class NavigationContractTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate app/src/main from ${System.getProperty("user.dir")}")
    }

    private val mainScreen: String
        get() = File(repoRoot(), "app/src/main/java/net/agora/app/ui/MainScreen.kt").readText()

    private fun source(relativePath: String): String =
        File(repoRoot(), relativePath).readText()

    private fun mainSources(): List<File> =
        File(repoRoot(), "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    // ── the transitions ─────────────────────────────────────────────────────

    /**
     * All four transition slots must be supplied.
     *
     * If `popEnterTransition`/`popExitTransition` are omitted they default to the
     * *forward* transitions, and Back becomes indistinguishable from a push.
     * If all four are omitted the library cross-dissolves two opaque screens for
     * 700 ms — the reported "tearing".
     */
    @Test
    fun theNavHostDeclaresAllFourTransitions() {
        val source = mainScreen()
        listOf(
            "enterTransition",
            "exitTransition",
            "popEnterTransition",
            "popExitTransition"
        ).forEach { slot ->
            assertEquals(
                "$slot must appear exactly once, wired into the NavHost call.",
                1,
                Regex(Regex.escape(slot) + "\\s*=").findAll(source).count()
            )
            assertTrue(
                "$slot is not delegating to NavTransitions — the motion rules must not be " +
                    "re-implemented inline where they cannot be tested.",
                Regex(Regex.escape(slot) + "\\s*=\\s*\\{\\s*NavTransitions\\.").containsMatchIn(source)
            )
        }
    }

    /** Pop must be the mirror of push, not a copy of it. */
    @Test
    fun popTransitionsAreDirectionAware() {
        val source = mainScreen()
        assertTrue(
            "popEnterTransition must pass isPop = true.",
            Regex("popEnterTransition\\s*=\\s*\\{[^}]*isPop\\s*=\\s*true").containsMatchIn(source)
        )
        assertTrue(
            "popExitTransition must pass isPop = true.",
            Regex("popExitTransition\\s*=\\s*\\{[^}]*isPop\\s*=\\s*true").containsMatchIn(source)
        )
    }

    /**
     * The predictive-back slots must exist and must reuse the POP transition.
     *
     * These are a separate code path from the four above: during a back *gesture*
     * NavHost seeks `predictivePop*` against the finger rather than running
     * `popEnter`/`popExit`, and it only commits on release. Left at their library
     * defaults — `scaleOut(0.7f)` / `fadeIn(spring)` — the preview shrank the page
     * to 70% inside the window and then snapped back to full size to slide away,
     * so the tearing survived on the gesture route after the button route was
     * fixed. Asserting `isPop = true` pins the preview to the same motion the
     * release commits to; without it the two disagree at the commit frame.
     */
    @Test
    fun predictiveBackPreviewsThePopTransition() {
        val source = mainScreen()

        val enter = Regex(
            "predictivePopEnterTransition\\s*=\\s*\\{\\s*NavTransitions\\.enter\\(" +
                "[^)]*isPop\\s*=\\s*true\\s*\\)"
        )
        val exit = Regex(
            "predictivePopExitTransition\\s*=\\s*\\{\\s*NavTransitions\\.exit\\(" +
                "[^)]*isPop\\s*=\\s*true\\s*\\)"
        )

        assertTrue(
            "predictivePopEnterTransition must delegate to NavTransitions.enter(..., isPop = true) " +
                "so the swipe preview is the pop it commits to.",
            enter.containsMatchIn(source)
        )
        assertTrue(
            "predictivePopExitTransition must delegate to NavTransitions.exit(..., isPop = true). " +
                "Its default is scaleOut(0.7f), which shrinks the page inside the window.",
            exit.containsMatchIn(source)
        )
    }

    // ── the modal auth flow ─────────────────────────────────────────────────

    /**
     * Change / Reset Password is not a NavHost destination — it must work signed
     * out, when no graph exists — so it gets its own enter/exit pair.
     *
     * The regression: it was composed as a bare `if (passwordResetStep != null)`,
     * which mounts and unmounts a full-screen opaque surface between two frames.
     * Opening had no motion; Back deleted the screen in a single step.
     */
    @Test
    fun passwordResetFlowIsAnimatedRatherThanSwapped() {
        val activity = source("app/src/main/java/net/agora/app/MainActivity.kt")

        assertFalse(
            "PasswordResetScreen is still behind a bare `if (passwordResetStep != null)` — " +
                "that is an instant swap, not a transition.",
            activity.contains("if (passwordResetStep != null)")
        )
        assertTrue(
            "PasswordResetScreen must be wrapped in AnimatedVisibility.",
            Regex("AnimatedVisibility\\(\\s*\\n?\\s*visible\\s*=\\s*passwordResetStep\\s*!=\\s*null")
                .containsMatchIn(activity)
        )
        assertTrue(
            "The overlay must use NavTransitions.overlayEnter().",
            activity.contains("NavTransitions.overlayEnter()")
        )
        assertTrue(
            "The overlay must use NavTransitions.overlayExit().",
            activity.contains("NavTransitions.overlayExit()")
        )
    }

    /**
     * Its content must survive the exit frame.
     *
     * `passwordResetStep` is null the moment the flow is cancelled, and the step
     * card deliberately renders nothing for null. Animating the panel out while
     * reading the live step would therefore slide an EMPTY glass panel away — the
     * form vanishing first, its frame following. `targetState` must be pinned to a
     * value that remembers the last step that was actually shown.
     */
    @Test
    fun passwordResetExitKeepsItsLastStepPainted() {
        val screen = source("app/src/main/java/net/agora/app/ui/PasswordResetScreen.kt")

        assertTrue(
            "The last non-null step must be remembered for the exit animation.",
            Regex("val\\s+displayStep:\\s*PasswordResetStep\\?\\s*=\\s*step\\s*\\?:\\s*lastVisibleStep")
                .containsMatchIn(screen)
        )
        assertTrue(
            "AnimatedContent must target displayStep, not the live (already null) step.",
            screen.contains("targetState = displayStep")
        )
        assertFalse(
            "AnimatedContent is still targeting the live step; it will blank the card " +
                "before the panel finishes sliding away.",
            Regex("targetState\\s*=\\s*step\\s*,").containsMatchIn(screen)
        )
    }

    // ── back handling ───────────────────────────────────────────────────────

    /**
     * Every `onBack` in the graph must POP.
     *
     * The regression this blocks is `onBack = { navController.navigate("feed") }`
     * — which "returns home" while throwing the stack away, so the next Back
     * exits the app and the user's route is gone. Requirement: Back must pop.
     */
    @Test
    fun everyBackCallbackPopsTheBackStack() {
        val bodies = Regex("onBack\\s*=\\s*\\{([^{}]*)\\}").findAll(mainScreen())
            .map { it.groupValues[1].trim() }
            .toList()

        assertTrue("No onBack callbacks found — the regex or the graph changed shape.", bodies.isNotEmpty())
        bodies.forEach { body ->
            assertTrue(
                "onBack = { $body } does not call popBackStack(). Back must pop the stack, " +
                    "never navigate (that resets the graph) and never call setGraph.",
                body.contains("popBackStack")
            )
            assertFalse(
                "onBack = { $body } navigates instead of popping. That discards the back stack.",
                body.contains("navigate(")
            )
        }
    }

    /** No screen may rebuild the graph root; that is what wipes the stack. */
    @Test
    fun nothingReplacesTheNavigationGraph() {
        val offenders = mainSources().filter { it.readText().contains("setGraph(") }
        assertTrue(
            "setGraph() is called in ${offenders.map { it.name }}. Replacing the graph clears " +
                "the back stack — the exact reset this task forbids.",
            offenders.isEmpty()
        )
    }

    // ── pushes and tab switches ─────────────────────────────────────────────

    /**
     * Every push must be `launchSingleTop`.
     *
     * Without it a double-tap on "Account Details" (or on an avatar) pushes two
     * identical destinations: the first Back lands on a pixel-identical copy, so
     * Back appears to do nothing and then jumps two screens at once.
     */
    /**
     * Splits the source into complete `navController.navigate(...) { ... }` calls.
     *
     * A character window is not good enough here: one of these calls carries a
     * three-line comment and a nested builder before its guard, and a fixed-size
     * window either truncates it or borrows the next call's guard. Walking the
     * brackets is exact.
     */
    private fun navigateCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        val marker = "navController.navigate("
        var start = source.indexOf(marker)

        while (start >= 0) {
            // Match the argument list.
            var i = start + marker.length - 1
            var depth = 0
            var argsEnd = -1
            while (i < source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) { argsEnd = i + 1; break }
                    }
                }
                i++
            }
            if (argsEnd < 0) break

            // Match an optional trailing builder lambda.
            var end = argsEnd
            var j = argsEnd
            while (j < source.length && source[j].isWhitespace()) j++
            if (j < source.length && source[j] == '{') {
                var braces = 0
                var k = j
                while (k < source.length) {
                    when (source[k]) {
                        '{' -> braces++
                        '}' -> {
                            braces--
                            if (braces == 0) { end = k + 1; break }
                        }
                    }
                    k++
                }
            }

            calls += source.substring(start, end)
            start = source.indexOf(marker, end)
        }
        return calls
    }

    @Test
    fun everyPushGuardsAgainstDuplicateDestinations() {
        val calls = navigateCalls(mainScreen())
        assertTrue("No navigate() calls found — the marker string changed.", calls.isNotEmpty())

        calls.forEach { call ->
            assertTrue(
                "This push has no launchSingleTop:\n" + call.trim().take(220),
                call.contains("launchSingleTop")
            )
        }
    }

    /**
     * Bottom-pill tab switches must use the canonical save/restore triple, or the
     * feed's scroll offset and Profile's selected tab are lost on every switch.
     */
    @Test
    fun tabSwitchesSaveAndRestoreState() {
        val source = mainScreen()
        listOf("feed", "profile").forEach { route ->
            // Capture THROUGH "= true": a lazy match that stops at the bare word
            // would then assert on text that excludes the very token under test.
            val block = Regex(
                "navigate\\(\"" + route + "\"\\)\\s*\\{[\\s\\S]{0,400}?restoreState\\s*=\\s*true"
            ).find(source)

            assertTrue("Tab switch to \"$route\" is missing its builder block.", block != null)
            val text = block!!.value
            assertTrue(
                "Tab switch to \"$route\" must popUpTo the start destination.",
                text.contains("popUpTo")
            )
            assertTrue(
                "Tab switch to \"$route\" must saveState = true.",
                text.contains("saveState = true")
            )
            assertTrue(
                "Tab switch to \"$route\" must restoreState = true, or the tab reopens at the top.",
                text.contains("restoreState = true")
            )
        }
    }
}

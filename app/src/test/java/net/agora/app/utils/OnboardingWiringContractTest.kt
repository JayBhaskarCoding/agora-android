package net.agora.app.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards on the onboarding write + routing wiring.
 *
 * [OnboardingRoutingTest] proves the policy. This proves the app actually uses it
 * — because every failure mode here is silent: the wizard still advances, the
 * user still reaches Home, and the damage only shows up after a restart, when the
 * next cold start re-reads the row.
 */
class OnboardingWiringContractTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate app/src/main from ${System.getProperty("user.dir")}")
    }

    private fun source(relativePath: String): String = File(repoRoot(), relativePath).readText()

    private fun codeOnly(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .lines()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker >= 0) line.substring(0, marker) else line
            }

    private val viewModel: String
        get() = source("app/src/main/java/net/agora/app/viewmodel/AuthViewModel.kt")

    private val viewModelCode: String get() = codeOnly(viewModel)

    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) return ""
        val open = text.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return ""
    }

    // ── the decision is made in one place ───────────────────────────────────

    /**
     * Nothing may hand-roll "profile == null means onboarding" any more.
     *
     * That single expression is the bug: at cold start it is also the answer when
     * the read merely failed, and the same boolean drove the account deletion.
     */
    @Test
    fun noCallSiteReimplementsTheCompletenessRule() {
        val code = viewModelCode
        assertFalse(
            "A `profile == null`-style completeness test has reappeared. Use OnboardingRouting.",
            code.contains("profile == null ||") || code.contains("profile == null ->")
        )
        assertFalse(
            "isProfileIncomplete must stay deleted.",
            code.contains("isProfileIncomplete")
        )
        assertTrue(
            "The verdict must come from OnboardingRouting.",
            code.contains("OnboardingRouting.verdict(")
        )
    }

    /**
     * Cold start must consult the local record BEFORE any network work, and must
     * not wipe when it does.
     */
    @Test
    fun coldStartHonoursTheLocalCompletionRecordFirst() {
        val body = functionBody(viewModelCode, "private fun verifyProfileCompleteness(")
        assertTrue("verifyProfileCompleteness not found", body.isNotEmpty())

        val localCheck = body.indexOf("hasCompletionEvidence(userId)")
        val query = body.indexOf("readProfileVerdict(")
        assertTrue(
            "Cold start must consult the completion evidence (local record + metadata).",
            localCheck >= 0
        )
        assertTrue(
            "The evidence must be consulted before the profile query.",
            query < 0 || localCheck < query
        )
        assertTrue(
            "A locally completed account must be routed Home explicitly.",
            body.contains("sendNav(AuthNavTarget.Home)")
        )
    }

    @Test
    fun theCleanupIsGatedByThePolicy() {
        val body = functionBody(viewModelCode, "private fun verifyProfileCompleteness(")
        assertTrue(
            "Deletion must go through OnboardingRouting.mayDeleteAccount.",
            body.contains("OnboardingRouting.mayDeleteAccount(")
        )
        // The old raw boolean gate.
        assertFalse(
            "The raw completeness boolean must not gate the cleanup any more.",
            body.contains("if (isProfileIncomplete(")
        )
    }

    // ── a failed check must never sign the user out ─────────────────────────

    /**
     * The old cold-start `catch` called `signOut()`, turning a timeout into a
     * logout. A failed check must fall back to the local record instead.
     */
    @Test
    fun aFailedProfileCheckNeverSignsTheUserOut() {
        val body = functionBody(viewModelCode, "private fun verifyProfileCompleteness(")
        assertTrue("verifyProfileCompleteness not found", body.isNotEmpty())

        val catchIndex = body.indexOf("} catch (e: Exception) {")
        assertTrue("expected a catch block", catchIndex >= 0)
        val catchBody = body.substring(catchIndex)

        assertFalse(
            "signOut() must not be reachable from the profile-check failure path.",
            catchBody.contains("signOut()")
        )
        assertTrue(
            "The failure path must fall back to the completion evidence, not a bare boolean.",
            catchBody.contains("hasCompletionEvidence(userId)")
        )
    }

    @Test
    fun theAbandonmentWipeIsTheOnlySignOutInThatFunction() {
        val body = functionBody(viewModelCode, "private fun verifyProfileCompleteness(")
        // Exactly one signOut: the confirmed-abandoned branch.
        val count = Regex("signOut\\(\\)").findAll(body).count()
        assertTrue(
            "Expected exactly one signOut() (the confirmed-abandonment wipe), found $count.",
            count == 1
        )
    }

    /**
     * The evidence predicate must fold both sources.
     *
     * Honouring only the local record leaves every account that onboarded before
     * that store existed (i.e. most of the reported population) unprotected on
     * their first start after the update.
     */
    @Test
    fun completionEvidenceFoldsTheLocalRecordAndMetadata() {
        val body = functionBody(viewModelCode, "private fun hasCompletionEvidence(")
        assertTrue("hasCompletionEvidence not found", body.isNotEmpty())
        assertTrue(
            "The per-user local record must count as evidence.",
            body.contains("OnboardingStateStore.isComplete(userId)")
        )
        assertTrue(
            "The session metadata must count as evidence too (pre-existing accounts).",
            body.contains("metadataProvesOnboarding()")
        )

        val metadataBody = functionBody(viewModelCode, "private fun metadataProvesOnboarding(")
        assertTrue("metadataProvesOnboarding not found", metadataBody.isNotEmpty())
        assertTrue(
            "Metadata evidence must read the handle the wizard wrote.",
            metadataBody.contains("stringOrNull(\"handle\")")
        )
        assertTrue(
            "Metadata evidence must read the username the wizard wrote.",
            metadataBody.contains("stringOrNull(\"username\")")
        )
        assertTrue(
            "The placeholder rules must live in OnboardingRouting, not be re-inlined.",
            metadataBody.contains("OnboardingRouting.metadataProvesOnboarding(")
        )
    }

    /**
     * The Google path must use the same predicate — not a local-only shortcut,
     * and not a fresh reimplementation.
     */
    @Test
    fun everyEntryPointSharesOneEvidencePredicate() {
        val code = viewModelCode
        assertTrue(
            "Google sign-in must defer to the shared evidence predicate.",
            code.contains("completionEvidence = hasCompletionEvidence(userId)")
        )
        assertFalse(
            "Google sign-in must not compute evidence by hand.",
            code.contains("completionEvidence = OnboardingStateStore.isComplete(userId)")
        )
    }

    // ── the write path proves itself ────────────────────────────────────────

    /**
     * Onboarding must not report success without confirming the row landed.
     *
     * `update` on a missing row is a silent success in Postgrest, which is how a
     * user could finish the wizard, reach Home, and be re-onboarded after a
     * restart.
     */
    @Test
    fun onboardingVerifiesTheProfileWriteInsteadOfAssumingIt() {
        val code = viewModelCode
        assertTrue(
            "saveOnboardingDetails must persist through persistProfileRow.",
            code.contains("persistProfileRow(")
        )
        assertTrue(
            "The write must be verified by reading the handle back.",
            code.contains("rowHasHandle(")
        )
        assertTrue(
            "A missing row must be created (INSERT), not silently skipped.",
            code.contains(".insert(values)")
        )
        assertTrue(
            "An unconfirmed write must fail loudly.",
            code.contains("ProfileWriteFailed(")
        )
        assertTrue(
            "The failure must be surfaced to the wizard, not swallowed.",
            code.contains("e is ProfileWriteFailed")
        )
    }

    @Test
    fun completingOnboardingRecordsItOnTheDevice() {
        assertTrue(
            "finishOnboarding must persist the completion.",
            functionBody(viewModelCode, "fun finishOnboarding(")
                .contains("OnboardingStateStore.markComplete(")
        )
        assertTrue(
            "The profile write must record completion as soon as it is confirmed.",
            viewModelCode.contains("OnboardingStateStore.markComplete(userId)")
        )
    }

    @Test
    fun signingOutClearsTheCompletionRecord() {
        val body = functionBody(viewModelCode, "fun signOut(")
        assertTrue("signOut not found", body.isNotEmpty())
        assertTrue(
            "The completion record is per-identity; sign-out must drop it.",
            body.contains("OnboardingStateStore.clear()")
        )
    }

    // ── metadata parity with Google ─────────────────────────────────────────

    @Test
    fun onboardingSyncsTheSameMetadataShapeAsGoogle() {
        val code = viewModelCode
        listOf("first_name", "last_name", "handle", "username", "full_name", "name").forEach {
            assertTrue(
                "Onboarding must write `$it` into user_metadata so manual and Google " +
                    "accounts have the same shape.",
                code.contains("put(\"$it\"")
            )
        }
        assertTrue(
            "Existing metadata must be copied through so updateUser{password} cannot " +
                "drop provider keys (picture/iss/sub).",
            code.contains("existingMetadata?.forEach")
        )
    }

    // ── the model no longer invents values ──────────────────────────────────

    /**
     * NULL columns must decode to null — not to the strings `"user"` / `"User"`.
     *
     * Those sentinels were doing double duty as database values, which is what made
     * "empty row" and "user called User" indistinguishable.
     */
    @Test
    fun theProfileModelDoesNotInventValues() {
        val model = source("app/src/main/java/net/agora/app/model/Profile.kt")
        assertFalse(
            "handle must not default to the \"user\" sentinel.",
            model.contains("val handle: String = \"user\"")
        )
        assertFalse(
            "firstName must not default to the \"User\" sentinel.",
            model.contains("firstName: String? = \"User\"")
        )
        assertTrue(
            "handle must be nullable.",
            model.contains("val handle: String? = null")
        )
        assertTrue(
            "firstName must be nullable with a null default.",
            model.contains("firstName: String? = null")
        )
    }

    // ── onboarding cannot be re-entered by Back or resume ───────────────────

    /**
     * The audit asked for `popUpTo(0) { inclusive = true }` when onboarding hands
     * off to Home. This shell has no auth `NavHost` — Create Your Profile is a
     * *branch* of `when (sessionStatus)`, not a destination — so the stack that
     * would have to be popped does not exist, and the branch disappearing from
     * composition is the same guarantee.
     *
     * What still has to be true, and is checked here, is that nothing composes
     * onboarding beside the feed: no second navigation host in the shell, and the
     * branch is chosen by the destination the ViewModel emits.
     */
    @Test
    fun onboardingIsABranchNotANavDestination() {
        val shell = codeOnly(source("app/src/main/java/net/agora/app/MainActivity.kt"))
        assertTrue(
            "The shell must select onboarding from the emitted destination.",
            shell.contains("val routeToOnboarding = authDestination")
        )
        assertTrue(
            "Onboarding must be composed in the session branch.",
            shell.contains("else if (routeToOnboarding)")
        )
        assertFalse(
            "Auth must not gain its own NavHost — that would create a stack Back " +
                "could return through.",
            shell.contains("rememberNavController") || shell.contains("NavHost(")
        )
        assertTrue(
            "A signed-out shell must drop the destination, so a stale Home/Onboarding " +
                "event cannot leak into the next identity.",
            shell.contains("authDestination = null")
        )
    }

    @Test
    fun finishingOnboardingLeavesTheWizardForGood() {
        val body = functionBody(viewModelCode, "fun finishOnboarding(")
        assertTrue("finishOnboarding not found", body.isNotEmpty())
        assertTrue(
            "The reactive onboarding flag must be cleared.",
            body.contains("_isOnboarding.value = false")
        )
        assertTrue(
            "And Home must be emitted explicitly.",
            body.contains("sendNav(AuthNavTarget.Home)")
        )
        assertTrue(
            "The cold-start check must not run again for this session.",
            body.contains("hasCompletedColdStartCheck = true")
        )
    }

    // ── the backend must allow the repair insert ────────────────────────────

    @Test
    fun theSelfInsertPolicyExists() {
        val dir = File(repoRoot(), "backend/supabase/migrations")
        val migration = dir.listFiles()
            ?.firstOrNull { it.name.contains("profiles_self_insert") }
            ?: throw AssertionError("The self-INSERT policy migration is missing.")
        val sql = migration.readText().lowercase()
        assertTrue("must create an INSERT policy", sql.contains("for insert"))
        assertTrue("must be scoped to the row's owner", sql.contains("auth.uid() = id"))
        assertTrue("must not be open to anonymous callers", sql.contains("to authenticated"))
    }
}

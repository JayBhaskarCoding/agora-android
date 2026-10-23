package net.agora.app.utils

import net.agora.app.utils.OnboardingRouting.Destination
import net.agora.app.utils.OnboardingRouting.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home-vs-onboarding decision, branch by branch.
 *
 * Every case here corresponds to a real reported or plausible failure:
 *
 *  - a finished account being sent back to "Create Your Profile" after a restart;
 *  - a finished account being **deleted** by the cold-start cleanup because a
 *    network hiccup looked like "no profile";
 *  - a genuinely new account skipping onboarding because a read failed "safe".
 *
 * Pure JVM. No Android, no Supabase, no Robolectric.
 */
class OnboardingRoutingTest {

    private fun verdict(
        rowPresent: Boolean = true,
        readFailed: Boolean = false,
        handle: String? = "ada",
        firstName: String? = "Ada",
        completionEvidence: Boolean = false
    ) = OnboardingRouting.verdict(rowPresent, readFailed, handle, firstName, completionEvidence)

    // ── the verdict ─────────────────────────────────────────────────────────

    @Test
    fun aRealUsernameMeansComplete() {
        assertEquals(Verdict.COMPLETE, verdict())
    }

    @Test
    fun aPlaceholderRowMeansOnboarding() {
        // The shape a fresh signup rows has: no handle, no name.
        assertEquals(
            Verdict.NEEDS_ONBOARDING,
            verdict(handle = null, firstName = null)
        )
        assertEquals(
            Verdict.NEEDS_ONBOARDING,
            verdict(handle = "", firstName = "")
        )
        assertEquals(
            Verdict.NEEDS_ONBOARDING,
            verdict(handle = "   ", firstName = "Ada")
        )
    }

    @Test
    fun legacyPlaceholderValuesAreStillDetected() {
        // Rows written by older clients.
        assertEquals(Verdict.NEEDS_ONBOARDING, verdict(handle = "user", firstName = "Ada"))
        assertEquals(Verdict.NEEDS_ONBOARDING, verdict(handle = "user_1234", firstName = "Ada"))
        assertEquals(Verdict.NEEDS_ONBOARDING, verdict(handle = "ada", firstName = "Pending"))
    }

    @Test
    fun aPlaceholderFirstNameAloneDoesNotBlockARealHandle() {
        // firstName "Pending" with a real handle is legacy-but-finished; the
        // placeholder first name is what marks it, so this stays onboarding.
        assertEquals(Verdict.NEEDS_ONBOARDING, verdict(handle = "ada", firstName = "Pending"))
    }

    @Test
    fun aFailedReadIsUnknownNeverOnboarding() {
        // ★ The bug: this used to be `false` (incomplete) -> onboarding, and at
        //   cold start "incomplete" also meant "delete the account".
        assertEquals(Verdict.UNKNOWN, verdict(readFailed = true))
        assertEquals(Verdict.UNKNOWN, verdict(readFailed = true, rowPresent = false))
        assertEquals(
            Verdict.UNKNOWN,
            verdict(readFailed = true, handle = null, firstName = null)
        )
    }

    @Test
    fun aFailedReadIsCompleteWhenTheDeviceSaysComplete() {
        // Evidence can only raise the verdict, never lower it. Saying UNKNOWN here
        // would be technically true about the *row* and actively harmful about the
        // *account*: one caller asking "is it COMPLETE?" would answer no, and that
        // `else` branch is the wizard.
        assertEquals(Verdict.COMPLETE, verdict(readFailed = true, completionEvidence = true))
    }

    @Test
    fun aMissingRowForANewAccountMeansOnboarding() {
        assertEquals(
            Verdict.NEEDS_ONBOARDING,
            verdict(rowPresent = false, handle = null, firstName = null)
        )
    }

    @Test
    fun aMissingRowForACompletedAccountIsUnknownNotOnboarding() {
        // ★ The restart bug in one assertion: the row exists, we just could not see
        //   it. A device that recorded completion must not be marched back through
        //   the wizard because of an empty result set.
        assertEquals(
            Verdict.UNKNOWN,
            verdict(rowPresent = false, handle = null, firstName = null, completionEvidence = true)
        )
    }

    @Test
    fun theLocalRecordBeatsALaggingPlaceholderRow() {
        // A write that has not landed yet, or a row read before the update
        // committed: the device already knows this account finished.
        assertEquals(
            Verdict.COMPLETE,
            verdict(handle = null, firstName = null, completionEvidence = true)
        )
    }

    @Test
    fun theLocalRecordDoesNotBeatTheDatabase() {
        // A real username is still complete when nothing is recorded locally —
        // a fresh install or a reinstall must not re-onboard an existing account.
        assertEquals(
            Verdict.COMPLETE,
            verdict(handle = "ada", firstName = "Ada", completionEvidence = false)
        )
    }

    // ── evidence from the session's own metadata ────────────────────────────

    @Test
    fun metadataProvesOnboardingOnlyWithARealHandle() {
        // Onboarding has always written handle/username into user_metadata;
        // sign-up does not. So this is proof, and it survives a failed read.
        assertTrue(OnboardingRouting.metadataProvesOnboarding("ada", null))
        assertTrue(OnboardingRouting.metadataProvesOnboarding(null, "ada"))
        assertTrue(OnboardingRouting.metadataProvesOnboarding("ada", "ada"))

        // Nothing written yet — a fresh signup, or a Google identity (which
        // carries full_name/name/picture and never a handle before onboarding).
        assertFalse(OnboardingRouting.metadataProvesOnboarding(null, null))
        assertFalse(OnboardingRouting.metadataProvesOnboarding(null, ""))
        assertFalse(OnboardingRouting.metadataProvesOnboarding("", "   "))

        // Placeholders are not evidence.
        assertFalse(OnboardingRouting.metadataProvesOnboarding("user", null))
        assertFalse(OnboardingRouting.metadataProvesOnboarding("user_1234", null))
        assertFalse(OnboardingRouting.metadataProvesOnboarding(null, "user_1234"))
    }

    @Test
    fun metadataEvidenceRescuesAnAccountWhoseRowCannotBeRead() {
        // ★ The upgrade population: accounts that onboarded on a build with no
        //   local record. Without this they are re-onboarded on the first flaky
        //   cold start after the update.
        assertEquals(
            Verdict.COMPLETE,
            verdict(readFailed = true, completionEvidence = true)
        )
        assertEquals(
            Verdict.COMPLETE,
            verdict(rowPresent = false, handle = null, firstName = null, completionEvidence = true)
        )
        assertEquals(
            Verdict.COMPLETE,
            verdict(handle = null, firstName = null, completionEvidence = true)
        )
    }

    @Test
    fun metadataEvidenceAlsoForbidsDeletion() {
        assertFalse(
            OnboardingRouting.mayDeleteAccount(
                verdict = Verdict.NEEDS_ONBOARDING,
                completionEvidence = true,
                recentlyVerified = false,
                sessionCreatedInThisRun = false
            )
        )
    }

    // ── the destination ─────────────────────────────────────────────────────

    @Test
    fun localCompletionAlwaysRoutesHome() {
        Verdict.entries.forEach { v ->
            assertEquals(
                "completionEvidence must win over $v",
                Destination.HOME,
                OnboardingRouting.destination(completionEvidence = true, verdict = v)
            )
        }
    }

    @Test
    fun onlyAConfirmedCompleteRowRoutesHomeWithoutALocalRecord() {
        assertEquals(Destination.HOME, OnboardingRouting.destination(false, Verdict.COMPLETE))
        assertEquals(Destination.ONBOARDING, OnboardingRouting.destination(false, Verdict.UNKNOWN))
        assertEquals(
            Destination.ONBOARDING,
            OnboardingRouting.destination(false, Verdict.NEEDS_ONBOARDING)
        )
    }

    // ── the deletion gate ───────────────────────────────────────────────────

    @Test
    fun onlyAConfirmedPlaceholderWithNoShieldMayBeDeleted() {
        assertTrue(
            OnboardingRouting.mayDeleteAccount(
                verdict = Verdict.NEEDS_ONBOARDING,
                completionEvidence = false,
                recentlyVerified = false,
                sessionCreatedInThisRun = false
            )
        )
    }

    @Test
    fun unknownNeverDeletes() {
        // ★ The most destructive interpretation of "I don't know" is deleting the
        //   user. It must be impossible.
        listOf(false, true).forEach { local ->
            assertFalse(
                OnboardingRouting.mayDeleteAccount(Verdict.UNKNOWN, local, false, false)
            )
        }
    }

    @Test
    fun completeNeverDeletes() {
        assertFalse(
            OnboardingRouting.mayDeleteAccount(Verdict.COMPLETE, false, false, false)
        )
    }

    @Test
    fun anyShieldPreventsDeletion() {
        assertFalse(
            "a locally completed account is never wiped",
            OnboardingRouting.mayDeleteAccount(Verdict.NEEDS_ONBOARDING, true, false, false)
        )
        assertFalse(
            "a recently verified account is never wiped",
            OnboardingRouting.mayDeleteAccount(Verdict.NEEDS_ONBOARDING, false, true, false)
        )
        assertFalse(
            "a session created in this run is never wiped",
            OnboardingRouting.mayDeleteAccount(Verdict.NEEDS_ONBOARDING, false, false, true)
        )
    }

    // ── handle classification ───────────────────────────────────────────────

    @Test
    fun placeholderDetection() {
        listOf(null, "", "   ", "user", "user_1234", "user_", " user ").forEach {
            assertTrue("'$it' must be a placeholder", OnboardingRouting.isPlaceholderHandle(it))
        }
        listOf("ada", "ada_l", "userrr", "user123", "User").forEach {
            assertFalse("'$it' is a real handle", OnboardingRouting.isPlaceholderHandle(it))
        }
    }

    @Test
    fun firstNamesAreCaseSensitivePlaceholders() {
        assertTrue(OnboardingRouting.isPlaceholderFirstName(null))
        assertTrue(OnboardingRouting.isPlaceholderFirstName(""))
        assertTrue(OnboardingRouting.isPlaceholderFirstName("Pending"))
        assertFalse(OnboardingRouting.isPlaceholderFirstName("Ada"))
        // A real person called "pending" in lower case is still a real name; the
        // sentinel was always the capitalised one written by the old signup path.
        assertFalse(OnboardingRouting.isPlaceholderFirstName("pending"))
    }
}

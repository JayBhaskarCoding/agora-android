package net.in.agora.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DateOfBirthTest {

    private val today = LocalDate.of(2026, 10, 19)

    @Test
    fun blankDobIsOptionalAndSubmittable() {
        assertEquals(DobValidation.Empty, DateOfBirth.validate(null, today))
        assertEquals(DobValidation.Empty, DateOfBirth.validate("   ", today))
        assertNull(DateOfBirth.validationError("", today))
    }

    @Test
    fun todayIsAllowedAndRepresentsAgeZero() {
        assertEquals(DobValidation.Valid, DateOfBirth.validate("2026-10-19", today))
        assertEquals(0, DateOfBirth.ageInYears(today, today))
        assertTrue(DateOfBirth.isMinor(today, today))
    }

    @Test
    fun exactlyOneHundredAndTwentyYearsAgoIsAllowed() {
        val oldest = today.minusYears(DateOfBirth.MAX_AGE_YEARS.toLong())
        assertEquals(DobValidation.Valid, DateOfBirth.validate(DateOfBirth.format(oldest), today))
        assertEquals(120, DateOfBirth.ageInYears(oldest, today))
        assertFalse(DateOfBirth.isMinor(oldest, today))
    }

    @Test
    fun oneDayOlderThanOneHundredAndTwentyYearsIsRejected() {
        val tooOld = today.minusYears(DateOfBirth.MAX_AGE_YEARS.toLong()).minusDays(1)
        assertEquals(
            DobValidation.OlderThanMaxAge,
            DateOfBirth.validate(DateOfBirth.format(tooOld), today)
        )
        assertEquals(
            "Date of birth can't be more than 120 years ago.",
            DateOfBirth.validationError(DateOfBirth.format(tooOld), today)
        )
    }

    @Test
    fun futureDatesAreRejected() {
        assertEquals(
            DobValidation.FutureDate,
            DateOfBirth.validate(DateOfBirth.format(today.plusDays(1)), today)
        )
        assertEquals(
            "Date of birth can't be in the future.",
            DateOfBirth.validationError("2026-10-20", today)
        )
    }

    @Test
    fun malformedAndImpossibleDatesAreRejected() {
        assertEquals(DobValidation.InvalidFormat, DateOfBirth.validate("19/10/2000", today))
        assertEquals(DobValidation.InvalidFormat, DateOfBirth.validate("2000-02-30", today))
        assertEquals(DobValidation.InvalidFormat, DateOfBirth.validate("2000-1-1", today))
    }

    @Test
    fun minorWindowIsZeroThroughSeventeen() {
        val seventeen = today.minusYears(17)
        val stillSeventeen = today.minusYears(18).plusDays(1)
        val eighteen = today.minusYears(18)

        assertEquals(17, DateOfBirth.ageInYears(seventeen, today))
        assertTrue(DateOfBirth.isMinor(seventeen, today))

        assertEquals(17, DateOfBirth.ageInYears(stillSeventeen, today))
        assertTrue(DateOfBirth.isMinor(stillSeventeen, today))

        assertEquals(18, DateOfBirth.ageInYears(eighteen, today))
        assertFalse(DateOfBirth.isMinor(eighteen, today))
    }

    @Test
    fun minorNoticeIsHiddenForBlankOrUnparsableDob() {
        // 🌟 The under-18 banner must never appear without a real date.
        assertFalse(DateOfBirth.shouldShowMinorNotice(null, today))
        assertFalse(DateOfBirth.shouldShowMinorNotice("", today))
        assertFalse(DateOfBirth.shouldShowMinorNotice("   ", today))
        assertFalse(DateOfBirth.shouldShowMinorNotice("not-a-date", today))
        assertFalse(DateOfBirth.shouldShowMinorNotice("2000-02-30", today))
        assertFalse(DateOfBirth.shouldShowMinorNotice("19/10/2015", today))
    }

    @Test
    fun minorNoticeIsHiddenForOutOfRangeAndAdultDates() {
        // Future and >120y values are invalid, so they are not "minor" either.
        assertFalse(DateOfBirth.shouldShowMinorNotice(DateOfBirth.format(today.plusDays(1)), today))
        assertFalse(
            DateOfBirth.shouldShowMinorNotice(
                DateOfBirth.format(today.minusYears(121)),
                today
            )
        )
        assertFalse(DateOfBirth.shouldShowMinorNotice(DateOfBirth.format(today.minusYears(18)), today))
        assertFalse(DateOfBirth.shouldShowMinorNotice(DateOfBirth.format(today.minusYears(40)), today))
    }

    @Test
    fun minorNoticeIsShownForAgesZeroThroughSeventeen() {
        assertTrue(DateOfBirth.shouldShowMinorNotice(DateOfBirth.format(today), today))
        assertTrue(DateOfBirth.shouldShowMinorNotice(DateOfBirth.format(today.minusYears(10)), today))
        assertTrue(DateOfBirth.shouldShowMinorNotice(DateOfBirth.format(today.minusYears(17)), today))
        // One day short of the 18th birthday is still a minor.
        assertTrue(
            DateOfBirth.shouldShowMinorNotice(
                DateOfBirth.format(today.minusYears(18).plusDays(1)),
                today
            )
        )
    }

    @Test
    fun validAgeIsNullForInvalidOrFutureValues() {
        assertNull(DateOfBirth.validAgeOrNull("", today))
        assertNull(DateOfBirth.validAgeOrNull("not-a-date", today))
        assertNull(DateOfBirth.validAgeOrNull(DateOfBirth.format(today.plusDays(1)), today))
        assertEquals(30, DateOfBirth.validAgeOrNull("1996-10-19", today))
    }

    @Test
    fun utcMillisRoundTripNeverShiftsTheDay() {
        val date = LocalDate.of(2000, 1, 1)
        val millis = DateOfBirth.toUtcMillis(date)
        assertEquals(date, DateOfBirth.fromUtcMillis(millis))
        assertEquals("2000-01-01", DateOfBirth.formatUtcMillis(millis))
    }
}

package com.example.agora.utils

import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Result of validating a Date of Birth value.
 *
 * Date of Birth is optional in Agora, so [Empty] is a valid, submittable state.
 * Everything except [Empty] and [Valid] blocks submission.
 */
sealed interface DobValidation {
    /** No date was provided. Allowed — the field is optional. */
    data object Empty : DobValidation

    /** A real calendar date inside the allowed 120-year window. */
    data object Valid : DobValidation

    /** Not an ISO `yyyy-MM-dd` date (e.g. `31/02/2000`). */
    data object InvalidFormat : DobValidation

    /** After today — future dates are never allowed. */
    data object FutureDate : DobValidation

    /** Before `LocalDate.now().minusYears(120)`. */
    data object OlderThanMaxAge : DobValidation

    val isSubmittable: Boolean
        get() = this is Empty || this is Valid
}

/**
 * 🌟 Single source of truth for Date of Birth rules.
 *
 * Allowed window (inclusive):
 *  - oldest: exactly 120 years before today (`LocalDate.now().minusYears(120)`)
 *  - youngest: today (`LocalDate.now()`) — future dates are rejected.
 *
 * Dates are stored and exchanged in strict ISO-8601 `yyyy-MM-dd` form; the
 * Material 3 picker works in UTC epoch millis, so the conversion helpers here
 * deliberately use [ZoneOffset.UTC] (never the device time zone) to avoid a
 * selected day shifting by one.
 */
object DateOfBirth {

    /** Oldest permitted age, in years. */
    const val MAX_AGE_YEARS = 120

    /** Minor threshold — ages `0..17` are minors. */
    const val ADULT_AGE_YEARS = 18

    private val ISO_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)

    /** Oldest selectable/valid DOB for [today]. */
    fun oldestAllowedDate(today: LocalDate = LocalDate.now()): LocalDate =
        today.minusYears(MAX_AGE_YEARS.toLong())

    /** Youngest selectable/valid DOB (today) — future dates are blocked. */
    fun newestAllowedDate(today: LocalDate = LocalDate.now()): LocalDate = today

    /** Parses a stored `yyyy-MM-dd` value, returning `null` for blank/malformed input. */
    fun parse(value: String?): LocalDate? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDate.parse(trimmed, ISO_DATE)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Formats a date as the strict `yyyy-MM-dd` value persisted in `profiles.dob`. */
    fun format(date: LocalDate): String = date.format(ISO_DATE)

    /** Validates [value] against the window anchored on [today]. */
    fun validate(value: String?, today: LocalDate = LocalDate.now()): DobValidation {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return DobValidation.Empty

        val date = try {
            LocalDate.parse(trimmed, ISO_DATE)
        } catch (_: DateTimeParseException) {
            return DobValidation.InvalidFormat
        }

        return when {
            date.isAfter(today) -> DobValidation.FutureDate
            date.isBefore(oldestAllowedDate(today)) -> DobValidation.OlderThanMaxAge
            else -> DobValidation.Valid
        }
    }

    /**
     * User-facing inline error for an invalid DOB, or `null` when the value is
     * blank/valid. Kept next to the validation logic so every screen shows the
     * exact same copy.
     */
    fun validationError(value: String?, today: LocalDate = LocalDate.now()): String? =
        when (validate(value, today)) {
            DobValidation.Empty, DobValidation.Valid -> null
            DobValidation.InvalidFormat -> "Enter a valid date of birth (YYYY-MM-DD)."
            DobValidation.FutureDate -> "Date of birth can't be in the future."
            DobValidation.OlderThanMaxAge ->
                "Date of birth can't be more than $MAX_AGE_YEARS years ago."
        }

    /**
     * Exact age in completed years, as required for the minor banner:
     * `Period.between(dob, today).years`.
     */
    fun ageInYears(dob: LocalDate, today: LocalDate = LocalDate.now()): Int =
        Period.between(dob, today).years

    /**
     * True when [dob] is a valid, non-future date producing an age in `0..17`.
     * This is informational only — it never blocks registration.
     */
    fun isMinor(dob: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        if (dob.isAfter(today)) return false
        return ageInYears(dob, today) in 0 until ADULT_AGE_YEARS
    }

    /**
     * Age from a stored DOB string, or `null` when the field is blank or the
     * value is malformed/out of range.
     *
     * 🌟 EMPTY MEANS UNKNOWN: a blank string, whitespace, or an unparsable value
     * returns `null`. That is what keeps the under-18 notice hidden for accounts
     * that never provided a Date of Birth — a blank value must never be treated
     * as "age 0".
     */
    fun validAgeOrNull(value: String?, today: LocalDate = LocalDate.now()): Int? {
        if (value.isNullOrBlank()) return null
        val date = parse(value) ?: return null
        return when (validate(value, today)) {
            DobValidation.Valid -> ageInYears(date, today)
            else -> null
        }
    }

    /**
     * True only when a VALID, non-empty DOB yields an age of 0..17.
     *
     * Single predicate shared by every screen so the banner cannot appear for a
     * `null`/blank/unparsable date, an out-of-range (future / >120y) date, or an
     * adult.
     */
    fun shouldShowMinorNotice(value: String?, today: LocalDate = LocalDate.now()): Boolean {
        val age = validAgeOrNull(value, today) ?: return false
        return age in 0 until ADULT_AGE_YEARS
    }

    /** Converts a [LocalDate] to the UTC start-of-day millis Material 3 expects. */
    fun toUtcMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Converts Material 3's UTC start-of-day millis back to a [LocalDate]. */
    fun fromUtcMillis(utcMillis: Long): LocalDate =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

    /** Formats Material 3's UTC millis directly into the persisted `yyyy-MM-dd`. */
    fun formatUtcMillis(utcMillis: Long): String = format(fromUtcMillis(utcMillis))
}

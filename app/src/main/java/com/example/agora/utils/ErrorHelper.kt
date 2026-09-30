package com.example.agora.utils

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 🌟 The single source of truth for user-facing error copy. Casual, calm and
 * consistent — raw backend exception text must NEVER reach the UI.
 */
object ErrorMessages {
    const val RATE_LIMITED = "Please wait a moment before trying again."
    const val INVALID_OTP = "That code doesn't look right, or it might have expired. Please try again."
    const val INVALID_CREDENTIALS = "We couldn't find an account with those details. Check your email and password."
    const val OFFLINE = "It looks like you're offline. Please check your connection."
    const val USERNAME_TAKEN = "That username is already taken! Try another one."
    const val GENERIC = "Something went wrong on our end. Please try again."
    const val SESSION_EXPIRED = "Your session has expired. Please sign in again."
    const val SERVER_HICCUP = "Our servers are having a moment. Please try again shortly."
    const val EMAIL_EXISTS = "An account with this email already exists. Please log in."
    const val WEAK_PASSWORD = "That password is a bit too weak. Try something longer."
    const val SAME_PASSWORD = "That's already your current password."
    const val EMAIL_NOT_CONFIRMED = "Your email isn't verified yet — check your inbox for the code."
    const val ACCOUNT_UNAVAILABLE = "This account is no longer available."
}

/**
 * 🌟 Centralized app-wide error mapper — intercepts Supabase/Ktor/JVM failures
 * and translates them into casual, user-friendly copy (see [ErrorMessages]).
 *
 * Classification is precise, per the guardrail — NO naive message sniffing:
 *  - network/offline  → exception TYPES (supabase-kt's HttpRequestException —
 *    the IOException wrapper thrown for every socket failure — plus raw
 *    UnknownHost/Connect/SocketTimeout and ktor's HttpRequestTimeoutException),
 *  - rate limits      → real HTTP status 429 ([RestException.statusCode]) AND
 *    the typed GoTrue send-limit [AuthErrorCode]s,
 *  - invalid/expired OTP, bad credentials, session expiry, weak password…
 *                     → the typed [AuthRestException.errorCode] enum,
 *  - unique violations → PostgreSQL SQLSTATE '23505' on [PostgrestRestException.code]
 *    (the app's only user-writable unique column is profiles.handle),
 *  - anything else    → status-code buckets, then the generic fallback.
 *
 * Callers MUST rethrow [kotlinx.coroutines.CancellationException] before
 * routing an exception through here (every ViewModel catch already does).
 */
fun handleAppError(e: Throwable): String {
    return when (e) {
        // ── Network / offline ────────────────────────────────────────────────
        is HttpRequestException,
        is HttpRequestTimeoutException,
        is UnknownHostException,
        is ConnectException,
        is SocketTimeoutException -> ErrorMessages.OFFLINE

        // ── Any Supabase REST failure (auth, postgrest, storage, realtime) ──
        is RestException -> mapRestException(e)

        // ── Everything else: never leak raw exception text ──────────────────
        else -> ErrorMessages.GENERIC
    }
}

private fun mapRestException(e: RestException): String {
    // 1) Rate limiting by the ACTUAL HTTP status — covers every plugin, e.g.
    //    Resend-OTP bursts and double-tapped "Delete Account" (GoTrue's
    //    reauthentication endpoint is send-rate limited too).
    if (e.statusCode == 429) return ErrorMessages.RATE_LIMITED

    // 2) GoTrue failures keyed on the typed error-code enum.
    if (e is AuthRestException) {
        when (e.errorCode) {
            AuthErrorCode.OverRequestRateLimit,
            AuthErrorCode.OverEmailSendRateLimit,
            AuthErrorCode.OverSmsSendRateLimit -> return ErrorMessages.RATE_LIMITED

            // GoTrue reports both wrong and out-of-window codes as otp_expired.
            AuthErrorCode.OtpExpired,
            AuthErrorCode.MfaChallengeExpired -> return ErrorMessages.INVALID_OTP

            AuthErrorCode.InvalidCredentials,
            AuthErrorCode.UserNotFound -> return ErrorMessages.INVALID_CREDENTIALS

            AuthErrorCode.EmailExists,
            AuthErrorCode.UserAlreadyExists -> return ErrorMessages.EMAIL_EXISTS

            AuthErrorCode.WeakPassword -> return ErrorMessages.WEAK_PASSWORD
            AuthErrorCode.SamePassword -> return ErrorMessages.SAME_PASSWORD
            AuthErrorCode.EmailNotConfirmed -> return ErrorMessages.EMAIL_NOT_CONFIRMED

            // Banned = the ghost accounts from the soft-deletion pipeline.
            AuthErrorCode.UserBanned -> return ErrorMessages.ACCOUNT_UNAVAILABLE

            AuthErrorCode.SessionExpired,
            AuthErrorCode.SessionNotFound,
            AuthErrorCode.RefreshTokenNotFound,
            AuthErrorCode.RefreshTokenAlreadyUsed -> return ErrorMessages.SESSION_EXPIRED

            // Unknown/new codes fall through to the status buckets below.
            else -> Unit
        }
    }

    // 3) Postgres unique-constraint violation, keyed on SQLSTATE not text.
    if (e is PostgrestRestException && e.code == "23505") return ErrorMessages.USERNAME_TAKEN

    // 4) Status-code buckets.
    return when (e.statusCode) {
        401, 403 -> ErrorMessages.SESSION_EXPIRED
        in 500..599 -> ErrorMessages.SERVER_HICCUP
        else -> ErrorMessages.GENERIC
    }
}

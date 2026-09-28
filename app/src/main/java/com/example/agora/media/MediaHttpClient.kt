package com.example.agora.media

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.agora.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single HTTP entry point for all remote media: the ExoPlayer upstream (via
 * [OkHttpDataSource]) and the Coil image loader both read their User-Agent from here.
 *
 * ### Why OkHttp and not `DefaultHttpDataSource`
 *
 * `DefaultHttpDataSource` builds on `HttpURLConnection`, which on Android is the
 * *platform's* repackaged OkHttp (`com.android.okhttp`). That stack reuses pooled
 * connections with no staleness detection and no retry, so a socket the server closed
 * while idle surfaces as:
 *
 * ```
 * java.io.EOFException: \n not found: size=0 content=...
 *   at com.android.okhttp.internal.http.Http1xStream.readResponse(Http1xStream.java:203)
 *     → ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001)
 * ```
 *
 * Note *zero* response bytes: nothing was parsed, no HTTP status existed, and the cache
 * never saw a byte (hence a black PlayerView). [OkHttpDataSource] fixes the transport
 * half of that on its own — OkHttp's `retryOnConnectionFailure` transparently retries an
 * idempotent request on a fresh connection when a pooled one turns out to be dead, and
 * it walks every resolved IP (IPv6 included) instead of only the first.
 */
@OptIn(UnstableApi::class)
object MediaHttpClient {

    private const val TAG = "MediaHttp"

    /**
     * Browser User-Agent used for every outbound media request.
     *
     * Catbox (`files.catbox.moe`) rejects Media3's default `ExoPlayerLib/<version>` UA and
     * Coil's `okhttp/x.y.z` UA with a 403 + HTML block page, so both share this value.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Host that [diagnoseOnFailure] knows an alternate route for. */
    private const val CATBOX_HOST = "files.catbox.moe"

    /**
     * Second opinion host used *only* by the diagnostic probe, never for playback.
     *
     * `files.pixstash.moe` is a community passthrough to the same Catbox storage. If the
     * primary host fails but this one answers, the phone's network — not the app, and not
     * the User-Agent — is dropping `files.catbox.moe`.
     */
    private const val DIAGNOSTIC_ALTERNATE_HOST = "files.pixstash.moe"

    /**
     * Opt-in rewrites applied to *every* upstream media request, e.g.
     * `mapOf("files.catbox.moe" to "files.pixstash.moe")`.
     *
     * Deliberately empty: routing user media through a third-party mirror is a product and
     * privacy decision, not something a library should do behind your back. Turn it on only
     * once [diagnoseOnFailure] has proven the primary host is blocked on your network — and
     * prefer pointing it at a relay you control (e.g. a Supabase Edge Function that streams
     * the file through your own domain) over someone else's mirror.
     */
    private val HOST_REWRITES: Map<String, String> = emptyMap()

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val PROBE_TIMEOUT_SECONDS = 10L
    private const val PROBE_BODY_BYTES = 512L
    private const val DIAGNOSTIC_DEBOUNCE_MS = 30_000L

    /**
     * The client behind all media traffic.
     *
     * Note the deliberate absence of `callTimeout`: it bounds the *entire* call including
     * body reads, which would kill long progressive downloads mid-stream.
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Retry idempotent requests on a fresh connection when a pooled one is dead.
            // This is the fix for the EOF-before-headers failure above; it is also the
            // default, but stated explicitly because the whole design leans on it.
            .retryOnConnectionFailure(true)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(browserHeaderInterceptor())
            .build()
    }

    /** Snappier client used only by the failure probe, where a quick answer matters more. */
    private val probeClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Upstream factory for [VideoCache]'s `CacheDataSource`, so cache misses and
     * [VideoPreloader]'s writes both go through [okHttpClient].
     *
     * OkHttp follows cross-protocol (http → https) redirects itself via `followSslRedirects`,
     * so no extra flag is needed here as it would be on `DefaultHttpDataSource`.
     */
    fun dataSourceFactory(): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(okHttpClient).setUserAgent(USER_AGENT)
        return if (HOST_REWRITES.isEmpty()) {
            httpFactory
        } else {
            HostRewriteDataSourceFactory(httpFactory, HOST_REWRITES)
        }
    }

    /**
     * Defensive UA header: [OkHttpDataSource] already sets it, but this guarantees the value
     * for any other caller sharing this client (and for [probe]).
     */
    private fun browserHeaderInterceptor(): Interceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()

            if (!BuildConfig.DEBUG) return chain.proceed(request)

            val startedAt = SystemClock.elapsedRealtime()
            try {
                val response = chain.proceed(request)
                Log.d(
                    TAG,
                    "${request.method} ${request.url} → HTTP ${response.code} " +
                        "(${response.protocol}) in ${SystemClock.elapsedRealtime() - startedAt}ms"
                )
                response
            } catch (e: IOException) {
                // IOException here means no usable response at all: DNS, TCP, TLS or a
                // peer that closed the socket. Same signature as the reported crash.
                Log.e(
                    TAG,
                    "${request.method} ${request.url} → no response after " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                    e
                )
                throw e
            }
        }
    }

    /**
     * Runs a one-shot network probe for [url] on a background thread, at most once per
     * [DIAGNOSTIC_DEBOUNCE_MS] per URL.
     *
     * A player error alone cannot tell "the file host is dropping us" from "the app is
     * misconfigured" — both end as `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`. The probe
     * answers it in Logcat: DNS result, HTTP status, protocol, elapsed time, and the same
     * test against [DIAGNOSTIC_ALTERNATE_HOST] for comparison.
     */
    fun diagnoseOnFailure(url: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastDiagnosticAt[url]
        if (previous != null && now - previous < DIAGNOSTIC_DEBOUNCE_MS) return
        lastDiagnosticAt[url] = now
        // Best-effort bounds; the map is only ever touched from the player thread.
        if (lastDiagnosticAt.size > 32) lastDiagnosticAt.clear()

        scope.launch { runDiagnostics(url) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastDiagnosticAt = ConcurrentHashMap<String, Long>()

    private suspend fun runDiagnostics(url: String) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return@withContext
        Log.e(TAG, "── playback failed for $url — probing the network path ──")
        logDnsResolution(host)

        val primary = probe(url)
        Log.e(TAG, "probe[$host] → $primary")

        val alternateHost =
            if (host == CATBOX_HOST) DIAGNOSTIC_ALTERNATE_HOST else HOST_REWRITES[host]
        val alternate = alternateHost?.let { alternateHost ->
            val alternateUrl = uri.buildUpon().authority(alternateHost).build().toString()
            probe(alternateUrl).also { Log.e(TAG, "probe[$alternateHost] → $it") }
        }

        Log.e(TAG, conclusionFor(host, primary, alternateHost, alternate))
    }

    private fun logDnsResolution(host: String) {
        try {
            val addresses = InetAddress.getAllByName(host)
            val rendered = addresses.joinToString { address ->
                val family = if (address is Inet6Address) "IPv6" else "IPv4"
                "${address.hostAddress} ($family)"
            }
            Log.e(TAG, "DNS $host → $rendered")
        } catch (e: IOException) {
            // A failing lookup means no HTTP request was ever attempted; packet-level
            // blocking or a dead resolver, not a User-Agent problem.
            Log.e(
                TAG,
                "DNS $host → FAILED (${e.javaClass.simpleName}: ${e.message}) — the request " +
                    "never left the device; this is a DNS-level block or outage.",
                e
            )
        }
    }

    /** Blocking single request; always called from [Dispatchers.IO]. */
    private fun probe(url: String): String {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-0")
                .build()
            probeClient.newCall(request).execute().use { response ->
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val snippet = runCatching {
                    response.peekBody(PROBE_BODY_BYTES).string().replace(WHITESPACE, " ").take(120)
                }.getOrDefault("")
                "HTTP ${response.code} (${response.protocol}) in ${elapsed}ms — " +
                    "content-type=${response.header("Content-Type")}, " +
                    "content-length=${response.header("Content-Length")}, " +
                    "server=${response.header("Server")}, body=\"$snippet\""
            }
        } catch (e: IOException) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val cause = e.cause?.let { " (cause=${it.javaClass.simpleName}: ${it.message})" }.orEmpty()
            "FAILED after ${elapsed}ms — ${e.javaClass.simpleName}: ${e.message}$cause"
        }
    }

    private fun conclusionFor(
        host: String,
        primary: String,
        alternateHost: String?,
        alternate: String?
    ): String = when {
        alternate == null ->
            if (primary.startsWith("HTTP")) {
                "DIAGNOSIS: $host answers now (${primary.take(40)}…) — the failed play was most " +
                    "likely a stale pooled connection or a momentary network drop; OkHttp retries " +
                    "these automatically. If it recurs, look at the error code, not the host."
            } else {
                "DIAGNOSIS: $host is unreachable from this network (no HTTP response at all). " +
                    "Check mobile data vs Wi-Fi and any VPN/captive portal, then compare with a " +
                    "browser on the same device."
            }

        primary.startsWith("HTTP") ->
            "DIAGNOSIS: $host is reachable — the failure was transport-level, not host-level. " +
                "The next player error should survive OkHttp's retry."

        alternate.startsWith("HTTP") ->
            "DIAGNOSIS: $host is blocked on this network but $alternateHost works — this is an " +
                "ISP/DNS-level block of $host, not a User-Agent or app bug. Fixes, in order of " +
                "preference: (1) relay media through your own backend/domain, (2) enable " +
                "HOST_REWRITES in MediaHttpClient, (3) let the user use a VPN or DNS-over-HTTPS."

        else ->
            "DIAGNOSIS: neither $host nor $alternateHost responded — the network path itself is " +
                "broken (airplane mode, captive portal, dead DNS, or an aggressive middlebox). " +
                "Retry on a different network to confirm."
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Wraps an upstream factory and rewrites the host of each [DataSpec] before opening it.
     * Used only when [HOST_REWRITES] is non-empty; the cache key follows the rewritten URI,
     * so cached bytes always correspond to the host they came from.
     */
    private class HostRewriteDataSourceFactory(
        private val delegate: DataSource.Factory,
        private val rewrites: Map<String, String>
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource = RewritingDataSource(delegate.createDataSource())

        private inner class RewritingDataSource(private val delegate: DataSource) : DataSource {

            override fun open(dataSpec: DataSpec): Long {
                val uri = dataSpec.uri
                val host = uri.host
                val replacement = host?.let { rewrites[it] } ?: return delegate.open(dataSpec)
                return delegate.open(dataSpec.withUri(uri.buildUpon().authority(replacement).build()))
            }

            override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int =
                delegate.read(buffer, offset, readLength)

            override fun addTransferListener(transferListener: TransferListener) =
                delegate.addTransferListener(transferListener)

            override fun getUri(): Uri? = delegate.uri

            override fun close() = delegate.close()
        }
    }
}

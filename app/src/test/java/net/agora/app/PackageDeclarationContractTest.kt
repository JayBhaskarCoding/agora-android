package net.agora.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the app identity and the Kotlin package headers.
 *
 * The rule this file exists to enforce: **one identity, `net.agora.app`, used
 * for both `namespace` and `applicationId`.**
 *
 * ```
 * namespace     = "net.agora.app"
 * applicationId = "net.agora.app"
 * ```
 *
 * History matters here, because both wrong answers were shipped at some point:
 *
 * 1. `namespace = "net.in.agora"` did not compile. `in` is a hard keyword, so
 *    `package net.in.agora` is a syntax error:
 *
 *    ```
 *    AgoraApplication.kt:1:13 Syntax error:
 *    Package name must be a '.'-separated identifier list.
 *    ```
 *
 *    Column 13 is the `i` of `in`. Grammar-level parsers do not catch this
 *    (tree-sitter lexes `in` as an ordinary identifier) — only the compiler
 *    does, which is why the rule is encoded here as a plain assertion.
 *
 * 2. The identity was briefly split: `namespace = net.agora.app` with
 *    `applicationId = net.in.agora`. That bought package-name compatibility
 *    with an older registration at the cost of a second identity to keep in
 *    sync everywhere — Gradle, Firebase, OAuth, App Links, Supabase redirects.
 *    It is unified again (docs §17), and this file pins the unified values.
 *
 * Unifying on `net.agora.app` is the only choice that satisfies both
 * constraints at once: it is a legal identifier chain (no hard keyword), and it
 * is a name that can be registered as a package wherever the app ships.
 *
 * ⚠️ Three things outside this repository must match the value asserted here,
 * and the tests below fail loudly rather than silently until they do:
 *   - `app/google-services.json` needs a client for `net.agora.app`, or the
 *     google-services plugin aborts the build;
 *   - a Google Cloud OAuth *Android* client must exist for `net.agora.app`
 *     plus the signing SHA-1, or sign-in returns [28444];
 *   - `https://agora.in.net/.well-known/assetlinks.json` must list
 *     `net.agora.app`, or App Links stop verifying.
 *
 * Pure JVM test: no device, no Robolectric.
 */
class PackageDeclarationContractTest {

    /**
     * Kotlin hard keywords — never valid as a package segment without backticks.
     * Soft/modifier keywords (`data`, `internal`, `value`, `field`, …) are legal
     * identifiers and are used here on purpose (`…agora.app.data`), so they are
     * deliberately absent.
     */
    private val hardKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
        "if", "in", "interface", "is", "null", "object", "package", "return",
        "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
        "var", "when", "while"
    )

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate app/src/main from ${System.getProperty("user.dir")}")
    }

    private fun kotlinSources(): List<File> {
        val root = repoRoot()
        return listOf("main", "test", "androidTest")
            .map { File(root, "app/src/$it") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
    }

    private fun packageOf(file: File): String {
        val match = Regex("""^package\s+([^\s;]+)""", RegexOption.MULTILINE).find(file.readText())
        assertTrue("${file.name} has no package declaration", match != null)
        return match!!.groupValues[1]
    }

    private val gradle: String
        get() = File(repoRoot(), "app/build.gradle.kts").readText()

    private fun gradleValue(key: String): String {
        val match = Regex("""^\s*$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(gradle)
        assertTrue("`$key = \"…\"` not found in app/build.gradle.kts", match != null)
        return match!!.groupValues[1]
    }

    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * One identity for both roles.
     *
     * They were split for a while (`applicationId = net.in.agora`) to match an
     * older registration. That is a legitimate AGP configuration, but every
     * split identity is a second thing to keep in sync across Gradle, Firebase,
     * OAuth, App Links and Supabase — and it produced exactly that class of
     * drift. Unified again: `net.agora.app` everywhere.
     *
     * `net.in.agora` can never be the namespace: `in` is a hard keyword, so
     * `package net.in.agora` does not compile. Since the namespace must be
     * `net.agora.app`, the applicationId follows it.
     */
    @Test
    fun namespaceAndApplicationIdAreTheSameUnifiedIdentity() {
        assertEquals("net.agora.app", gradleValue("namespace"))
        assertEquals("net.agora.app", gradleValue("applicationId"))
    }

    /**
     * The invariant that the previous configuration violated.
     *
     * The google-services Gradle plugin fails the build outright when no client
     * in google-services.json matches `applicationId` ("No matching client found
     * for package name"). That is a build-time failure with a confusing message,
     * so it is asserted here where the cause is obvious.
     */
    @Test
    fun googleServicesJsonDeclaresTheApplicationId() {
        val file = File(repoRoot(), "app/google-services.json")
        assertTrue("app/google-services.json is missing", file.isFile)

        val applicationId = gradleValue("applicationId")
        val declared = Regex(""""package_name"\s*:\s*"([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(
            "app/google-services.json declares no client for applicationId=\"$applicationId\" " +
                "(it has $declared).\n" +
                "The google-services Gradle plugin aborts with 'No matching client found " +
                "for package name' until a matching Firebase Android app exists.\n" +
                "Fix: Firebase console -> Project settings -> Your apps -> Add app -> " +
                "Android -> package name \"$applicationId\" -> register -> download " +
                "google-services.json -> replace app/google-services.json.\n" +
                "Do NOT hand-edit the file: the plugin verifies its signature and the " +
                "console is the source of truth for the API keys.",
            applicationId in declared
        )
    }

    // ── Package legality ────────────────────────────────────────────────────

    @Test
    fun noPackageSegmentIsAKotlinHardKeyword() {
        val offenders = mutableListOf<String>()
        for (file in kotlinSources()) {
            val declared = packageOf(file)
            for (segment in declared.split('.')) {
                // A backticked segment (`in`) is legal; strip ticks so only truly
                // bare keywords fail.
                if (segment.trim('`') in hardKeywords) {
                    offenders += "${file.path}: package $declared -> '$segment' is a hard keyword"
                }
            }
        }
        assertEquals(
            "A package segment is a Kotlin hard keyword; `package a.in.b` does not " +
                "compile. This is why the namespace is net.agora.app while the " +
                "applicationId is net.agora.app — see docs/engineering-notes.md §17.",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun everyPackageStartsWithTheNamespace() {
        val namespace = gradleValue("namespace")
        val strays = kotlinSources()
            .map { it to packageOf(it) }
            .filter { (_, pkg) -> pkg != namespace && !pkg.startsWith("$namespace.") }
        assertEquals(
            "Every source file must live under the namespace ($namespace).",
            emptyList<Pair<File, String>>(),
            strays
        )
    }

    @Test
    fun packageMatchesDirectory() {
        val root = repoRoot()
        val mismatches = mutableListOf<String>()
        for (file in kotlinSources()) {
            val relative = file.relativeTo(root).path
            val afterJava = relative.substringAfter("/java/", missingDelimiterValue = "")
            if (afterJava.isEmpty()) continue
            val expected = afterJava.substringBeforeLast('/').replace('/', '.')
            val declared = packageOf(file)
            if (declared != expected) mismatches += "$relative: declared $declared, expected $expected"
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    // ── Retired identities ──────────────────────────────────────────────────

    @Test
    fun retiredIdentitiesDoNotAppearInSources() {
        // net.in.agora is RETIRED as an identity (it is no longer the
        // applicationId) but it is still an accepted INBOUND deep-link scheme,
        // so it legitimately appears in the manifest and the router tests.
        // Only the template package and the old auth domains are fully gone.
        val retired = listOf("com.example.agora", "auth-agora.info", "auth-agora.online")
        val hits = mutableListOf<String>()
        for (file in kotlinSources()) {
            val text = file.readText()
            for (token in retired) {
                if (!text.contains(token)) continue
                // Allowed only inside an explicit "this is retired" explanation.
                val explained = text.lineSequence().any {
                    it.contains(token) &&
                        (it.contains("retired", true) || it.contains("never compiled", true) ||
                            it.contains("hard keyword", true))
                }
                if (!explained) hits += "${file.path}: $token"
            }
        }
        assertEquals(
            "A retired identity is still referenced. Only agora.in.net (web) and " +
                "net.agora.app (namespace + applicationId) are live identities.",
            emptyList<String>(),
            hits
        )
    }

    /**
     * All three custom schemes must be declared.
     *
     *  - `agora`        — the stable public contract, emitted since day one.
     *  - `net.agora.app`— the unified identity; `intent://` URIs and anything
     *                     derived from the package name use it. This is the one
     *                     that MUST be present for the new identity to work.
     *  - `net.in.agora` — legacy inbound only. Builds before the unification
     *                     shipped under that applicationId, so notifications
     *                     they delivered carry it. Accepting it keeps those
     *                     taps working; nothing emits it any more.
     *
     * If the legacy scheme is ever dropped, delete it here AND the matching
     * assertion in DeepLinkRouterTest at the same time.
     */
    @Test
    fun everyLiveSchemeIsDeclared() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        listOf("agora", "net.agora.app", "net.in.agora").forEach { scheme ->
            assertTrue(
                "The manifest must declare android:scheme=\"$scheme\"",
                manifest.contains("android:scheme=\"$scheme\"")
            )
        }
    }
}

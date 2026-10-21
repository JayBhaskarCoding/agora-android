package net.agora.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the app identity and the Kotlin package headers.
 *
 * The rule this file exists to enforce: **one namespace, one applicationId,
 * and they are deliberately different strings.**
 *
 * ```
 * namespace     = "net.agora.app"   // Kotlin package, R, BuildConfig
 * applicationId = "net.in.agora"    // device identity, Play, Firebase, OAuth
 * ```
 *
 * The split is forced from both sides:
 *
 * 1. `applicationId` cannot be `net.agora.app`: no Android OAuth client, no
 *    entry in `google-services.json`, and no `package_name` in the live
 *    `/.well-known/assetlinks.json` is registered under it, so Play App Links
 *    and Google Sign-In both fail closed (status 16 / `DEVELOPER_ERROR`).
 *
 * 2. `namespace` cannot be `net.in.agora`: `in` is a hard keyword, so
 *    `package net.in.agora` is a syntax error —
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
 * AGP supports the split natively: `namespace` only feeds R/BuildConfig and the
 * manifest's relative class names, while `applicationId` is what ends up in the
 * APK manifest, on the device, in Play, and in the Digital Asset Links
 * statement. A previous revision of this file asserted the two were equal, back
 * when the namespace still sat at the template default `com.example.agora` —
 * that assertion is gone because the premise was wrong.
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
     * `namespace` and `applicationId` are deliberately DIFFERENT values:
     *
     *  - `namespace` = net.agora.app — the Kotlin package, which must be a legal
     *    identifier chain. `net.in.agora` is not: `in` is a hard keyword, so
     *    `package net.in.agora` does not compile.
     *  - `applicationId` = net.in.agora — the shipped identity. It is what
     *    google-services.json, the Google OAuth Android client and
     *    /.well-known/assetlinks.json are all registered against.
     *
     * AGP treats them independently on purpose: namespace controls R,
     * BuildConfig and relative manifest class names, applicationId controls the
     * device/Play/Firebase identity.
     */
    @Test
    fun namespaceIsTheLegalPackageAndApplicationIdIsTheShippedIdentity() {
        assertEquals("net.agora.app", gradleValue("namespace"))
        assertEquals("net.in.agora", gradleValue("applicationId"))
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
            "google-services.json declares no client for applicationId=" +
                "\"$applicationId\" (it has $declared). The google-services plugin " +
                "fails the build until a matching Firebase Android app exists.",
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
                "applicationId is net.in.agora — see docs/engineering-notes.md §16.",
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
        // net.in.agora is NOT retired: it is the applicationId. Only the
        // template package and the old auth domains are gone.
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
            "A retired identity is still referenced. Only agora.in.net (web), " +
                "net.in.agora (applicationId) and net.agora.app (namespace) are live.",
            emptyList<String>(),
            hits
        )
    }

    /**
     * All three custom schemes must be declared. `net.in.agora` is the
     * applicationId (intent:// URIs and package-derived links use it),
     * `net.agora.app` is the namespace (links minted while the two identities
     * were unified), and `agora` is what the app itself emits.
     */
    @Test
    fun everyLiveSchemeIsDeclared() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        listOf("agora", "net.in.agora", "net.agora.app").forEach { scheme ->
            assertTrue(
                "The manifest must declare android:scheme=\"$scheme\"",
                manifest.contains("android:scheme=\"$scheme\"")
            )
        }
    }
}

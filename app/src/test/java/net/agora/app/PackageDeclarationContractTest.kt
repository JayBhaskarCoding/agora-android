package net.agora.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the app identity and the Kotlin package headers.
 *
 * The rule this file exists to enforce: **`namespace` and `applicationId` are the
 * same string, and that string is a legal Kotlin package.**
 *
 * Both halves have already been broken once in opposite directions:
 *
 * 1. `namespace = "net.in.agora"` shipped in a commit and did not compile —
 *    `in` is a hard keyword, so `package net.in.agora` is a syntax error:
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
 * 2. The namespace sat at the template default `com.example.agora` for a while,
 *    which is unpublishable-looking and diverged from the app id.
 *
 * `net.agora.app` satisfies both constraints: legal identifiers, no hard
 * keyword, and a single identity across Gradle, R8, the manifest and OAuth.
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

    @Test
    fun namespaceAndApplicationIdAreIdentical() {
        val namespace = gradleValue("namespace")
        val applicationId = gradleValue("applicationId")
        assertEquals(
            "namespace and applicationId must be the same string — one identity " +
                "everywhere (Gradle, R8 rules, manifest, OAuth client, adb).",
            namespace,
            applicationId
        )
    }

    @Test
    fun identityIsNetAgoraApp() {
        assertEquals("net.agora.app", gradleValue("namespace"))
        assertEquals("net.agora.app", gradleValue("applicationId"))
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
                "compile. This is why the identity is net.agora.app and not " +
                "net.in.agora — see docs/engineering-notes.md §9.",
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
        val retired = listOf("com.example.agora", "net.in.agora")
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
                "net.agora.app (app) are live.",
            emptyList<String>(),
            hits
        )
    }

    @Test
    fun noRetiredSchemeIsDeclared() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "The net.in.agora:// scheme is retired; use net.agora.app://",
            !manifest.contains("android:scheme=\"net.in.agora\"")
        )
        assertTrue(
            "The manifest must declare the app-identity scheme",
            manifest.contains("android:scheme=\"net.agora.app\"")
        )
    }
}

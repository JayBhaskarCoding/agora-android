package com.example.agora

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Kotlin package headers themselves.
 *
 * This exists because of a real defect: the applicationId is `net.in.agora`, and
 * the namespace was changed to match it. Everything looked fine — tree-sitter
 * parsed all 98 files, the manifest was valid, the router tests passed — and the
 * build still died on the first line of the first file:
 *
 * ```
 * AgoraApplication.kt:1:13 Syntax error:
 * Package name must be a '.'-separated identifier list.
 * ```
 *
 * `in` is a **hard keyword** in Kotlin, so `package net.in.agora` cannot parse
 * and neither can `import net.in.agora.…`. (Java accepts the same name, which is
 * why the applicationId is perfectly legal — the constraint is Kotlin's, and it
 * only applies to the Kotlin-visible package.) Grammar-level parsers do not
 * enforce keyword rules, so no amount of syntax checking on source text catches
 * it; only the compiler does. These assertions encode the rule cheaply.
 *
 * Pure JVM test: no device, no Robolectric.
 */
class PackageDeclarationContractTest {

    /**
     * Kotlin hard keywords — never valid as a package segment without backticks.
     * Soft keywords (`data`, `internal`, `value`, …) are legal identifiers and
     * are used as package segments in this project (`…agora.data`), so they are
     * deliberately absent from this list.
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
        val match = Regex("""^package\s+([^\s;]+)""", RegexOption.MULTILINE)
            .find(file.readText())
        assertTrue("${file.name} has no package declaration", match != null)
        return match!!.groupValues[1]
    }

    @Test
    fun noPackageSegmentIsAKotlinHardKeyword() {
        val offenders = mutableListOf<String>()
        for (file in kotlinSources()) {
            val declared = packageOf(file)
            for (segment in declared.split('.')) {
                // A backticked segment (`in`) is legal; strip the ticks before
                // comparing so only truly bare keywords fail.
                val bare = segment.trim('`')
                if (bare in hardKeywords) {
                    offenders += "${file.path}: package $declared -> '$bare' is a hard keyword"
                }
            }
        }
        assertEquals(
            "A package segment is a Kotlin hard keyword; `package a.in.b` does not " +
                "compile. The applicationId may contain it (net.in.agora) — the " +
                "namespace may not. See docs/engineering-notes.md §9.",
            emptyList<String>(),
            offenders
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
            if (declared != expected) {
                mismatches += "$relative: declared $declared, expected $expected"
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    /**
     * The applicationId and the namespace are allowed to differ, and do. Pinning
     * the intent here means a future "make them match" refactor fails loudly in
     * `./gradlew test` instead of at the first line of the first file.
     */
    @Test
    fun appIdIsNetInAgoraAndNamespaceCannotBe() {
        val gradle = File(repoRoot(), "app/build.gradle.kts").readText()
        assertTrue(
            "applicationId must stay net.in.agora",
            gradle.contains("""applicationId = "net.in.agora"""")
        )
        assertTrue(
            "namespace must NOT be net.in.agora: `in` is a Kotlin hard keyword",
            !gradle.contains("""namespace = "net.in.agora"""")
        )
    }
}

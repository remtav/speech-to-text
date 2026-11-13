package io.github.remtav.dictee

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Android's regex engine rejects some syntax the JVM accepts, so a pattern can pass every host test and still
 * fail to compile on the phone (issue #49: `(?U)` broke every Whisper decode in « Automatique »). This scans
 * the app sources for the constructs known to differ.
 */
class AndroidRegexGuardTest {
    private val forbidden = listOf("(?U)", "UNICODE_CHARACTER_CLASS")

    @Test fun mainSourcesUseNoRegexSyntaxThatAndroidRejects() {
        val root = File("src/main/java")
        check(root.isDirectory) { "run from the app module: ${root.absolutePath}" }
        val hits = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                forbidden.firstOrNull { it in code }?.let { "${file.path}:${i + 1}: $it" }
            }
        }.toList()
        assertEquals("regex syntax Android rejects", emptyList<String>(), hits)
    }
}

package com.themoon.y1.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ZipFile and JarFile are Closeable only from API 19: `ZipFile(f).use {}` stops with an Error on the Y1 (Android 4.2),
 * and only there. The main sources must use [useZip].
 */
class UseZipSourceTest {

    private val zipUse = Regex("""\b(?:ZipFile|JarFile)\((?:[^()]|\([^()]*\))*\)\s*\.use\s*\{""")

    /** The calls in [code] that [zipUse] finds, in code lines only: a comment can name the call that fails. */
    private fun zipUses(code: String): List<String> {
        val lines = code.lines().filter { line -> listOf("*", "/*", "//").none { line.trimStart().startsWith(it) } }
        return zipUse.findAll(lines.joinToString("\n")).map { it.value.replace(Regex("\\s+"), " ") }.toList()
    }

    @Test
    fun theRuleFindsUse() {
        assertEquals(1, zipUses("JarFile(apk, true).use { jar -> signersOf(jar) }").size)
        assertEquals(1, zipUses("java.util.zip.ZipFile(File(dir, name)).use {").size)
        assertEquals(1, zipUses("JarFile(apk, true)\n    .use { jar -> signersOf(jar) }").size)
        assertEquals(0, zipUses("ZipFile(file).useZip { zip -> }").size)
        assertEquals(0, zipUses(" * `ZipFile(f).use {}` fails before API 19").size)
    }

    @Test
    fun mainSourcesDoNotCallUseOnAZipFile() {
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val found = dir.walk().filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> zipUses(file.readText()).map { "${file.name}: $it" } }.toList()
        assertEquals("use useZip, not use, on a ZipFile or a JarFile", emptyList<String>(), found)
    }
}

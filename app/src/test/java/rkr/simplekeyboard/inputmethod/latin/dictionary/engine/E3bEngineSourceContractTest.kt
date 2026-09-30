package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The engine keeps the keyboard layout as data: neither the letters nor the key pairs are
 * hard-coded in it. The long-press adjacency is derived by a pure function of numbers supplied by
 * the live layout through `KeyNeighborTableBuilder`, which is the single place layout data crosses
 * into the engine.
 *
 * This test asserts the engine sources — and that single crossing — carry no Cyrillic literal at
 * all, so a hard-coded letter or key pair cannot slip in unseen.
 */
class E3bEngineSourceContractTest {
    @Test
    fun engineSourcesCarryNoCyrillicLiteralOrHardCodedKeyPair() {
        val files = engineSources()
        assertTrue("engine sources not found", files.isNotEmpty())
        for (file in files) {
            // Only literals matter: KDoc/comments legitimately describe the layout in Cyrillic, but
            // no string or char literal in code may hard-code a letter or key pair.
            val code = stripComments(file.readText())
            val cyrillic = code.filter { it in '\u0400'..'\u04FF' }
            assertTrue(
                "${file.name} carries a Cyrillic literal (\"$cyrillic\") — layout must stay data",
                cyrillic.isEmpty(),
            )
        }
    }

    private fun stripComments(text: String): String {
        val noBlock = text.replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        return noBlock.lines().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment >= 0) line.substring(0, comment) else line
        }
    }

    @Test
    fun theEngineDoesNotLogKeyCodesOrText() {
        // The layout crossing carries numbers only.
        val sources = engineSources().joinToString("\n") { it.readText() }
        for (forbidden in listOf("android.util.Log", "println(", "System.out", "System.err")) {
            assertTrue("found $forbidden in engine sources", !sources.contains(forbidden))
        }
    }

    private fun engineSources(): List<File> {
        val engineDir = firstExisting(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine",
        )
        val builder = firstFile(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/KeyNeighborTableBuilder.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/KeyNeighborTableBuilder.kt",
        )
        val engineFiles = engineDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        return engineFiles + listOfNotNull(builder)
    }

    private fun firstExisting(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isDirectory) ?: error("engine sources not found")

    private fun firstFile(vararg paths: String): File? =
        paths.map(::File).firstOrNull(File::isFile)
}

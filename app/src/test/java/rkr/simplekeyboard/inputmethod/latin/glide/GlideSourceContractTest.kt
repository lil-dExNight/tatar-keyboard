package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The glide package keeps the keyboard layout as data (mirroring the E3b engine contract): no
 * Cyrillic literal in code — comments may name letters, literals may not — and no Android import
 * anywhere in `latin/glide` (the package must stay JVM-testable; the single Android crossing is
 * `GlideKeyGeometryBuilder` in the suggestions package).
 */
class GlideSourceContractTest {

    @Test
    fun glideSourcesCarryNoCyrillicLiteral() {
        val files = glideSources() + builderSource()
        assertTrue("glide sources not found", files.isNotEmpty())
        for (file in files) {
            val code = stripComments(file.readText())
            val cyrillic = code.filter { it in '\u0400'..'\u04FF' }
            assertTrue(
                "${file.name} carries a Cyrillic literal (\"$cyrillic\") — layout must stay data",
                cyrillic.isEmpty(),
            )
        }
    }

    @Test
    fun glidePackageImportsNoAndroidType() {
        for (file in glideSources()) {
            val androidImports = file.readText().lines().filter {
                it.startsWith("import android.")
            }
            assertTrue("${file.name} imports Android: $androidImports", androidImports.isEmpty())
        }
    }

    @Test
    fun glideSourcesDoNotLog() {
        val sources = (glideSources() + builderSource()).joinToString("\n") { it.readText() }
        for (forbidden in listOf("android.util.Log", "println(", "System.out", "System.err")) {
            assertTrue("found $forbidden in glide sources", !sources.contains(forbidden))
        }
    }

    private fun stripComments(text: String): String {
        val noBlock = text.replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        return noBlock.lines().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment >= 0) line.substring(0, comment) else line
        }
    }

    private fun glideSources(): List<File> {
        val dir = firstExisting(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/glide",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/glide",
        )
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun builderSource(): List<File> = listOfNotNull(
        firstFile(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/GlideKeyGeometryBuilder.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/GlideKeyGeometryBuilder.kt",
        ),
    )

    private fun firstExisting(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isDirectory) ?: error("glide sources not found")

    private fun firstFile(vararg paths: String): File? =
        paths.map(::File).firstOrNull(File::isFile)
}

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionaryEnginePrivacyTest {
    @Test
    fun engineHasNoLoggingNetworkAnalyticsOrEditorMutationSurface() {
        val sourceDirectory = locateEngineSources()
        val source = sourceDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        listOf(
            "android.util.Log",
            "java.net.",
            "FirebaseAnalytics",
            "System.out",
            "println(",
            "commitText",
            "deleteSurroundingText",
            "RichInputConnection",
        ).forEach { forbidden -> assertFalse("found $forbidden", source.contains(forbidden)) }

        val publicMethods = listOf(
            TdictPrefixIndex::class.java,
            LatestOnlyPrefixEngine::class.java,
            MappedDictionaryEngine::class.java,
            // TatBigrPrefixIndex is the bigram read side, the direct analogue of
            // TdictPrefixIndex — same package, same reflection scan.
            TatBigrPrefixIndex::class.java,
        ).flatMap { type -> type.declaredMethods.map { it.name } }
        assertTrue(publicMethods.none { it.contains("log", ignoreCase = true) })
        assertTrue(publicMethods.none { it.contains("commit", ignoreCase = true) })
        assertTrue(publicMethods.none { it.contains("delete", ignoreCase = true) })
    }

    private fun locateEngineSources(): File {
        val relative = "src/main/java/rkr/simplekeyboard/inputmethod/latin/dictionary/engine"
        return listOf(File(relative), File("app/$relative")).firstOrNull(File::isDirectory)
            ?: error("cannot locate engine sources")
    }
}

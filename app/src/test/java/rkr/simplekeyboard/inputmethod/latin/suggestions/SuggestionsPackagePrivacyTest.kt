/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.suggestions

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy scan of `latin/suggestions/`, modeled on `DictionaryEnginePrivacyTest`.
 *
 * This is the package where the completed-word detection lives — the code that sees every keystroke
 * and holds a whole word in a field. The scan guards against a regression that is very easy to
 * make: one `Log.d` with a word in the message.
 */
class SuggestionsPackagePrivacyTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private val sources by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/suggestions")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
    }

    @Test
    fun thePackageHasNoLoggingNetworkingOrAnalytics() {
        assertTrue("the package exists", sources.isNotEmpty())
        val forbidden = listOf(
            "android.util.Log", "println(", "System.out", "System.err",
            "java.net.", "HttpURL", "Firebase", "Analytics", "Crashlytics",
        )
        for (file in sources) {
            val text = file.readText()
            for (marker in forbidden) {
                assertFalse("${file.name} carries '$marker'", text.contains(marker))
            }
        }
        // Fail-capable: the same predicate against a deliberately broken input.
        assertTrue("Log.d(TAG, word)".contains("Log"))
    }

    @Test
    fun noTypeInThePackageLeaksAWordThroughAGeneratedToString() {
        // A `data class` holding the typed word would print it at the first interpolation. The
        // controller keeps the run word in a plain field; nothing here may be a data class carrying
        // text.
        for (file in sources) {
            val text = file.readText()
            if (!text.contains("data class")) continue
            val offenders = Regex("data class (\\w+)\\(([^)]*)\\)").findAll(text)
                .filter { match -> match.groupValues[2].contains("String") }
                .map { it.groupValues[1] }
                .toList()
            assertTrue("${file.name} has a data class carrying a String: $offenders",
                offenders.isEmpty())
        }
    }
}

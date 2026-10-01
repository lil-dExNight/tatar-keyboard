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

package rkr.simplekeyboard.inputmethod.latin.inputlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two quick spaces give ". " only in a general text field: `tryDoubleSpacePeriod` reads
 * `mIsGeneralTextInput`, which `InputAttributes` sets from `isGeneralTextInputType` on the text
 * path and to false for every other input class.
 *
 * Asserted by source for the reason given in [CommitPathConnectionContractTest]; the field
 * classification itself is tested in `InputTypeUtilsGeneralTextTest`.
 */
class DoubleSpacePeriodFieldSourceContractTest {

    private fun read(relative: String): String {
        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
        return File(root, "java/rkr/simplekeyboard/inputmethod/$relative").readText()
    }

    private val inputLogic by lazy { read("latin/inputlogic/InputLogic.java") }
    private val attributes by lazy { read("latin/InputAttributes.java") }

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is missing", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces after $signature")
    }

    @Test
    fun thePeriodRequiresAGeneralTextField() {
        val period = body(inputLogic, "private boolean tryDoubleSpacePeriod(")
        assertTrue(period.contains("&& settingsValues.mInputAttributes.mIsGeneralTextInput\n"))
        // Password variations are not general text, so the old gate is gone, not duplicated.
        assertFalse(period.contains("mIsPasswordField"))
    }

    @Test
    fun attributesAssignTheFlagOnBothPaths() {
        val constructor = body(attributes, "public InputAttributes(final EditorInfo editorInfo)")
        val assignments = Regex("mIsGeneralTextInput = ").findAll(constructor).count()
        assertEquals(2, assignments)
        val earlyReturn = constructor.indexOf("if (inputClass != InputType.TYPE_CLASS_TEXT) {")
        val nonText = constructor.indexOf("mIsGeneralTextInput = false;")
        val returned = constructor.indexOf("return;", earlyReturn)
        assertTrue("the non-text branch clears the flag before it returns",
            nonText in earlyReturn until returned)
        val textPath = constructor.indexOf(
            "mIsGeneralTextInput = InputTypeUtils.isGeneralTextInputType(inputType);")
        assertTrue("the text path reads the classifier", textPath > returned)
    }
}

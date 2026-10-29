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

package rkr.simplekeyboard.inputmethod.latin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field start works with current settings: `onStartInputViewInternal` rebuilds
 * `SettingsValues` on a field switch or an orientation change BEFORE the first reader
 * (fullscreen evaluation, the hardware-keyboard check, the keyboard load), and the first
 * show builds them exactly once. Pinned in source because `LatinIME` cannot be instantiated
 * without Android.
 */
class FieldStartSettingsReloadSourceContractTest {

    @Test
    fun reloadPrecedesEverySettingsRead() {
        val body = javaBody(ime, "void onStartInputViewInternal(final EditorInfo editorInfo")
        val reload = body.indexOf(RELOAD_CONDITION)
        assertTrue("the conditional reload is missing", reload >= 0)
        for (reader in listOf(
            "updateFullscreenMode()",
            "isImeSuppressedByHardwareKeyboard()",
            "switcher.loadKeyboard(editorInfo, currentSettingsValues",
        )) {
            val at = body.indexOf(reader)
            assertTrue("$reader not found", at >= 0)
            assertTrue("$reader runs before the settings reload", reload < at)
        }
        // The reload refreshes the local that the keyboard load consumes.
        val refresh = body.indexOf("currentSettingsValues = mSettings.getCurrent();", reload)
        assertTrue("the reload does not refresh the local values", refresh > reload)
    }

    @Test
    fun firstShowBuildsSettingsExactlyOnce() {
        val body = javaBody(ime, "void onStartInputViewInternal(final EditorInfo editorInfo")
        // Two call sites, one build per show: the guarded first build OR the conditional reload.
        assertEquals(2, Regex("loadSettings\\(\\)").findAll(body).count())
        assertTrue(body.contains("final boolean settingsUninitialized = currentSettingsValues == null;"))
        assertTrue(body.contains("if (settingsUninitialized) {"))
        // The reload guard skips the first show: it would rebuild from the same inputs.
        assertTrue(body.contains(RELOAD_CONDITION))
    }

    @Test
    fun noUnresolvedAlertCommentsRemain() {
        assertFalse(ime.contains("ALERT"))
    }

    /** The body of the Java method whose signature starts with [signatureStart]. */
    private fun javaBody(source: String, signatureStart: String): String {
        val start = source.indexOf(signatureStart)
        assertTrue("method not found: $signatureStart", start >= 0)
        val openBrace = source.indexOf('{', start)
        var depth = 0
        var index = openBrace
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        error("unbalanced braces after $signatureStart")
    }

    private fun read(path: String): String {
        val candidates = listOf(path, "app/$path")
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        error("source not found: $path")
    }

    private val ime by lazy { read(LATIN_IME) }

    private companion object {
        const val LATIN_IME = "src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java"
        const val RELOAD_CONDITION = "if (!settingsUninitialized && (isDifferentTextField ||"
    }
}

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
 * `switchToTargetIme` runs on ONE shared executor, never on a per-call one.
 *
 * Every IME-picker switch used to call `Executors.newSingleThreadExecutor()` and never shut the
 * executor down, leaking one thread per switch (audit B1). The executor is now the field
 * `mSwitchExecutor`, created once in `initInternal` — the idiom `AudioAndHapticFeedbackManager`
 * uses for its background thread. A source contract because these JVM tests have no Android
 * framework to drive the picker with.
 */
class RichInputMethodManagerExecutorSourceContractTest {

    private val source by lazy {
        File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/RichInputMethodManager.java")
            .readText()
    }

    /** The body of switchToTargetIme — the last method of the class. */
    private val switchToTargetImeBody by lazy {
        val start = source.indexOf("private void switchToTargetIme(")
        assertTrue("switchToTargetIme declaration not found", start >= 0)
        source.substring(start)
    }

    @Test
    fun theFileCreatesExactlyOneExecutor() {
        val occurrences = source.split("newSingleThreadExecutor").size - 1
        assertEquals("RichInputMethodManager.java must create exactly one executor", 1, occurrences)
    }

    @Test
    fun theSingleExecutorIsCreatedInInitInternal() {
        val start = source.indexOf("private void initInternal(")
        assertTrue("initInternal declaration not found", start >= 0)
        assertTrue("initInternal must create the one executor",
            source.substring(start).contains("Executors.newSingleThreadExecutor()"))
    }

    @Test
    fun switchToTargetImeDoesNotCreateAnExecutor() {
        assertFalse(
            "switchToTargetIme must not create a per-call executor",
            switchToTargetImeBody.contains("newSingleThreadExecutor"),
        )
    }

    @Test
    fun switchToTargetImeRunsOnTheSharedField() {
        assertTrue(
            "switchToTargetIme must execute on the mSwitchExecutor field",
            switchToTargetImeBody.contains("mSwitchExecutor.execute("),
        )
    }

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
}

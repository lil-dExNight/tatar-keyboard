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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The locale broadcast can start a cold process, before LatinIME or a settings screen has
 * initialized [RichInputMethodManager]. The receiver must not reach `getInstance()`, which
 * throws before init; a cold process has no subtype list to reload.
 */
class RichInputMethodManagerColdStartTest {

    @Test
    fun aReloadBeforeInitIsANoOp() {
        // This JVM never initializes the manager (that needs a Context): the process is cold.
        try {
            RichInputMethodManager.getInstance()
            fail("the manager must be uninitialized here")
        } catch (expected: RuntimeException) {
        }

        RichInputMethodManager.reloadSubtypesIfInitialized(null)
    }

    @Test
    fun theLocaleReceiverUsesTheColdSafeReload() {
        val receiver = readMain("SystemBroadcastReceiver.java")
        assertFalse("getInstance() throws in a cold process",
            receiver.contains("RichInputMethodManager.getInstance()"))
        assertTrue(receiver.contains("RichInputMethodManager.reloadSubtypesIfInitialized(context);"))
    }

    private fun readMain(name: String): String {
        val relative = "java/rkr/simplekeyboard/inputmethod/latin/$name"
        return listOf(File("src/main/$relative"), File("app/src/main/$relative"))
            .firstOrNull(File::isFile)?.readText() ?: error("cannot locate $relative")
    }
}

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
 * No `Handler` of this app may use 0 as a message id.
 *
 * `Handler.post(Runnable)` does not invent an id: it enqueues an ordinary `Message` whose `what` is
 * **0** and whose `callback` is the runnable. `Handler.removeMessages(int what)` matches on `what`
 * ALONE — the callback is not part of the comparison — so `removeMessages(0)` deletes every posted
 * runnable still waiting on that handler.
 *
 * Example: `postUpdateShiftState()` runs after almost every keystroke and starts with
 * `removeMessages(MSG_UPDATE_SHIFT_STATE)`. If that id were 0, it would drop the suggestion
 * results that `SuggestionsController`'s `UiPoster` posts on the same handler, and the suggestion
 * strip would never repaint. The emoji panel's poster and several dialogs use that handler too.
 *
 * A source-contract test rather than a behavioral one because these JVM tests have no Android
 * framework: `Handler`, `Looper` and `MessageQueue` do not exist here, so the invariant is pinned
 * where it is written down.
 */
class HandlerMessageIdSourceContractTest {

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private val handlerSources: List<File> by lazy {
        File(sourceRoot(), "java")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readText().contains("removeMessages(") }
            .toList()
    }

    private val messageIdDeclaration =
        Regex("""(?:static final int|const val)\s+(MSG_\w+)\s*=\s*(-?\d+)""")

    @Test
    fun everySourceThatCancelsMessagesWasFound() {
        val names = handlerSources.map(File::getName).toSet()
        assertTrue("LatinIME.java must be among the handler sources: $names",
            names.contains("LatinIME.java"))
        assertTrue("TimerHandler.java must be among the handler sources: $names",
            names.contains("TimerHandler.java"))
    }

    @Test
    fun noHandlerUsesZeroAsAMessageId() {
        var declarations = 0
        for (file in handlerSources) {
            for (match in messageIdDeclaration.findAll(file.readText())) {
                declarations++
                val (name, value) = match.destructured
                assertFalse(
                    "${file.name}: $name = 0 collides with the id Handler.post(Runnable) uses, " +
                        "so removeMessages($name) silently deletes posted runnables",
                    value.toInt() == 0,
                )
            }
        }
        assertTrue("the scan found no message ids at all", declarations >= 8)
    }

    @Test
    fun messageIdsAreUniqueWithinEachHandlerSource() {
        for (file in handlerSources) {
            val values = messageIdDeclaration.findAll(file.readText())
                .map { it.destructured.component2().toInt() }
                .toList()
            assertEquals("${file.name} declares two message ids with the same value: $values",
                values.size, values.toSet().size)
        }
    }

    /**
     * The invariant is only worth having while runnables really are posted on such a handler. If
     * this ever stops being true the test above checks nothing, and this test fails.
     */
    @Test
    fun runnablesAreStillPostedOnTheImeHandler() {
        val latinIme = handlerSources.first { it.name == "LatinIME.java" }.readText()
        assertTrue(
            "nothing posts a bare Runnable on the IME handler any more",
            latinIme.contains("mHandler.post("),
        )
        val controller = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
        ).readText()
        assertTrue(
            "the suggestion result is no longer delivered through a posted runnable",
            controller.contains("uiHandler.post(runnable)"),
        )
    }

    /** Fail-capable: the same predicate against a deliberately broken input. */
    @Test
    fun theScanWouldCatchAZeroId() {
        val broken = "    private static final int MSG_UPDATE_SHIFT_STATE = 0;"
        val match = messageIdDeclaration.find(broken)
        assertTrue("the regex must match a plain declaration", match != null)
        assertEquals(0, match!!.destructured.component2().toInt())
    }
}

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

package rkr.simplekeyboard.inputmethod.keyboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PointerTracker half of the 2026-09-25 input-robustness wave
 * (docs/SECURITY-AUDIT-2026-09-25-FIXES.md), pinned at source level for the same reason as
 * [GlideTouchIntegrationContractTest]: PointerTracker's static state needs a live `Resources`,
 * so it cannot be instantiated in a JVM test. The behavioral half of F13 (the queue itself)
 * lives in `PointerTrackerQueueTest`.
 *
 * F2: `mCursorMoved` marks THIS gesture's space/delete swipe. It used to reset only on a plain
 * up, so a cancelled swipe leaked the state into the next touch — whose up then dereferenced a
 * null current key (the NPE) or fired the swipe callbacks for a gesture that never swiped.
 * F13: the queue evicts cancelled trackers, so the phantom-up wave must ride inside the
 * queue's cancel-all (a releaseAllPointers after it would iterate an empty queue).
 */
class PointerTrackerRobustnessContractTest {

    private val trackerSource = read(
        "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java",
        "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java",
    )
    private val queueSource = read(
        "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java",
        "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java",
    )

    @Test
    fun theSwipeStateResetsAtDownAndAtCancel() {
        assertTrue(
            "a new gesture starts with a clean swipe state",
            methodBody(trackerSource, "onDownEventInternal").contains("mCursorMoved = false;"),
        )
        assertTrue(
            "a cancel ends the gesture, swipe state included",
            methodBody(trackerSource, "onCancelEventInternal").contains("mCursorMoved = false;"),
        )
    }

    @Test
    fun theSwipeUpCallbacksAreNullGuarded() {
        val up = methodBody(trackerSource, "onUpEventInternal")
        assertTrue(
            "a cursor-moved up with no current key must not dereference it (delete)",
            up.contains("if (mCursorMoved && currentKey != null && currentKey.getCode() == Constants.CODE_DELETE)"),
        )
        assertTrue(
            "a cursor-moved up with no current key must not dereference it (space)",
            up.contains("if (mCursorMoved && currentKey != null && currentKey.getCode() == Constants.CODE_SPACE)"),
        )
    }

    @Test
    fun theCancelEventCarriesItsPhantomUpWaveInsideTheQueueCall() {
        val cancel = methodBody(trackerSource, "onCancelEvent")
        assertTrue(
            "the queue's cancel-all (disable + phantom-up + evict) runs",
            cancel.contains("sPointerTrackerQueue.cancelAllPointerTrackers(eventTime);"),
        )
        assertFalse(
            "a releaseAllPointers after the eviction would iterate an empty queue",
            cancel.contains("releaseAllPointers(eventTime)"),
        )
        assertTrue(
            "the trail/hover graphics still end for every tracker",
            cancel.contains("endGlideFeedbackForAllTrackers();"),
        )
        // The zero-arg static keeps its contract for the closing path (MainKeyboardView).
        val cancelAll = trackerSource.substringAfter("public static void cancelAllPointerTrackers()")
        assertTrue(cancelAll.contains("endGlideFeedbackForAllTrackers();"))
    }

    @Test
    fun theQueueDeduplicatesAddsAndEvictsOnCancel() {
        val add = methodBody(queueSource, "add")
        assertTrue(
            "a tracker already in the queue is not added twice",
            add.contains("if (expandableArray.get(index) == pointer)"),
        )
        val cancelAll = methodBody(queueSource, "cancelAllPointerTrackers")
        assertTrue(
            "cancelled trackers leave the queue",
            cancelAll.contains("mArraySize = 0;"),
        )
    }

    @Test
    fun theQueueDisablesEverythingBeforePhantomUpping() {
        val overload = queueSource.substringAfter("public void cancelAllPointerTrackers(final long eventTime)")
        val disable = overload.indexOf("cancelTrackingForAction();")
        val phantom = overload.indexOf("onPhantomUpEvent(eventTime);")
        val evict = overload.indexOf("mArraySize = 0;")
        assertTrue("disable-all precedes the phantom ups", disable in 0 until phantom)
        assertTrue("the eviction is last", phantom in 0 until evict)
    }

    /** The body of one method, from its declaration to the next one (good enough for pins). */
    private fun methodBody(source: String, name: String): String {
        // The " void " prefix skips javadoc {@link} references to the same method.
        val start = source.indexOf(" void $name(")
        assertTrue("method $name not found", start >= 0)
        val next = source.indexOf("\n    private ", start + 1)
        val nextPublic = source.indexOf("\n    public ", start + 1)
        val end = listOf(next, nextPublic).filter { it > 0 }.minOrNull() ?: source.length
        return source.substring(start, end)
    }

    private fun read(vararg paths: String): String =
        paths.map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate ${paths.first()}")
}

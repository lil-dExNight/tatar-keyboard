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

package rkr.simplekeyboard.inputmethod.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-25 audit, F13 (docs/SECURITY-AUDIT-2026-09-25-FIXES.md): the queue holds each tracker
 * at most once (a tracker whose up event was lost must not re-enter on the next down), and a
 * cancel-all EVICTS — a cancelled tracker used to linger forever, inflating the active-pointer
 * count and receiving phantom ups meant for live touches. The eventTime overload keeps the
 * ACTION_CANCEL ordering: disable everyone first (a phantom up delivered to a still-enabled
 * tracker would commit its key), then the phantom-up wave, then the eviction.
 *
 * The queue is plain Java with its Android use confined to DEBUG-gated logs, so it runs in a
 * JVM test; [PointerTracker] itself does not (its static state needs a live Resources), and its
 * half of the contract is pinned in `PointerTrackerRobustnessContractTest`.
 */
class PointerTrackerQueueTest {

    private class FakeElement(
        private val log: MutableList<String>,
        private val name: String,
    ) : PointerTrackerQueue.Element {
        var modifier = false
        var cursorMove = false

        override fun isModifier() = modifier
        override fun isInDraggingFinger() = false
        override fun isInCursorMove() = cursorMove
        override fun onPhantomUpEvent(eventTime: Long) {
            log.add("phantom:$name:$eventTime")
        }

        override fun cancelTrackingForAction() {
            log.add("cancel:$name")
        }

        override fun toString() = name
    }

    private fun fixture(): Triple<PointerTrackerQueue, MutableList<String>, List<FakeElement>> {
        val log = mutableListOf<String>()
        val elements = listOf(FakeElement(log, "a"), FakeElement(log, "b"), FakeElement(log, "c"))
        return Triple(PointerTrackerQueue(), log, elements)
    }

    @Test
    fun aSecondAddOfTheSameElementIsIgnored() {
        val (queue, _, elements) = fixture()
        val (a, b) = elements

        queue.add(a)
        queue.add(a)
        assertEquals("the same tracker enters the queue once", 1, queue.size())

        queue.add(b)
        assertEquals(2, queue.size())

        // And a duplicate that slipped past add is still excised whole by remove.
        queue.remove(a)
        assertEquals(1, queue.size())
        queue.remove(a)
        assertEquals("removing an absent element is a no-op", 1, queue.size())
    }

    @Test
    fun cancelAllDisablesAndEvictsEveryone() {
        val (queue, log, elements) = fixture()
        elements.forEach(queue::add)

        queue.cancelAllPointerTrackers()

        assertEquals(listOf("cancel:a", "cancel:b", "cancel:c"), log)
        assertEquals("cancelled trackers leave the queue", 0, queue.size())

        // The audit's shape: nothing lingers to receive a later phantom up.
        queue.releaseAllPointers(42L)
        assertEquals("no phantom ups reach evicted trackers", 3, log.size)
    }

    @Test
    fun cancelAllWithEventTimeDisablesEverythingBeforePhantomUppingThenEvicts() {
        val (queue, log, elements) = fixture()
        elements.forEach(queue::add)

        queue.cancelAllPointerTrackers(7L)

        assertEquals(
            "all cancels precede all phantom ups (an enabled tracker would commit its key)",
            listOf("cancel:a", "cancel:b", "cancel:c", "phantom:a:7", "phantom:b:7", "phantom:c:7"),
            log,
        )
        assertEquals(0, queue.size())
    }

    @Test
    fun theReleaseSemanticsAreUnchanged() {
        val (queue, log, elements) = fixture()
        val (a, b, c) = elements
        a.modifier = true
        elements.forEach(queue::add)

        // Older-than: the modifier stays without a phantom up; the plain element before the
        // marker is phantom-upped and evicted; the marker and everything after stays.
        queue.releaseAllPointersOlderThan(c, 5L)
        assertEquals(listOf("phantom:b:5"), log)
        assertEquals(2, queue.size())

        queue.releaseAllPointersExcept(c, 6L)
        assertEquals(listOf("phantom:b:5", "phantom:a:6"), log)
        assertEquals(1, queue.size())

        assertFalse(queue.isAnyInCursorMove())
        c.cursorMove = true
        assertTrue(queue.isAnyInCursorMove())
    }
}

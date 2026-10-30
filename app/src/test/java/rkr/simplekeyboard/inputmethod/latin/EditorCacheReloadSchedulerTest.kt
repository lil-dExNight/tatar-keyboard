/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package rkr.simplekeyboard.inputmethod.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.RichInputConnection.EditorCacheReloadScheduler

class EditorCacheReloadSchedulerTest {

    @Test
    fun ownSelectionReportsReloadOnlyAfterTheySettle() {
        val backend = FakeBackend()
        var reloads = 0
        val scheduler = EditorCacheReloadScheduler(backend) { reloads++ }

        scheduler.onExpectedSelectionChange()

        assertEquals(0, reloads)
        backend.advanceBy(EditorCacheReloadScheduler.SETTLE_DELAY_MS - 1)
        assertEquals(0, reloads)
        backend.advanceBy(1)
        assertEquals(1, reloads)
        assertTrue(backend.pending.isEmpty())

        backend.advanceBy(2 * EditorCacheReloadScheduler.HEARTBEAT_MS)
        assertEquals("an idle field has no recurring reloads", 1, reloads)
    }

    @Test
    fun selfEditingHostResynchronizesWithinTheHeartbeatWhileTypingContinues() {
        val backend = FakeBackend()
        var hostText = "host"
        var cachedText = hostText
        val scheduler = EditorCacheReloadScheduler(backend) { cachedText = hostText }

        repeat(9) {
            hostText += "x"
            scheduler.onExpectedSelectionChange()
            backend.advanceBy(100)
        }
        assertNotEquals("the host's self-edit is not mirrored by the local edit", hostText, cachedText)

        backend.advanceBy(EditorCacheReloadScheduler.HEARTBEAT_MS - backend.now - 1)
        assertNotEquals(hostText, cachedText)
        backend.advanceBy(1)

        assertEquals(EditorCacheReloadScheduler.HEARTBEAT_MS, backend.now)
        assertEquals(hostText, cachedText)
    }

    @Test
    fun externalMovementReloadsImmediatelyAndCancelsPendingTypingWork() {
        val backend = FakeBackend()
        var reloads = 0
        val scheduler = EditorCacheReloadScheduler(backend) { reloads++ }
        scheduler.onExpectedSelectionChange()

        scheduler.reloadNow()

        assertEquals(1, reloads)
        assertTrue(backend.pending.isEmpty())
        backend.advanceBy(2 * EditorCacheReloadScheduler.HEARTBEAT_MS)
        assertEquals(1, reloads)
    }

    @Test
    fun selectionNormalizationIsUnaffectedByReloadScheduling() {
        val backend = FakeBackend()
        val connection = RichInputConnection(null)
        val scheduler = EditorCacheReloadScheduler(backend) {}
        connection.updateSelection(8, 8)
        scheduler.onExpectedSelectionChange()

        assertEquals(8, connection.expectedSelectionStart)
        assertEquals(8, connection.expectedSelectionEnd)
        assertFalse(connection.hasSelection())

        connection.updateSelection(12, 7)
        scheduler.reloadNow()

        assertEquals(7, connection.expectedSelectionStart)
        assertEquals(12, connection.expectedSelectionEnd)
        assertTrue(connection.hasSelection())
    }

    private class FakeBackend : EditorCacheReloadScheduler.Backend {
        data class PendingTask(val id: Long, val due: Long, val runnable: Runnable)

        var now = 0L
            private set
        val pending = mutableListOf<PendingTask>()
        private var nextId = 0L

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            pending += PendingTask(nextId++, now + delayMillis, runnable)
        }

        override fun removeCallbacks(runnable: Runnable) {
            pending.removeAll { it.runnable === runnable }
        }

        fun advanceBy(deltaMillis: Long) {
            val target = now + deltaMillis
            while (true) {
                val next = pending.filter { it.due <= target }
                    .minWithOrNull(compareBy<PendingTask> { it.due }.thenBy { it.id }) ?: break
                pending.remove(next)
                now = next.due
                next.runnable.run()
            }
            now = target
        }
    }
}

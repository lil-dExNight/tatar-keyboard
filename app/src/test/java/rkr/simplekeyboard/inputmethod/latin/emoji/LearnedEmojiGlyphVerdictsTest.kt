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

package rkr.simplekeyboard.inputmethod.latin.emoji

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restored learned clusters are probed once off the keystroke path and then cached. */
class LearnedEmojiGlyphVerdictsTest {

    @Test
    fun everyClusterShapeUsesOneCoalescedBackgroundProbeAndACachedVerdict() {
        val supported = setOf(NEUTRAL, TONED, ZWJ, KEYCAP)
        val forms = listOf(NEUTRAL, TONED, ZWJ, FLAG, KEYCAP, TAG)
        val probes = ArrayList<String>()
        val executor = QueuedExecutorService()
        val cache = LearnedEmojiGlyphVerdicts(
            executor,
            GlyphProbe { sequence ->
                probes.add(sequence)
                sequence in supported
            },
        )
        val resolutions = ArrayList<Pair<String, Boolean>>()
        val observer = LearnedEmojiDrawabilityObserver { emoji, drawable ->
            resolutions.add(emoji to drawable)
        }

        for (form in forms) {
            assertNull(cache.verdict(form, observer))
            // Repeated reads while pending add neither work nor a duplicate observer.
            assertNull(cache.verdict(form, observer))
        }
        assertEquals(forms.size, executor.taskCount)
        assertTrue(probes.isEmpty())

        executor.runAll()

        assertEquals(forms, probes)
        assertEquals(forms.size, resolutions.size)
        for (form in forms) {
            val expected = form in supported
            assertEquals(expected, resolutions.single { it.first == form }.second)
            repeat(5) { assertEquals(expected, cache.verdict(form, observer)) }
        }
        assertEquals("known verdicts must not probe again", forms, probes)
        assertEquals(forms.size, executor.taskCount)
    }

    @Test
    fun theSameSequenceFollowsTheSupportingFontVerdict() {
        val supporting = LearnedEmojiGlyphVerdicts(
            QueuedExecutorService().also { it.runOnExecute = true },
            GlyphProbe { sequence -> sequence == ZWJ },
        )
        val unsupported = LearnedEmojiGlyphVerdicts(
            QueuedExecutorService().also { it.runOnExecute = true },
            GlyphProbe { false },
        )
        val observer = LearnedEmojiDrawabilityObserver { _, _ -> }

        assertNull(supporting.verdict(ZWJ, observer))
        assertNull(unsupported.verdict(ZWJ, observer))
        assertTrue(supporting.verdict(ZWJ, observer) == true)
        assertFalse(unsupported.verdict(ZWJ, observer) == true)
    }

    private class QueuedExecutorService : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        private var submittedCount = 0
        var runOnExecute = false
        val taskCount: Int get() = submittedCount

        override fun execute(command: Runnable) {
            submittedCount++
            if (runOnExecute) command.run() else tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }

        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf()
        }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private companion object {
        const val NEUTRAL = "☀️"
        const val TONED = "👍🏿"
        const val ZWJ = "👩‍👩‍👧‍👧"
        const val FLAG = "🇹🇹"
        const val KEYCAP = "1️⃣"
        const val TAG = "🏴\uDB40\uDD67\uDB40\uDD62\uDB40\uDD65\uDB40\uDD73\uDB40\uDD63\uDB40\uDD7F"
    }
}

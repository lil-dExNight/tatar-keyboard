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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.suggestions.LookupTracer.StartupSlice

/**
 * The cookie state machine of [LookupTracerImpl], driven against a recording sink. Event strings
 * are `S|name|cookie` for a slice begin and `F|name|cookie` for its end, in emission order — the
 * exact pairing the atrace text parser reconstructs from a captured trace.
 */
class LookupTracerTest {

    private class RecordingSink(var tracing: Boolean = true) : LookupTraceSink {
        val events = mutableListOf<String>()

        override fun isTracing(): Boolean = tracing

        override fun beginAsyncSection(name: String, cookie: Int) {
            events.add("S|$name|$cookie")
        }

        override fun endAsyncSection(name: String, cookie: Int) {
            events.add("F|$name|$cookie")
        }
    }

    private fun tracerWith(sink: RecordingSink) = LookupTracerImpl(sink)

    @Test
    fun roundTripPairsEverySliceUnderOneCookie() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        val cookie = tracer.beginLookup()
        assertNotEquals(LookupTracer.NO_COOKIE, cookie)
        assertEquals(cookie, tracer.issuedCookie())
        tracer.workerStart(cookie)
        tracer.workerHandoff(cookie)
        tracer.lookupApplied(cookie)
        tracer.frameDrawn(cookie)

        assertEquals(
            listOf(
                "S|TT#suggestLookup|$cookie",
                "S|TT#lookupQueue|$cookie",
                "F|TT#lookupQueue|$cookie",
                "S|TT#lookupCompute|$cookie",
                "F|TT#lookupCompute|$cookie",
                "S|TT#lookupHandoff|$cookie",
                "F|TT#lookupHandoff|$cookie",
                "F|TT#suggestLookup|$cookie",
                "S|TT#lookupFrame|$cookie",
                "F|TT#lookupFrame|$cookie",
            ),
            sink.events,
        )
        assertEquals(LookupTracer.NO_COOKIE, tracer.issuedCookie())
    }

    @Test
    fun cookiesAreSerialsAndNeverRepeat() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        val first = tracer.beginLookup()
        val second = tracer.beginLookup()
        val third = tracer.beginLookup()

        assertNotEquals(LookupTracer.NO_COOKIE, first)
        assertTrue(first != second && second != third && first != third)
    }

    @Test
    fun supersedeClosesTheOldTripAndSuppressesItsLateBoundaries() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        val stale = tracer.beginLookup()
        tracer.workerStart(stale)
        // The replacement begins while the old compute is still open on the worker.
        val fresh = tracer.beginLookup()

        assertEquals(
            listOf(
                "S|TT#suggestLookup|$stale",
                "S|TT#lookupQueue|$stale",
                "F|TT#lookupQueue|$stale",
                "S|TT#lookupCompute|$stale",
                // The supersede closes the open compute and the parent, innermost first.
                "F|TT#lookupCompute|$stale",
                "F|TT#suggestLookup|$stale",
                "S|TT#suggestLookup|$fresh",
                "S|TT#lookupQueue|$fresh",
            ),
            sink.events,
        )

        // The stale worker's own boundaries arrive late and must be dropped.
        tracer.workerHandoff(stale)
        tracer.lookupApplied(stale)
        tracer.cancelLookup(stale)
        tracer.frameDrawn(stale)
        assertEquals(8, sink.events.size)

        // The fresh trip is unaffected.
        tracer.workerStart(fresh)
        assertEquals(
            listOf("F|TT#lookupQueue|$fresh", "S|TT#lookupCompute|$fresh"),
            sink.events.takeLast(2),
        )
    }

    @Test
    fun cancelClosesOpenSlicesAndSuppressesTheWorker() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        val cookie = tracer.beginLookup()
        tracer.cancelLookup(cookie)

        assertEquals(
            listOf(
                "S|TT#suggestLookup|$cookie",
                "S|TT#lookupQueue|$cookie",
                "F|TT#lookupQueue|$cookie",
                "F|TT#suggestLookup|$cookie",
            ),
            sink.events,
        )

        tracer.workerStart(cookie)
        tracer.workerHandoff(cookie)
        tracer.lookupApplied(cookie)
        tracer.frameDrawn(cookie)
        assertEquals(4, sink.events.size)
    }

    @Test
    fun cancelMidComputeClosesTheComputeSliceFromTheUiSide() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        val cookie = tracer.beginLookup()
        tracer.workerStart(cookie)
        tracer.cancelLookup(cookie)

        assertEquals(
            listOf(
                "F|TT#lookupCompute|$cookie",
                "F|TT#suggestLookup|$cookie",
            ),
            sink.events.takeLast(2),
        )
        // The worker's end-of-compute boundary for the canceled cookie is then dropped.
        tracer.workerHandoff(cookie)
        assertEquals(6, sink.events.size)
    }

    @Test
    fun applyWithoutWorkerBoundariesClosesTheQueueSliceDefensively() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        // An executor that failed the submission delivers an empty answer: no workerStart or
        // workerHandoff ever fired for this cookie.
        val cookie = tracer.beginLookup()
        tracer.lookupApplied(cookie)
        tracer.frameDrawn(cookie)

        assertEquals(
            listOf(
                "S|TT#suggestLookup|$cookie",
                "S|TT#lookupQueue|$cookie",
                "F|TT#lookupQueue|$cookie",
                "F|TT#suggestLookup|$cookie",
                "S|TT#lookupFrame|$cookie",
                "F|TT#lookupFrame|$cookie",
            ),
            sink.events,
        )
    }

    @Test
    fun lateWorkerBoundariesAfterApplyAreSuppressed() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        // A same-cookie worker boundary after the answer was applied belongs to a request
        // stamped while the trip was open (a companion fill or a glide); it must not paint.
        val cookie = tracer.beginLookup()
        tracer.workerStart(cookie)
        tracer.workerHandoff(cookie)
        tracer.lookupApplied(cookie)
        val sizeAtApply = sink.events.size

        tracer.workerStart(cookie)
        tracer.workerHandoff(cookie)
        assertEquals(sizeAtApply, sink.events.size)

        tracer.frameDrawn(cookie)
        assertEquals("F|TT#lookupFrame|$cookie", sink.events.last())
    }

    @Test
    fun boundariesWithoutAnOpenTripAreSuppressed() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        tracer.workerStart(7)
        tracer.workerHandoff(7)
        tracer.lookupApplied(7)
        tracer.cancelLookup(7)
        tracer.frameDrawn(7)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun untracedTracerIsAStrictNoOp() {
        val sink = RecordingSink(tracing = false)
        val tracer = tracerWith(sink)

        assertEquals(LookupTracer.NO_COOKIE, tracer.beginLookup())
        assertEquals(LookupTracer.NO_COOKIE, tracer.issuedCookie())
        tracer.workerStart(1)
        tracer.workerHandoff(1)
        tracer.lookupApplied(1)
        tracer.frameDrawn(1)
        tracer.cancelLookup(1)
        tracer.startupBegin(StartupSlice.SETTINGS_INIT)
        tracer.startupEnd(StartupSlice.SETTINGS_INIT)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun startupSlicesFireOncePerProcess() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        tracer.startupBegin(StartupSlice.SETTINGS_INIT)
        tracer.startupEnd(StartupSlice.SETTINGS_INIT)
        // The one-time mask: neither a second begin nor a second end records anything.
        tracer.startupBegin(StartupSlice.SETTINGS_INIT)
        tracer.startupEnd(StartupSlice.SETTINGS_INIT)
        // An end without a begin records nothing.
        tracer.startupEnd(StartupSlice.ENGINE_START)

        assertEquals(2, sink.events.size)
        assertEquals(
            "S|${LookupTracer.SECTION_START_SETTINGS_INIT}",
            sink.events[0].substringBeforeLast("|"),
        )
        // The end pairs with the begin: same slice name, same cookie.
        assertEquals(
            sink.events[0].replaceFirst("S|", "F|"),
            sink.events[1],
        )
    }

    @Test
    fun startupSlicesHaveDistinctNamesAndCookies() {
        val sink = RecordingSink()
        val tracer = tracerWith(sink)

        for (slice in StartupSlice.entries) {
            tracer.startupBegin(slice)
        }
        for (slice in StartupSlice.entries) {
            tracer.startupEnd(slice)
        }

        assertEquals(2 * StartupSlice.entries.size, sink.events.size)
        val beginCookies = sink.events.filter { it.startsWith("S|") }.map { it.substringAfterLast("|") }
        assertEquals(beginCookies.size, beginCookies.toSet().size)
        // Every begin pairs with exactly one end of the same name and cookie.
        assertEquals(
            sink.events.filter { it.startsWith("S|") }.map { it.replaceFirst("S|", "") }.toSet(),
            sink.events.filter { it.startsWith("F|") }.map { it.replaceFirst("F|", "") }.toSet(),
        )
    }

    @Test
    fun disabledTracerAnswersNoCookieAndIgnoresEveryBoundary() {
        val tracer = LookupTracer.DISABLED

        assertEquals(LookupTracer.NO_COOKIE, tracer.beginLookup())
        assertEquals(LookupTracer.NO_COOKIE, tracer.issuedCookie())
        // No sink exists to observe; the contract is that nothing throws and nothing is kept.
        tracer.workerStart(1)
        tracer.workerHandoff(1)
        tracer.lookupApplied(1)
        tracer.frameDrawn(1)
        tracer.cancelLookup(1)
        tracer.startupBegin(StartupSlice.KEYBOARD_LOAD)
        tracer.startupEnd(StartupSlice.KEYBOARD_LOAD)
    }
}

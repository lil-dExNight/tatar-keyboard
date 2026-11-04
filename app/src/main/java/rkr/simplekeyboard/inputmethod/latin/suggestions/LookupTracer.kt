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

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.os.Trace
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.LookupTraceHook

/**
 * Perfetto tracing of the suggestion round trip and the cold start. The round trip is one
 * cookie-keyed async slice per lookup ([SECTION_LOOKUP]) with nested sub-slices that attribute
 * the queue wait (enqueue to worker start), the worker compute, the worker-to-UI delivery and
 * the apply-to-frame tail; the cold start is four one-time slices ([StartupSlice]). Async
 * (cookie-keyed) rather than beginSection/endSection because the ends run on different threads.
 *
 * The default is [DISABLED]: plain-JVM tests have no Android runtime, where android.os.Trace
 * would throw. The production [ATRACE] gates on `Trace.isEnabled()` per call, so an untraced
 * process pays one query per boundary and no allocation: names are constants and a lookup that
 * was never begun carries [NO_COOKIE], which every later boundary rejects.
 */
internal interface LookupTracer : LookupTraceHook {
    /**
     * Opens the round trip of a lookup just issued on the UI thread, superseding whatever trip
     * is still open (the engine never delivers that one's stale result). Returns the cookie the
     * later boundaries are keyed by, or [NO_COOKIE] when untraced.
     */
    fun beginLookup(): Int

    /**
     * The answer reached applyResult on the UI thread: closes the trip and opens the frame
     * slice. A cookie that was superseded or canceled is ignored.
     */
    fun lookupApplied(cookie: Int)

    /** The frame that paints the applied strip was drawn; closes the frame slice. */
    fun frameDrawn(cookie: Int)

    /** The lookup died without an answer (superseded elsewhere, invalidated, torn down). */
    fun cancelLookup(cookie: Int)

    /** Opens a cold-start slice, once per process; later beginnings of the same slice no-op. */
    fun startupBegin(slice: StartupSlice)

    /** Closes a cold-start slice; a slice that never began is ignored. */
    fun startupEnd(slice: StartupSlice)

    /** The one-time slices of the cold start, keyed by ordinal so an untraced call is free. */
    enum class StartupSlice(val sectionName: String) {
        SETTINGS_INIT(SECTION_START_SETTINGS_INIT),
        KEYBOARD_LOAD(SECTION_START_KEYBOARD_LOAD),
        DICTIONARY_PUBLISH(SECTION_START_DICTIONARY_PUBLISH),
        ENGINE_START(SECTION_START_ENGINE_START),
    }

    companion object {
        /** Sentinel of "no slice is open"; real cookies are serials starting at 1. */
        const val NO_COOKIE: Int = 0

        /** The Perfetto slice name of the lookup round trip (`TT#` prefix, short on purpose). */
        const val SECTION_LOOKUP: String = "TT#suggestLookup"

        /** Enqueue on the UI thread to the worker taking the request: the executor queue wait. */
        const val SECTION_LOOKUP_QUEUE: String = "TT#lookupQueue"

        /** The worker's dictionary compute of the request. */
        const val SECTION_LOOKUP_COMPUTE: String = "TT#lookupCompute"

        /** Worker-side delivery of the result to its applyResult on the UI thread. */
        const val SECTION_LOOKUP_HANDOFF: String = "TT#lookupHandoff"

        /** applyResult to the frame that paints the strip (two vsync callbacks). */
        const val SECTION_LOOKUP_FRAME: String = "TT#lookupFrame"

        const val SECTION_START_SETTINGS_INIT: String = "TT#startSettingsInit"
        const val SECTION_START_KEYBOARD_LOAD: String = "TT#startKeyboardLoad"
        const val SECTION_START_DICTIONARY_PUBLISH: String = "TT#startDictionaryPublish"
        const val SECTION_START_ENGINE_START: String = "TT#startEngineStart"

        /** Fixed cookies of the one-time slices; lookup cookies (serials from 1) stay below. */
        private const val STARTUP_COOKIE_BASE: Int = 1_000_000

        /** No markers at all — the tests' and every unwired controller's shape. */
        val DISABLED: LookupTracer = object : LookupTracer {
            override fun issuedCookie(): Int = NO_COOKIE
            override fun beginLookup(): Int = NO_COOKIE
            override fun workerStart(cookie: Int) {}
            override fun workerHandoff(cookie: Int) {}
            override fun lookupApplied(cookie: Int) {}
            override fun frameDrawn(cookie: Int) {}
            override fun cancelLookup(cookie: Int) {}
            override fun startupBegin(slice: StartupSlice) {}
            override fun startupEnd(slice: StartupSlice) {}
        }

        /**
         * Production: platform atrace async sections. `Trace.beginAsyncSection` exists only since
         * API 29; below that the tracer is [DISABLED] and records nothing.
         */
        val ATRACE: LookupTracer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                LookupTracerImpl(AtraceSink)
            } else {
                DISABLED
            }

        /** Java-facing entry to [ATRACE]'s cold-start slices (LatinIME). */
        @JvmStatic
        fun traceStartupBegin(slice: StartupSlice) = ATRACE.startupBegin(slice)

        /** Java-facing entry to [ATRACE]'s cold-start slices (LatinIME). */
        @JvmStatic
        fun traceStartupEnd(slice: StartupSlice) = ATRACE.startupEnd(slice)

        internal fun startupCookie(slice: StartupSlice): Int = STARTUP_COOKIE_BASE + slice.ordinal
    }
}

/**
 * The platform seam of [LookupTracerImpl]: the android.os.Trace calls, faked from JVM tests.
 * Implementations must not allocate when [isTracing] is false; the tracer queries it before
 * building any marker.
 */
internal interface LookupTraceSink {
    fun isTracing(): Boolean
    fun beginAsyncSection(name: String, cookie: Int)
    fun endAsyncSection(name: String, cookie: Int)
}

/** android.os.Trace-backed sink; instantiated only on API 29+, where these methods exist. */
// @RequiresApi lives in androidx.annotation, which the app does not depend on.
@SuppressLint("UseRequiresApi")
@TargetApi(Build.VERSION_CODES.Q)
private object AtraceSink : LookupTraceSink {
    override fun isTracing(): Boolean = Trace.isEnabled()
    override fun beginAsyncSection(name: String, cookie: Int) = Trace.beginAsyncSection(name, cookie)
    override fun endAsyncSection(name: String, cookie: Int) = Trace.endAsyncSection(name, cookie)
}

/**
 * The cookie state machine behind [LookupTracer.ATRACE]: which slices of the one outstanding
 * lookup are open, and which one-time slices already fired. UI-thread and worker-thread
 * boundaries meet here, so every mutation is serialized on [lock]; the untraced path is a
 * single [LookupTraceSink.isTracing] query or a [NO_COOKIE] compare, never an allocation.
 *
 * Stale-cookie rule: a new [beginLookup] closes whatever the previous lookup left open, and any
 * later boundary naming that cookie is dropped. A canceled compute closes from the UI thread
 * mid-compute; the worker's own boundary for it then no-ops.
 */
internal class LookupTracerImpl(private val sink: LookupTraceSink) : LookupTracer {
    private val lock = Any()

    /** The cookie a new engine request is stamped with; read on the UI thread at enqueue. */
    @Volatile
    private var currentCookie = LookupTracer.NO_COOKIE

    private var cookieSerial = 0

    /** Bitmask of the open slices of [currentCookie]: OPEN_* constants below. */
    private var openPhases = 0

    /** Bitmasks of the one-time slices: fired stays set, open clears at [startupEnd]. */
    private var startupFiredMask = 0
    private var startupOpenMask = 0

    override fun issuedCookie(): Int = currentCookie

    override fun beginLookup(): Int {
        if (!sink.isTracing()) return LookupTracer.NO_COOKIE
        synchronized(lock) {
            endOpenPhasesLocked()
            cookieSerial = if (cookieSerial == Int.MAX_VALUE) 1 else cookieSerial + 1
            val cookie = cookieSerial
            sink.beginAsyncSection(LookupTracer.SECTION_LOOKUP, cookie)
            sink.beginAsyncSection(LookupTracer.SECTION_LOOKUP_QUEUE, cookie)
            currentCookie = cookie
            openPhases = OPEN_PARENT or OPEN_QUEUE
            return cookie
        }
    }

    override fun workerStart(cookie: Int) {
        if (cookie == LookupTracer.NO_COOKIE) return
        synchronized(lock) {
            if (cookie != currentCookie || openPhases and OPEN_QUEUE == 0) return
            sink.endAsyncSection(LookupTracer.SECTION_LOOKUP_QUEUE, cookie)
            sink.beginAsyncSection(LookupTracer.SECTION_LOOKUP_COMPUTE, cookie)
            openPhases = openPhases and OPEN_QUEUE.inv() or OPEN_COMPUTE
        }
    }

    override fun workerHandoff(cookie: Int) {
        if (cookie == LookupTracer.NO_COOKIE) return
        synchronized(lock) {
            if (cookie != currentCookie || openPhases and OPEN_COMPUTE == 0) return
            sink.endAsyncSection(LookupTracer.SECTION_LOOKUP_COMPUTE, cookie)
            sink.beginAsyncSection(LookupTracer.SECTION_LOOKUP_HANDOFF, cookie)
            openPhases = openPhases and OPEN_COMPUTE.inv() or OPEN_HANDOFF
        }
    }

    override fun lookupApplied(cookie: Int) {
        if (cookie == LookupTracer.NO_COOKIE) return
        synchronized(lock) {
            if (cookie != currentCookie) return
            // A result that arrived without a worker boundary (the executor failed the
            // submission and delivered an empty answer) leaves the queue slice open.
            endPhaseLocked(OPEN_QUEUE, LookupTracer.SECTION_LOOKUP_QUEUE)
            endPhaseLocked(OPEN_COMPUTE, LookupTracer.SECTION_LOOKUP_COMPUTE)
            endPhaseLocked(OPEN_HANDOFF, LookupTracer.SECTION_LOOKUP_HANDOFF)
            endPhaseLocked(OPEN_PARENT, LookupTracer.SECTION_LOOKUP)
            sink.beginAsyncSection(LookupTracer.SECTION_LOOKUP_FRAME, cookie)
            openPhases = OPEN_FRAME
        }
    }

    override fun frameDrawn(cookie: Int) {
        if (cookie == LookupTracer.NO_COOKIE) return
        synchronized(lock) {
            if (cookie != currentCookie || openPhases and OPEN_FRAME == 0) return
            sink.endAsyncSection(LookupTracer.SECTION_LOOKUP_FRAME, cookie)
            openPhases = 0
            currentCookie = LookupTracer.NO_COOKIE
        }
    }

    override fun cancelLookup(cookie: Int) {
        if (cookie == LookupTracer.NO_COOKIE) return
        synchronized(lock) {
            if (cookie != currentCookie) return
            endOpenPhasesLocked()
        }
    }

    override fun startupBegin(slice: LookupTracer.StartupSlice) {
        if (!sink.isTracing()) return
        synchronized(lock) {
            val bit = 1 shl slice.ordinal
            if (startupFiredMask and bit != 0) return
            startupFiredMask = startupFiredMask or bit
            startupOpenMask = startupOpenMask or bit
            sink.beginAsyncSection(slice.sectionName, LookupTracer.startupCookie(slice))
        }
    }

    override fun startupEnd(slice: LookupTracer.StartupSlice) {
        val bit = 1 shl slice.ordinal
        val wasOpen = synchronized(lock) {
            val open = startupOpenMask and bit != 0
            startupOpenMask = startupOpenMask and bit.inv()
            open
        }
        if (!wasOpen || !sink.isTracing()) return
        sink.endAsyncSection(slice.sectionName, LookupTracer.startupCookie(slice))
    }

    /** Ends one open phase of [currentCookie]; a phase that never opened is skipped. */
    private fun endPhaseLocked(phase: Int, name: String) {
        if (openPhases and phase == 0) return
        sink.endAsyncSection(name, currentCookie)
        openPhases = openPhases and phase.inv()
    }

    /** Ends every open phase of [currentCookie], innermost slice first, and clears the trip. */
    private fun endOpenPhasesLocked() {
        endPhaseLocked(OPEN_FRAME, LookupTracer.SECTION_LOOKUP_FRAME)
        endPhaseLocked(OPEN_HANDOFF, LookupTracer.SECTION_LOOKUP_HANDOFF)
        endPhaseLocked(OPEN_COMPUTE, LookupTracer.SECTION_LOOKUP_COMPUTE)
        endPhaseLocked(OPEN_QUEUE, LookupTracer.SECTION_LOOKUP_QUEUE)
        endPhaseLocked(OPEN_PARENT, LookupTracer.SECTION_LOOKUP)
        currentCookie = LookupTracer.NO_COOKIE
    }

    private companion object {
        private const val OPEN_PARENT = 1
        private const val OPEN_QUEUE = 2
        private const val OPEN_COMPUTE = 4
        private const val OPEN_HANDOFF = 8
        private const val OPEN_FRAME = 16
    }
}

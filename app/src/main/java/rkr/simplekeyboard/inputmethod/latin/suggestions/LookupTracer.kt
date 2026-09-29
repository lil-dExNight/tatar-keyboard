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

import android.os.Build
import android.os.Trace

/**
 * O5 (docs/OPTIMIZE-SECURITY-PLAN-2026-09-29.md): the Perfetto seam for the suggestion round
 * trip — one async slice from "lookup issued on the UI thread" to "its answer applied back on
 * the UI thread", the worker hop and the re-marshal in between being exactly what the existing
 * p95 numbers could not explain before. Async (cookie-keyed) rather than beginSection/endSection
 * because the two ends of the trip run on different threads.
 *
 * A marker pair costs ~10 µs, which is the whole placement discipline: coarse millisecond spans
 * only, never sub-200 µs methods, never anything running per frame.
 *
 * The default is [DISABLED] and every test constructor keeps it: the plain-JVM suite drives
 * [SuggestionsController] without an Android runtime, where android.os.Trace would throw. The
 * production constructor wires [ATRACE] — android.os.Trace is a platform API, so this adds no
 * dependency. When the process is not being traced the markers cost the pair alone; nothing is
 * recorded anywhere.
 */
internal interface LookupTracer {
    fun beginAsync(cookie: Int)
    fun endAsync(cookie: Int)

    companion object {
        /** The Perfetto slice name of the lookup round trip (`TT#` prefix, short on purpose). */
        const val SECTION_LOOKUP: String = "TT#suggestLookup"

        /** No markers at all — the tests' and every unwired controller's shape. */
        val DISABLED: LookupTracer = object : LookupTracer {
            override fun beginAsync(cookie: Int) {}
            override fun endAsync(cookie: Int) {}
        }

        /**
         * Production: platform atrace async sections, framework API, zero dependencies.
         * `Trace.beginAsyncSection`/`endAsyncSection` exist only since API 29 (the synchronous
         * pair is API 18); below 29 the tracer degrades to [DISABLED] — profiling targets modern
         * devices anyway, and an old device simply records nothing.
         */
        val ATRACE: LookupTracer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : LookupTracer {
                    override fun beginAsync(cookie: Int) =
                        Trace.beginAsyncSection(SECTION_LOOKUP, cookie)
                    override fun endAsync(cookie: Int) =
                        Trace.endAsyncSection(SECTION_LOOKUP, cookie)
                }
            } else {
                DISABLED
            }
    }
}

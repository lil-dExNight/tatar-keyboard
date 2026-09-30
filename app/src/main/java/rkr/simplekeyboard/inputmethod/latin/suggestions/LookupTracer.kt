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
 * Perfetto tracing of the suggestion round trip: one async slice from "lookup issued on the UI
 * thread" to "its answer applied back on the UI thread", including the worker hop in between.
 * Async (cookie-keyed) rather than beginSection/endSection because the two ends run on different
 * threads. Markers are not free, so trace only coarse millisecond spans, never per-frame code.
 *
 * The default is [DISABLED]: plain-JVM tests have no Android runtime, where android.os.Trace would
 * throw. The production constructor wires [ATRACE], a platform API (no dependency). When the
 * process is not being traced, nothing is recorded.
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
         * Production: platform atrace async sections. `Trace.beginAsyncSection` exists only since
         * API 29; below that the tracer is [DISABLED] and records nothing.
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

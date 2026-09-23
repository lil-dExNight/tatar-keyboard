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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import android.content.Context
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink

/**
 * The six factors that decide whether ANYTHING may be learned. One predicate, deliberately, rather
 * than six checks spread over the read and the write paths — that is how the two drift apart.
 *
 * Every factor is supplied by `LatinIME`, which is the only place that sees all of them:
 *  1. Tatar suggestions are eligible for this field and subtype (which already implies the field
 *     allows suggestions and does not carry `IME_FLAG_NO_PERSONALIZED_LEARNING`);
 *  2. the personal dictionary setting is ON;
 *  3. the device has been unlocked at least once since boot;
 *  4. the field is not a postal address;
 *  5. — folded into (1): a field with no editor info at all is never eligible, so the
 *     `editorInfo == null` case is closed by the same factor rather than by a separate check;
 *  6. incognito mode is OFF (U8, docs/ROADMAP-P2.md): the pause gates WRITES only — this sink
 *     included, pending counters included — while the read side keeps surfacing what is saved.
 *
 * The conjunction itself is [PersonalLearningGates.mayLearn], pure and JVM-tested; this interface
 * is the shape `LatinIME` hands the sinks.
 */
fun interface PersonalLearningPredicate {
    fun mayLearn(): Boolean
}

/** The subtype whose personal store a write belongs to right now, or null when there is none. */
fun interface ActiveSubtypeSupplier {
    fun get(): String?
}

/**
 * Turns clean-completion events into store mutations, under [PersonalLearningPredicate].
 *
 * This is the ONE place where typing can cause a write, and it is deliberately not in `LatinIME` and
 * not in `SuggestionsController`: the class that sees every keystroke announces an event, and the
 * decision to persist anything lives here, inside the package that owns the file.
 */
object PersonalLearning {

    /**
     * The sink for a FIXED subtype. Kept for callers that know their language at construction time
     * (the tests do); production goes through the [ActiveSubtypeSupplier] overload instead.
     */
    @JvmStatic
    fun sinkFor(
        context: Context,
        subtypeId: String,
        predicate: PersonalLearningPredicate,
    ): WordCompletionSink = sinkFor(context, ActiveSubtypeSupplier { subtypeId }, predicate)

    /**
     * The sink for whatever language is active at the moment of the event.
     *
     * The subtype is resolved per event, not per sink: the sink is built once for the IME's whole
     * lifetime, while the user switches layouts inside a single editor session. A word completed on
     * the Russian layout must reach the Russian store and nothing else — there is no shared
     * "default" store, and writing it to the Tatar one would put Russian words into Tatar
     * suggestions for good. A null subtype (a layout with no dictionary) writes nothing.
     */
    @JvmStatic
    fun sinkFor(
        context: Context,
        activeSubtype: ActiveSubtypeSupplier,
        predicate: PersonalLearningPredicate,
    ): WordCompletionSink = object : WordCompletionSink {
        override fun onCleanCompletion(word: String) {
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalDictionaries.storeFor(context, subtypeId).noteCompletion(word)
        }

        override fun onInputFinished() {
            // The flush is gated too: without the predicate a session that became ineligible could
            // still put what it accumulated on disk.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalDictionaries.storeFor(context, subtypeId).flush()
        }
    }
}

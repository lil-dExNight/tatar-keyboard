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
 * The factors that decide whether anything may be learned, kept in one predicate so the checks
 * cannot drift apart across paths.
 *
 * Every factor is supplied by `LatinIME`, which is the only place that sees all of them:
 *  1. Tatar suggestions are eligible for this field and subtype (which already implies the field
 *     allows suggestions and does not carry `IME_FLAG_NO_PERSONALIZED_LEARNING`);
 *  2. the personal dictionary setting is ON;
 *  3. the device has been unlocked at least once since boot;
 *  4. the field is not a postal address;
 *  5. — folded into (1): a field with no editor info at all is never eligible, so the
 *     `editorInfo == null` case is closed by the same factor rather than by a separate check;
 *  6. learning is not paused (incognito): the pause gates writes only, pending counters
 *     included, while the read side keeps showing what is saved.
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
 * Turns clean-completion and accepted-suggestion events into store mutations, under
 * [PersonalLearningPredicate].
 *
 * This is the only place where typing can cause a write. It is not in `LatinIME` or
 * `SuggestionsController`: the class that sees every keystroke only announces events, and the
 * decision to write lives in the package that owns the file.
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
     * The subtype is resolved per event, not per sink: the sink lives as long as the IME, while the
     * user switches layouts within one session, and a word must reach its own language's store. A
     * null subtype (a layout with no dictionary) writes nothing.
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

        override fun onAcceptedSuggestion(word: String) {
            // The acceptance bump is a write like any other: same predicate, same per-event
            // subtype. The store bumps the counter in memory; the session-end flush writes it.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalDictionaries.storeFor(context, subtypeId).noteAcceptedSuggestion(word)
        }

        override fun onInputFinished() {
            // The flush is gated too, so a session that became ineligible writes nothing.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalDictionaries.storeFor(context, subtypeId).flush()
        }
    }
}

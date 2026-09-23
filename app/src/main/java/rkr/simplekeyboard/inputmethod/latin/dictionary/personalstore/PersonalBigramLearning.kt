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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink

/**
 * Turns clean pair-completion events into store mutations, under [PersonalLearningPredicate] —
 * the pair analogue of [PersonalLearning] (P1 of Phase 2, docs/ROADMAP-P2.md), gated by the very
 * same six factors: suggestions eligible for this field and subtype (which already carries
 * "field allows suggestions", "not `IME_FLAG_NO_PERSONALIZED_LEARNING`" and "an editorInfo
 * exists"), the personal dictionary setting ON, the device unlocked at least once since boot, the
 * field not a postal address, and incognito mode OFF (U8 — the pause gates this sink's writes
 * exactly like the word sink's).
 *
 * This is the ONE place where typing can cause a bigram write, and it is deliberately not in
 * `LatinIME` and not in `SuggestionsController`: the class that sees every keystroke announces an
 * event, and the decision to persist anything lives here, inside the package that owns the file.
 * All content checks — the alphabet filter, the length bounds, the context-membership gate — are
 * the store's; what lives here is only the wiring: predicate first, subtype second, then the event.
 */
object PersonalBigramLearning {

    /**
     * The sink for whatever language is active at the moment of the event.
     *
     * The subtype is resolved per event, not per sink: the sink is built once for the IME's whole
     * lifetime, while the user switches layouts inside a single editor session. A pair completed on
     * the Russian layout must reach the Russian store and nothing else — there is no shared
     * "default" store, and writing it to the Tatar one would put Russian pairs into Tatar
     * predictions for good. A null subtype (a layout with no dictionary) writes nothing.
     */
    @JvmStatic
    fun sinkFor(
        context: Context,
        activeSubtype: ActiveSubtypeSupplier,
        predicate: PersonalLearningPredicate,
    ): PairCompletionSink = object : PairCompletionSink {
        override fun onCleanPairCompletion(contextWord: String, completedWord: String) {
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalBigramDictionaries.storeFor(context, subtypeId).notePair(contextWord, completedWord)
        }

        override fun onAcceptedPrediction(contextWord: String, word: String) {
            // The acceptance bump is a write-adjacent event: the same five factors decide whether
            // it may happen, exactly like the flush below.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalBigramDictionaries.storeFor(context, subtypeId)
                .noteAcceptedPrediction(contextWord, word)
        }

        override fun onInputFinished() {
            // The flush is gated too: without the predicate a session that became ineligible could
            // still put what it accumulated on disk.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalBigramDictionaries.storeFor(context, subtypeId).flush()
        }
    }
}

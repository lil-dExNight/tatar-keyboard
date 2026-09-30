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
 * Turns clean pair-completion events into store mutations, under [PersonalLearningPredicate]; the
 * pair counterpart of [PersonalLearning], with the same factors and the same reasons. Content
 * checks (alphabet, length, context membership) belong to the store; this is only the wiring:
 * predicate first, subtype second, then the event.
 */
object PersonalBigramLearning {

    /**
     * The sink for whatever language is active at the moment of the event. See
     * [PersonalLearning.sinkFor].
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
            // The acceptance bump is gated by the same predicate, like the flush below.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalBigramDictionaries.storeFor(context, subtypeId)
                .noteAcceptedPrediction(contextWord, word)
        }

        override fun onInputFinished() {
            // The flush is gated too, so a session that became ineligible writes nothing.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            PersonalBigramDictionaries.storeFor(context, subtypeId).flush()
        }
    }
}

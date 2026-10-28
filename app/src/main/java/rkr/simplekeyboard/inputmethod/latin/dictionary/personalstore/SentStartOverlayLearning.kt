/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SentenceStartSink

/**
 * Turns sentence-initial word commits into store mutations, under [PersonalLearningPredicate];
 * the sentence-start counterpart of [PersonalLearning], with the same factors and the same
 * reasons: a commit in a private field, in a postal-address field or while learning is paused is
 * never recorded. Content checks (alphabet, length) and the learn threshold belong to the store;
 * this is only the wiring: predicate first, subtype second, then the event.
 */
object SentStartOverlayLearning {

    /**
     * The sink for whatever language is active at the moment of the event. See
     * [PersonalLearning.sinkFor].
     */
    @JvmStatic
    fun sinkFor(
        context: Context,
        activeSubtype: ActiveSubtypeSupplier,
        predicate: PersonalLearningPredicate,
    ): SentenceStartSink = sinkOver(activeSubtype, predicate) { subtypeId ->
        SentStartOverlays.storeFor(context, subtypeId)
    }

    /**
     * The store-injected half of [sinkFor]: the identical gating logic, with store resolution
     * handed in, so a plain JVM test drives the real sink against a real store without a Context.
     */
    internal fun sinkOver(
        activeSubtype: ActiveSubtypeSupplier,
        predicate: PersonalLearningPredicate,
        storeFor: (String) -> SentStartOverlayStore,
    ): SentenceStartSink = object : SentenceStartSink {
        override fun onSentenceStartWord(word: String) {
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            storeFor(subtypeId).noteSentenceStart(word)
        }

        override fun onInputFinished() {
            // The flush is gated too, so a session that became ineligible writes nothing.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            storeFor(subtypeId).flush()
        }
    }
}

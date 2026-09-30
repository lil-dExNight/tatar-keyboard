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

/**
 * The write-side events of learned emoji, as the IME announces them; the same shape as
 * [rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink]. Both halves are
 * raw; normalization, filters and the learn threshold belong to [PersonalEmojiStore].
 */
fun interface PersonalEmojiEventSink {
    /**
     * One clean co-usage: [rawWord] is the committed word the emoji was picked right after,
     * [rawEmoji] the raw emoji cluster. The store saves the pair after enough observations.
     */
    fun noteObservation(rawWord: String, rawEmoji: String)

    /**
     * The user accepted a learned emoji from the strip's emoji cell. A pick that is not a learned
     * entry changes nothing; the store decides.
     */
    fun noteUse(rawWord: String, rawEmoji: String) {}

    /**
     * The editor session ended: the only point where the store writes usage counters and pending
     * hashes, and only if something changed.
     */
    fun onInputFinished() {}
}

/**
 * Turns learned-emoji events into store mutations, under [PersonalLearningPredicate]; the emoji
 * counterpart of [PersonalLearning], with the same factors and the same reasons. The predicate
 * gates every method, flush included. This is only the wiring: predicate first, subtype second,
 * then the event.
 */
object PersonalEmojiLearning {

    /**
     * The sink for whatever language is active at the moment of the event. See
     * [PersonalLearning.sinkFor].
     */
    @JvmStatic
    fun sinkFor(
        context: Context,
        activeSubtype: ActiveSubtypeSupplier,
        predicate: PersonalLearningPredicate,
    ): PersonalEmojiEventSink = sinkOver(activeSubtype, predicate) { subtypeId ->
        PersonalEmojiDictionaries.storeFor(context, subtypeId)
    }

    /**
     * The store-injected half of [sinkFor]: the identical gating logic, with store resolution
     * handed in, so a plain JVM test drives the real sink against a real store without a Context.
     */
    internal fun sinkOver(
        activeSubtype: ActiveSubtypeSupplier,
        predicate: PersonalLearningPredicate,
        storeFor: (String) -> PersonalEmojiStore,
    ): PersonalEmojiEventSink = object : PersonalEmojiEventSink {
        override fun noteObservation(rawWord: String, rawEmoji: String) {
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            storeFor(subtypeId).noteObservation(rawWord, rawEmoji)
        }

        override fun noteUse(rawWord: String, rawEmoji: String) {
            // The acceptance bump is gated by the same predicate, like the flush below.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            storeFor(subtypeId).noteUse(rawWord, rawEmoji)
        }

        override fun onInputFinished() {
            // The flush is gated too, so a session that became ineligible writes nothing.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            storeFor(subtypeId).flush()
        }
    }
}

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
 * The write-side events of the personal word→emoji feature, as the IME announces them — the emoji
 * sibling of [rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink] and its
 * exact shape: ONE abstract method (the co-usage observation) plus two defaulted ones, so a caller
 * that only observes stays a lambda.
 *
 * Both content halves travel RAW, exactly as they stand in the editor: normalization, the alphabet
 * filter, the cluster check and the learn threshold are all the store's business
 * ([PersonalEmojiStore]), never the announcer's.
 */
fun interface PersonalEmojiEventSink {
    /**
     * One clean co-usage: [rawWord] is the committed word the emoji was picked right after,
     * [rawEmoji] the raw emoji cluster itself. The store graduates the pair only after it has seen
     * the threshold's worth of such observations.
     */
    fun noteObservation(rawWord: String, rawEmoji: String)

    /**
     * The user accepted the LEARNED emoji from the strip's tail cell — the usage half of the pinned
     * ranking (usage descending, then frequency). A pick that is not a learned entry changes
     * nothing; the store decides.
     */
    fun noteUse(rawWord: String, rawEmoji: String) {}

    /**
     * The editor session ended: the ONE boundary where the store may put what it accumulated on
     * disk — usage counters and pending hashes, once, and only if something changed. Never per
     * keystroke, never per pick.
     */
    fun onInputFinished() {}
}

/**
 * Turns personal-emoji events into store mutations, under [PersonalLearningPredicate] — the emoji
 * analogue of [PersonalBigramLearning], gated by the very same six factors: suggestions eligible
 * for this field and subtype, the personal dictionary setting ON, the device unlocked at least once
 * since boot, the field not a postal address, and incognito mode OFF. The pause gates EVERY method
 * of this sink, flush included: with incognito on, not even a pending hash is written.
 *
 * This is the ONE place where an emoji pick can cause a personal-store write, and it is
 * deliberately not in `LatinIME` and not in `SuggestionsController`: those classes announce events,
 * and the decision to persist anything lives here, inside the package that owns the file. All
 * content checks are the store's; what lives here is only the wiring: predicate first, subtype
 * second, then the event.
 */
object PersonalEmojiLearning {

    /**
     * The sink for whatever language is active at the moment of the event.
     *
     * The subtype is resolved per event, not per sink: the sink is built once for the IME's whole
     * lifetime, while the user switches layouts inside a single editor session. A co-usage picked
     * on the Russian layout must reach the Russian store and nothing else — there is no shared
     * "default" store, and writing it to the Tatar one would put Russian emoji into Tatar
     * suggestions for good. A null subtype (a layout with no dictionary) writes nothing.
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
            // The acceptance bump is a write-adjacent event: the same six factors decide whether
            // it may happen, exactly like the flush below.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            storeFor(subtypeId).noteUse(rawWord, rawEmoji)
        }

        override fun onInputFinished() {
            // The flush is gated too: without the predicate a session that became ineligible could
            // still put what it accumulated on disk.
            if (!predicate.mayLearn()) return
            val subtypeId = activeSubtype.get() ?: return
            storeFor(subtypeId).flush()
        }
    }
}

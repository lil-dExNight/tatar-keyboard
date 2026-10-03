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

package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.glide.CompositeGlideInventory
import rkr.simplekeyboard.inputmethod.latin.glide.GlideBigramRerank
import rkr.simplekeyboard.inputmethod.latin.glide.GlideComputer
import rkr.simplekeyboard.inputmethod.latin.glide.GlideDecoder
import rkr.simplekeyboard.inputmethod.latin.glide.GlideGeometrySink
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import rkr.simplekeyboard.inputmethod.latin.glide.GlideResult
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordInventory

/**
 * Owns one engine's glide decode side: the lazily built [GlideDecoder] (its word index is built
 * on the first decode, on the engine worker) plus the current layout geometry.
 *
 * Threading: [decodeGlide] runs on the engine's serialized worker (decoder scratch and result
 * buffer are worker-confined); [updateGlideGeometry] is a `@Volatile` swap from the UI thread. A
 * geometry change rebuilds the decoder, because the word index's key indices only make sense
 * against the geometry they were built from. A null or empty geometry returns no candidates.
 *
 * Personal dictionary: a decode reads the personal source's current immutable snapshot (one
 * `@Volatile` read, no I/O) and rebuilds the decoder when the snapshot's identity changed, so one
 * rebuild per learning event, on the worker. An empty snapshot keeps the base inventory
 * unwrapped; [PersonalDictionary.EMPTY] is a singleton, so the identity check does not churn.
 * [dictionaryMembership] is the duplicate check of the composite inventory; it runs only during
 * such a rebuild, never per gesture.
 */
internal class GlideDecoderHost(
    private val inventory: GlideWordInventory,
    private val personal: PersonalCandidateSource = PersonalCandidateSource.EMPTY,
    private val dictionaryMembership: (String) -> Boolean = { false },
    private val glideConstants: GlideDecoder.GlideConstants = GlideDecoder.GlideConstants.TATAR,
) : GlideComputer, GlideGeometrySink {
    @Volatile
    private var geometry: GlideKeyGeometry? = null

    // Worker-confined: touched only inside decodeGlide.
    private var decoder: GlideDecoder? = null
    private var decoderGeometry: GlideKeyGeometry? = null
    private var decoderSnapshot: PersonalDictionary? = null
    private val result = GlideResult()
    // Scratch for the bigram channel's rerank (see GlideBigramRerank).
    private val rerankAdjusted = FloatArray(GlideDecoder.TOP_N)

    /**
     * The bigram table's successors of a context word, count-descending. Set by the owning
     * computer; the provider answers empty until the table attaches, which switches the channel
     * off. Called on the engine worker.
     */
    var bigramSuccessorsProvider: (String) -> List<String> = { emptyList() }

    override fun updateGlideGeometry(geometry: GlideKeyGeometry?) {
        this.geometry = geometry
    }

    /**
     * Drops the lazily built decoder and its [GlideWordIndex], the large structure the idle memory
     * release (LatinIME `MSG_DEALLOCATE_MEMORY`) targets; the next [decodeGlide] rebuilds it.
     * Worker-confined: the caller routes this through the engine's serialized executor.
     */
    fun releaseIndex() {
        decoder = null
        decoderGeometry = null
        decoderSnapshot = null
    }

    override fun decodeGlide(path: GlidePath, contextWord: String?): List<String> {
        val current = geometry ?: return emptyList()
        if (current.isEmpty) return emptyList()
        val snapshot = personal.glideSnapshot()
        var active = decoder
        if (active == null || decoderGeometry !== current || decoderSnapshot !== snapshot) {
            val effective =
                if (snapshot.isEmpty) inventory
                else CompositeGlideInventory(inventory, snapshot, dictionaryMembership)
            active = GlideDecoder(current, effective, glideConstants)
            decoder = active
            decoderGeometry = current
            decoderSnapshot = snapshot
        }
        val count = active.decode(path, result)
        if (count == 0) return emptyList()
        // The bigram channel: rerank the N-best against the committed context word. A missing or
        // unknown context word (the table has no such head) leaves the decode order untouched.
        if (!contextWord.isNullOrEmpty() && count > 1) {
            val successors = try {
                bigramSuccessorsProvider(contextWord)
            } catch (_: RuntimeException) {
                emptyList()
            }
            GlideBigramRerank.rerank(result, successors, glideConstants.bigramRankPenalty, rerankAdjusted)
        }
        val words = ArrayList<String>(result.count)
        for (slot in 0 until result.count) {
            words.add(result.words[slot]!!)
        }
        return words
    }
}

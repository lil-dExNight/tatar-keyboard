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
import rkr.simplekeyboard.inputmethod.latin.glide.GlideComputer
import rkr.simplekeyboard.inputmethod.latin.glide.GlideDecoder
import rkr.simplekeyboard.inputmethod.latin.glide.GlideGeometrySink
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import rkr.simplekeyboard.inputmethod.latin.glide.GlideResult
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordInventory

/**
 * Owns one engine's glide decode side (P7-3, docs/GLIDE-PLAN.md): the lazily built
 * [GlideDecoder] (its word index is built once per dictionary on the FIRST decode — the engine
 * worker is already a background thread) plus the current layout geometry.
 *
 * Threading mirrors the fuzzy pass exactly: [decodeGlide] runs on the engine's serialized worker
 * (the decoder's scratch and the result buffer are worker-confined like the lookup path's);
 * [updateGlideGeometry] is a `@Volatile` swap from the UI thread. A geometry change rebuilds the
 * decoder — the word index's key indices are only meaningful against the geometry they were
 * built from. A null or empty geometry fails closed: no candidates, never an exception.
 *
 * The personal-dictionary integration (docs/GLIDE-PERSONAL.md) rides the same rebuild: a decode
 * reads the personal source's current immutable snapshot (one `@Volatile` hop, no I/O) and
 * rebuilds the decoder when the snapshot's IDENTITY changed — one rebuild per learning event,
 * paid on the worker. An empty snapshot (feature off, nothing learned) keeps the base inventory
 * unwrapped, so the decode stays byte-identical to the pre-personal behavior;
 * [PersonalDictionary.EMPTY] is a singleton, so the identity comparison does not churn.
 * [dictionaryMembership] is the duplicate check of the composite inventory (the dictionary's
 * cold exact-membership read); it runs only inside such a rebuild, never per gesture.
 */
internal class GlideDecoderHost(
    private val inventory: GlideWordInventory,
    private val personal: PersonalCandidateSource = PersonalCandidateSource.EMPTY,
    private val dictionaryMembership: (String) -> Boolean = { false },
) : GlideComputer, GlideGeometrySink {
    @Volatile
    private var geometry: GlideKeyGeometry? = null

    // Worker-confined: touched only inside decodeGlide.
    private var decoder: GlideDecoder? = null
    private var decoderGeometry: GlideKeyGeometry? = null
    private var decoderSnapshot: PersonalDictionary? = null
    private val result = GlideResult()

    override fun updateGlideGeometry(geometry: GlideKeyGeometry?) {
        this.geometry = geometry
    }

    /**
     * O2 (docs/OPTIMIZE-2026-09-25.md): drops the lazily built decoder — its [GlideWordIndex] is
     * the ~6 MB structure the idle memory release (LatinIME `MSG_DEALLOCATE_MEMORY`) targets.
     * The index is a pure derivation of the inventory, the live geometry and the current personal
     * snapshot, so the next [decodeGlide] simply rebuilds it. Worker-confined like every other
     * touch of [decoder]: the caller routes this through the engine's serialized executor, never
     * the UI thread.
     */
    fun releaseIndex() {
        decoder = null
        decoderGeometry = null
        decoderSnapshot = null
    }

    override fun decodeGlide(path: GlidePath): List<String> {
        val current = geometry ?: return emptyList()
        if (current.isEmpty) return emptyList()
        val snapshot = personal.glideSnapshot()
        var active = decoder
        if (active == null || decoderGeometry !== current || decoderSnapshot !== snapshot) {
            val effective =
                if (snapshot.isEmpty) inventory
                else CompositeGlideInventory(inventory, snapshot, dictionaryMembership)
            active = GlideDecoder(current, effective)
            decoder = active
            decoderGeometry = current
            decoderSnapshot = snapshot
        }
        val count = active.decode(path, result)
        if (count == 0) return emptyList()
        val words = ArrayList<String>(count)
        for (slot in 0 until count) {
            words.add(result.words[slot]!!)
        }
        return words
    }
}

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
 */
internal class GlideDecoderHost(
    private val inventory: GlideWordInventory,
) : GlideComputer, GlideGeometrySink {
    @Volatile
    private var geometry: GlideKeyGeometry? = null

    // Worker-confined: touched only inside decodeGlide.
    private var decoder: GlideDecoder? = null
    private var decoderGeometry: GlideKeyGeometry? = null
    private val result = GlideResult()

    override fun updateGlideGeometry(geometry: GlideKeyGeometry?) {
        this.geometry = geometry
    }

    override fun decodeGlide(path: GlidePath): List<String> {
        val current = geometry ?: return emptyList()
        if (current.isEmpty) return emptyList()
        var active = decoder
        if (active == null || decoderGeometry !== current) {
            active = GlideDecoder(current, inventory)
            decoder = active
            decoderGeometry = current
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

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

package rkr.simplekeyboard.inputmethod.latin.glide

/**
 * The decode seam of the glide engine path (P7-3, docs/GLIDE-PLAN.md): one recorded path in,
 * ranked words out. Runs on the engine's serialized worker exactly like the prefix lookup —
 * the [GlideDecoder] behind it is worker-confined.
 */
fun interface GlideComputer {
    fun decodeGlide(path: GlidePath): List<String>
}

/**
 * Receives the current layout's key geometry. The same handoff shape as the fuzzy pass's
 * neighbor table: a `@Volatile` reference swap from the UI thread, read by the serialized
 * worker at decode time. A null or empty geometry fails closed (no candidates).
 */
fun interface GlideGeometrySink {
    fun updateGlideGeometry(geometry: GlideKeyGeometry?)
}

/**
 * The idle memory-release seam (O2, docs/OPTIMIZE-2026-09-25.md): drops the lazily built glide
 * word index so a long-hidden keyboard does not hold ~6 MB of pure derivation. The index
 * rebuilds from the dictionary on the next decode. Runs on the engine's serialized worker —
 * the decoder behind it is worker-confined, so the engine posts this through its executor,
 * never onto the calling (UI) thread.
 */
fun interface GlideIndexReleaser {
    fun releaseGlideIndex()
}


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
 * The decode entry point of glide typing: one recorded path in, ranked words out. Runs on the
 * engine's serialized worker like the prefix lookup; the [GlideDecoder] behind it is
 * worker-confined.
 */
fun interface GlideComputer {
    fun decodeGlide(path: GlidePath): List<String>
}

/**
 * Receives the current layout's key geometry: a `@Volatile` reference swap from the UI thread,
 * read by the serialized worker at decode time (same pattern as the typo-recovery neighbor
 * table). A null or empty geometry yields no candidates.
 */
fun interface GlideGeometrySink {
    fun updateGlideGeometry(geometry: GlideKeyGeometry?)
}

/**
 * Drops the lazily built glide word index so a long-hidden keyboard does not hold derived data;
 * the index is rebuilt from the dictionary on the next decode. The decoder is worker-confined,
 * so the engine posts this through its serialized executor, never on the calling (UI) thread.
 */
fun interface GlideIndexReleaser {
    fun releaseGlideIndex()
}


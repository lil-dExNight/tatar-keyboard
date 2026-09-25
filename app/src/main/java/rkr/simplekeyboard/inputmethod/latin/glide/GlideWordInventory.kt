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
 * The word inventory a glide decoder scores against: the dictionary's entries in dictionary
 * order with their frequencies. This is the decode-side seam of the dictionary integration —
 * the decoder never touches the per-keystroke prefix lookup path.
 *
 * Implementations must serve NFC lower-case words (the shipped dictionaries' pipeline
 * normalization) with strictly positive frequencies; the inventory is read-only and immutable
 * for the lifetime of a decoder.
 *
 * [forEachWord] is the INDEX-BUILD entry point: it runs once per decoder (lazily, off the
 * UI thread), so it may walk cold storage; [wordAt] is the RESULT-MATERIALIZATION entry point:
 * it runs on the engine worker at the end of a decode, for the handful of winning entries only.
 */
interface GlideWordInventory {
    val entryCount: Int

    /**
     * The word of dictionary entry [index] (materializes a String; result-path use only). A
     * composite inventory may answer with the user's SAVED casing here — for a personal entry
     * always, and for a dictionary entry the user's saved spelling when it overrides one
     * (docs/GLIDE-PERSONAL.md); the display-time casing pass treats the answer like any
     * dictionary word.
     */
    fun wordAt(index: Int): String

    /** Visits every entry exactly once, in dictionary order, with its word and frequency. */
    fun forEachWord(visitor: GlideWordVisitor)
}

/** Primitive-friendly visitor (no boxed Long per word) for the index-build walk. */
fun interface GlideWordVisitor {
    fun visit(word: String, frequency: Long)
}

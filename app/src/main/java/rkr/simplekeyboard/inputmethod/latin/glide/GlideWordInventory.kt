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
 * order with their frequencies. The decoder never touches the per-keystroke prefix lookup.
 * Implementations serve NFC lowercase words with strictly positive frequencies and are
 * immutable for the lifetime of a decoder.
 *
 * [forEachWord] builds the index: it runs once per decoder during armed preparation or an
 * unprepared decode, off the UI thread, so it may walk cold storage. [wordAt] materializes
 * results on the engine worker, for the few winning entries of a decode.
 */
interface GlideWordInventory {
    val entryCount: Int

    /**
     * The word of entry [index] (materializes a String; result path only). A composite
     * inventory may return the user's saved casing: always for a personal entry, and for a
     * dictionary entry the user has saved in another casing. See [CompositeGlideInventory].
     */
    fun wordAt(index: Int): String

    /** Visits every entry exactly once, in dictionary order, with its word and frequency. */
    fun forEachWord(visitor: GlideWordVisitor)

    /**
     * The byte-level counterpart of [forEachWord], used by [GlideWordIndex.build] so a
     * dictionary-backed inventory can serve its stored UTF-8 directly instead of materializing
     * a [String] per entry. The bytes in `[0, length)` are exactly `word.toByteArray(UTF_8)` of
     * the word [forEachWord] would yield, in the same order with the same frequencies; the
     * buffer is reused and valid only for the duration of the visit, so the visitor must not
     * retain it. The default re-encodes the String forms through one shared scratch buffer —
     * no per-entry allocation either — and an inventory backed by UTF-8 storage overrides it
     * with its direct walk.
     */
    fun forEachWordUtf8(visitor: GlideWordUtf8Visitor) {
        val encoder = GlideUtf8.Encoder()
        forEachWord { word, frequency ->
            val length = encoder.encode(word)
            visitor.visit(encoder.buffer, length, frequency)
        }
    }
}

/** Primitive-friendly visitor (no boxed Long per word) for the index-build walk. */
fun interface GlideWordVisitor {
    fun visit(word: String, frequency: Long)
}

/**
 * The [GlideWordInventory.forEachWordUtf8] visitor: no String and no boxed Long per word.
 * [wordUtf8] holds the word's UTF-8 in `[0, length)` and is valid only during the call.
 */
fun interface GlideWordUtf8Visitor {
    fun visit(wordUtf8: ByteArray, length: Int, frequency: Long)
}

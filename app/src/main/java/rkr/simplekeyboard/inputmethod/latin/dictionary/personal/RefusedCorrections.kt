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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * An immutable in-memory snapshot of one language's refused corrections: the (typed word →
 * replacement) pairs whose autocorrection the user undid, both words in the normalized lookup form
 * (NFC lowercase), in refusal order (the oldest first).
 *
 * A pair suppresses its correction once its refusal count reaches [REFUSAL_THRESHOLD]. The match is
 * the exact pair only: a different replacement proposed for the same typed word still fires, and so
 * does the same replacement for a different typed word.
 *
 * Every mutation returns a new instance; nothing is changed in place. The store keeps one instance
 * as its worker-side model and publishes it to readers as-is, so there is no separate snapshot
 * type. A plain class without a generated `toString`, because it carries the user's text.
 */
class RefusedCorrections private constructor(
    private val typedWords: Array<String>,
    private val replacements: Array<String>,
    private val refusalCounts: IntArray,
) {
    /**
     * The lookup index of the suppressed pairs, built once at construction (on the store's worker):
     * the read side answers in O(1) per keystroke instead of scanning up to the entry cap. A key is
     * the two words joined by NUL, which neither word can contain (both are letters and marks).
     */
    private val suppressed: Set<String> = run {
        val keys = HashSet<String>()
        for (index in typedWords.indices) {
            if (refusalCounts[index] >= REFUSAL_THRESHOLD) keys.add(pairKey(typedWords[index], replacements[index]))
        }
        keys
    }

    val size: Int
        get() = typedWords.size

    val isEmpty: Boolean
        get() = typedWords.isEmpty()

    fun typedWordAt(index: Int): String = typedWords[index]

    fun replacementAt(index: Int): String = replacements[index]

    fun refusalCountAt(index: Int): Int = refusalCounts[index]

    /**
     * Whether the correction of [typedWord] to [replacement] is refused, both already in the
     * normalized lookup form. True only once the pair's count has reached [REFUSAL_THRESHOLD].
     */
    fun isRefused(typedWord: String, replacement: String): Boolean {
        if (isEmpty) return false
        return pairKey(typedWord, replacement) in suppressed
    }

    /**
     * The snapshot with one more refusal of the pair recorded: the count grows by one (capped at
     * [REFUSAL_THRESHOLD] — a suppressed correction cannot fire, so no further refusal of it can be
     * observed) and the pair moves to the back, so a refusal that keeps happening is the last to be
     * evicted. A new pair past [maxEntries] evicts the oldest refusal first. Returns `this` when
     * nothing changes.
     */
    fun noting(typedWord: String, replacement: String, maxEntries: Int): RefusedCorrections {
        val existing = indexOfPair(typedWord, replacement)
        if (existing >= 0 && refusalCounts[existing] >= REFUSAL_THRESHOLD) return this
        val typed = typedWords.toMutableList()
        val replaced = replacements.toMutableList()
        val counts = refusalCounts.toMutableList()
        val count = if (existing >= 0) {
            val grown = counts[existing] + 1
            typed.removeAt(existing)
            replaced.removeAt(existing)
            counts.removeAt(existing)
            grown
        } else {
            while (typed.size >= maxEntries) {
                typed.removeAt(0)
                replaced.removeAt(0)
                counts.removeAt(0)
            }
            1
        }
        typed.add(typedWord)
        replaced.add(replacement)
        counts.add(count)
        return RefusedCorrections(typed.toTypedArray(), replaced.toTypedArray(), counts.toIntArray())
    }

    /** The in-memory bytes to be written whole to disk, in the frozen `.tref` layout. */
    fun serialize(subtypeTag: String): ByteArray {
        val encodedTyped = typedWords.map { it.toByteArray(StandardCharsets.UTF_8) }
        val encodedReplacements = replacements.map { it.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = (0 until size)
            .sumOf { TrefFormat.RECORD_HEADER_SIZE + encodedTyped[it].size + encodedReplacements[it].size }
        val fileSize = TrefFormat.HEADER_SIZE + payloadSize

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TrefFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(TrefFormat.SCHEMA_ID.toShort())
        buffer.putShort(TrefFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TrefFormat.HEADER_SIZE.toShort())
        buffer.putShort(TrefFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt(size)
        buffer.putInt(payloadSize)
        val tag = ByteArray(TrefFormat.SUBTYPE_TAG_SIZE)
        val tagBytes = subtypeTag.toByteArray(StandardCharsets.US_ASCII)
        tagBytes.copyInto(tag, 0, 0, minOf(tagBytes.size, tag.size))
        buffer.put(tag)
        buffer.put(ByteArray(TrefFormat.CHECKSUM_SIZE))
        for (index in typedWords.indices) {
            buffer.put(encodedTyped[index].size.toByte())
            buffer.put(encodedReplacements[index].size.toByte())
            buffer.put(refusalCounts[index].toByte())
            buffer.put(encodedTyped[index])
            buffer.put(encodedReplacements[index])
        }

        val image = buffer.array()
        val checksum = MessageDigest.getInstance("SHA-256").digest(image)
        checksum.copyInto(image, TrefFormat.CHECKSUM_OFFSET)
        return image
    }

    private fun indexOfPair(typedWord: String, replacement: String): Int {
        for (index in typedWords.indices) {
            if (typedWords[index] == typedWord && replacements[index] == replacement) return index
        }
        return -1
    }

    companion object {
        /**
         * How many times the same correction must be undone before it is remembered across
         * sessions. Two: one undo can be an accident (the undo window is one backspace away), so a
         * single refusal must not mute a correction forever; waiting for a third would make the
         * memory nearly invisible. Within one field session a pair can be refused at most once —
         * the session-scoped refusal list already stops the correction from refiring there — so the
         * count grows across sessions, never twice in one.
         */
        const val REFUSAL_THRESHOLD = 2

        @JvmField
        val EMPTY = RefusedCorrections(emptyArray(), emptyArray(), IntArray(0))

        internal fun of(validated: ValidatedRefusedCorrections): RefusedCorrections {
            if (validated.entryCount == 0) return EMPTY
            return RefusedCorrections(
                validated.typedWords.toTypedArray(),
                validated.replacements.toTypedArray(),
                validated.refusalCounts.toIntArray(),
            )
        }

        private fun pairKey(typedWord: String, replacement: String): String =
            typedWord + "\u0000" + replacement
    }
}

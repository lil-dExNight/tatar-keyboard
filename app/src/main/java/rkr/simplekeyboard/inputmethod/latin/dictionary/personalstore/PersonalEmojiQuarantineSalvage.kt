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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiTextUtils
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * What could be read out of a quarantined `.tpersem` file: the (word, emoji) entries, and whether
 * the file was read to its end. See [PersonalQuarantineSalvage].
 */
internal class PersonalEmojiQuarantineSalvage internal constructor(
    /** Words in the NORMALIZED form (the only form the format stores for them), as read. */
    val words: List<String>,
    /** Emoji clusters in their RAW form, as read. */
    val emojiClusters: List<String>,
    val readToEnd: Boolean,
) {
    val entryCount: Int
        get() = words.size

    companion object {
        /**
         * Reads as much of [file] as parses, for the learned emoji of [requestedSubtypeId]. See
         * [PersonalQuarantineSalvage.read].
         */
        fun read(file: File, requestedSubtypeId: String): PersonalEmojiQuarantineSalvage? {
            val length = try {
                if (file.isFile) file.length() else return null
            } catch (_: Exception) {
                return null
            }
            val alphabet = PersonalSubtypes.alphabetFor(requestedSubtypeId) ?: return NOTHING
            if (length < TpersemFormat.HEADER_SIZE) return NOTHING

            val cap = TpersemFormat.MAX_FILE_SIZE
            val bytes = readAtMost(file, minOf(length, cap).toInt()) ?: return NOTHING
            if (bytes.size < TpersemFormat.HEADER_SIZE) return NOTHING
            // Read up to the cap; see PersonalQuarantineSalvage.
            var readToEnd = length <= cap && bytes.size.toLong() == length

            val header = ByteBuffer.wrap(bytes, 0, TpersemFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(TpersemFormat.MAGIC_SIZE)
            header.get(magic)
            if (!magic.contentEquals(TpersemFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) return NOTHING
            val schemaId = header.short.toInt() and 0xffff
            val formatVersion = header.short.toInt() and 0xffff
            val headerSize = header.short.toInt() and 0xffff
            val checksumAlgorithm = header.short.toInt() and 0xffff
            val entryCount = header.int.toLong() and TpersemFormat.MAX_U32
            val payloadSize = header.int.toLong() and TpersemFormat.MAX_U32
            val subtypeTagBytes = ByteArray(TpersemFormat.SUBTYPE_TAG_SIZE)
            header.get(subtypeTagBytes)

            if (schemaId != TpersemFormat.SCHEMA_ID) return NOTHING
            if (formatVersion != TpersemFormat.FORMAT_VERSION) return NOTHING
            if (headerSize != TpersemFormat.HEADER_SIZE) return NOTHING
            if (checksumAlgorithm != TpersemFormat.CHECKSUM_ALGORITHM_SHA256) return NOTHING
            // Emoji learned in another language never appear in this one's strip.
            if (decodeSubtypeTag(subtypeTagBytes) != requestedSubtypeId) return NOTHING

            if (payloadSize != length - TpersemFormat.HEADER_SIZE) readToEnd = false
            var declared = entryCount
            if (declared > TpersemFormat.MAX_PERSONAL_EMOJI_ENTRIES) {
                declared = TpersemFormat.MAX_PERSONAL_EMOJI_ENTRIES
                readToEnd = false
            }

            val words = ArrayList<String>()
            val emojiClusters = ArrayList<String>()
            val cursor = ByteBuffer
                .wrap(bytes, TpersemFormat.HEADER_SIZE, bytes.size - TpersemFormat.HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            var previousWordBytes: ByteArray? = null
            var previousEmojiBytes: ByteArray? = null
            var index = 0L
            while (index < declared) {
                if (cursor.remaining() < TpersemFormat.RECORD_HEADER_SIZE) break
                val wordByteLength = cursor.get().toInt() and 0xff
                val emojiByteLength = cursor.get().toInt() and 0xff
                cursor.short // usageCount: read to advance the cursor; a restored entry starts fresh.
                val frequencyCount = cursor.short.toInt() and 0xffff
                cursor.int // lastUseSerial: read to advance the cursor; a restored entry starts fresh.
                if (wordByteLength < 1) break
                if (emojiByteLength < 1) break
                if (frequencyCount < 1) break
                if (cursor.remaining() < wordByteLength + emojiByteLength) break
                val wordBytes = ByteArray(wordByteLength)
                cursor.get(wordBytes)
                val emojiBytes = ByteArray(emojiByteLength)
                cursor.get(emojiBytes)

                val word = decodeStrictUtf8(wordBytes) ?: break
                // The word is stored normalized, so it must filter as its own normalized form.
                if (PersonalBigramWordFilter.acceptedNormalizedForm(word, alphabet) != word) break
                val emoji = decodeStrictUtf8(emojiBytes) ?: break
                if (!isSingleEmojiCluster(emoji)) break
                val previousWord = previousWordBytes
                if (previousWord != null && previousEmojiBytes != null) {
                    val wordOrder = compareUnsigned(previousWord, wordBytes)
                    if (wordOrder > 0) break
                    if (wordOrder == 0 && compareUnsigned(previousEmojiBytes, emojiBytes) >= 0) {
                        break
                    }
                }
                previousWordBytes = wordBytes
                previousEmojiBytes = emojiBytes

                words.add(word)
                emojiClusters.add(emoji)
                index++
            }
            if (index != declared || cursor.remaining() != 0) readToEnd = false

            return PersonalEmojiQuarantineSalvage(words, emojiClusters, readToEnd)
        }

        /** A file that exists and yields nothing: no entries, not read to the end. */
        private val NOTHING = PersonalEmojiQuarantineSalvage(emptyList(), emptyList(), false)

        /**
         * The emoji half's content check, the same one the validator enforces: at most
         * [TpersemFormat.MAX_EMOJI_CLUSTER_CHARS] UTF-16 units, and exactly one cluster.
         */
        private fun isSingleEmojiCluster(emoji: String): Boolean =
            emoji.isNotEmpty() &&
                emoji.length <= TpersemFormat.MAX_EMOJI_CLUSTER_CHARS &&
                EmojiTextUtils.trailingEmojiClusterLength(emoji) == emoji.length

        /** Reads at most [limit] bytes. See [PersonalQuarantineSalvage]. */
        private fun readAtMost(file: File, limit: Int): ByteArray? = try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(limit)
                var filled = 0
                while (filled < limit) {
                    val step = input.read(buffer, filled, limit - filled)
                    if (step < 0) break
                    filled += step
                }
                if (filled == limit) buffer else buffer.copyOf(filled)
            }
        } catch (_: Exception) {
            null
        }

        private fun decodeSubtypeTag(tagBytes: ByteArray): String? {
            var end = tagBytes.size
            while (end > 0 && tagBytes[end - 1].toInt() == 0) end--
            for (position in 0 until end) {
                val value = tagBytes[position].toInt() and 0xff
                if (value == 0 || value > 0x7f) return null
            }
            return String(tagBytes, 0, end, StandardCharsets.US_ASCII)
        }

        private fun decodeStrictUtf8(encoded: ByteArray): String? = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString()
        } catch (_: Exception) {
            null
        }

        private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
            val count = minOf(first.size, second.size)
            for (position in 0 until count) {
                val difference = (first[position].toInt() and 0xff) - (second[position].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return first.size - second.size
        }
    }
}

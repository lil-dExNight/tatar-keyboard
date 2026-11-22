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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalWordShapes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The readable prefix of a quarantined `.tsent` file. The checksum is deliberately not required:
 * a truncated file fails it before any record can be recovered. Header, language, content and
 * ordering checks still match `TsentValidator`, and parsing stops at the first bad record.
 *
 * A plain class without a generated `toString`, because it carries the user's words.
 */
internal class SentStartQuarantineSalvage internal constructor(
    /** Words in their original on-disk form, in ascending normalized order. */
    val rawForms: List<String>,
    /** The parallel NFC lowercase forms. */
    val normalizedForms: List<String>,
    val readToEnd: Boolean,
) {
    val wordCount: Int
        get() = rawForms.size

    companion object {
        /** Returns null only when [file] is absent; an unreadable copy still returns [NOTHING]. */
        fun read(file: File, requestedSubtypeId: String): SentStartQuarantineSalvage? {
            val length = try {
                if (file.isFile) file.length() else return null
            } catch (_: Exception) {
                return null
            }
            val alphabet = PersonalSubtypes.alphabetFor(requestedSubtypeId) ?: return NOTHING
            if (length < TsentFormat.HEADER_SIZE) return NOTHING

            val cap = TsentFormat.MAX_FILE_SIZE
            val bytes = readAtMost(file, minOf(length, cap).toInt()) ?: return NOTHING
            if (bytes.size < TsentFormat.HEADER_SIZE) return NOTHING
            var readToEnd = length <= cap && bytes.size.toLong() == length

            val header = ByteBuffer.wrap(bytes, 0, TsentFormat.HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(TsentFormat.MAGIC_SIZE)
            header.get(magic)
            if (!magic.contentEquals(TsentFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
                return NOTHING
            }
            val schemaId = header.short.toInt() and 0xffff
            val formatVersion = header.short.toInt() and 0xffff
            val headerSize = header.short.toInt() and 0xffff
            val checksumAlgorithm = header.short.toInt() and 0xffff
            val entryCount = header.int.toLong() and TsentFormat.MAX_U32
            val payloadSize = header.int.toLong() and TsentFormat.MAX_U32
            val subtypeTagBytes = ByteArray(TsentFormat.SUBTYPE_TAG_SIZE)
            header.get(subtypeTagBytes)

            if (schemaId != TsentFormat.SCHEMA_ID) return NOTHING
            if (formatVersion != TsentFormat.FORMAT_VERSION) return NOTHING
            if (headerSize != TsentFormat.HEADER_SIZE) return NOTHING
            if (checksumAlgorithm != TsentFormat.CHECKSUM_ALGORITHM_SHA256) return NOTHING
            if (decodeSubtypeTag(subtypeTagBytes) != requestedSubtypeId) return NOTHING
            if (payloadSize != length - TsentFormat.HEADER_SIZE) readToEnd = false

            var declared = entryCount
            if (declared > TsentFormat.MAX_SENTSTART_ENTRIES) {
                declared = TsentFormat.MAX_SENTSTART_ENTRIES
                readToEnd = false
            }

            val rawForms = ArrayList<String>(declared.toInt())
            val normalizedForms = ArrayList<String>(declared.toInt())
            val cursor = ByteBuffer
                .wrap(bytes, TsentFormat.HEADER_SIZE, bytes.size - TsentFormat.HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            var previousNormalizedBytes: ByteArray? = null
            var index = 0L
            while (index < declared) {
                if (cursor.remaining() < TsentFormat.RECORD_HEADER_SIZE) break
                val wordByteLength = cursor.get().toInt() and 0xff
                val usageCount = cursor.short.toInt() and 0xffff
                cursor.int // Last-use serial: a restored word starts fresh.
                if (usageCount < 1 || wordByteLength < 1) break
                if (cursor.remaining() < wordByteLength) break
                val encoded = ByteArray(wordByteLength)
                cursor.get(encoded)

                val rawWord = decodeStrictUtf8(encoded) ?: break
                if (TatarWordUtils.classifyCasing(rawWord) == TatarWordUtils.PrefixCasing.MIXED) {
                    break
                }
                val normalized = PersonalWordFilter.normalize(rawWord)
                if (PersonalWordShapes.rejectionReason(
                        normalized,
                        alphabet,
                        TsentFormat.MIN_WORD_CODE_POINTS,
                        TsentFormat.MAX_WORD_CODE_POINTS,
                    ) != null
                ) {
                    break
                }
                val normalizedBytes = normalized.toByteArray(StandardCharsets.UTF_8)
                val previous = previousNormalizedBytes
                if (previous != null && compareUnsigned(previous, normalizedBytes) >= 0) break
                previousNormalizedBytes = normalizedBytes

                rawForms.add(rawWord)
                normalizedForms.add(normalized)
                index++
            }
            if (index != declared || cursor.remaining() != 0) readToEnd = false
            return SentStartQuarantineSalvage(rawForms, normalizedForms, readToEnd)
        }

        private val NOTHING = SentStartQuarantineSalvage(emptyList(), emptyList(), false)

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
                val difference =
                    (first[position].toInt() and 0xff) - (second[position].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return first.size - second.size
        }
    }
}

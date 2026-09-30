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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * What could be read out of a quarantined `.tpers` file: the words, and whether the file was read
 * to its end.
 *
 * A plain class without a generated `toString`, because it carries the user's words. Nothing here
 * is logged. [readToEnd] is true only when every declared record was read, nothing was left over
 * and no cap cut the read short; when it is false the user must be told the rest is lost.
 */
internal class PersonalQuarantineSalvage internal constructor(
    /** Words in their ORIGINAL on-disk form, in ascending normalized order, as read. */
    val rawForms: List<String>,
    /** The parallel NFC lowercase forms. */
    val normalizedForms: List<String>,
    val readToEnd: Boolean,
) {
    val wordCount: Int
        get() = rawForms.size

    companion object {
        /**
         * Reads as much of [file] as parses, for the personal dictionary of [requestedSubtypeId].
         * Returns null when there is no file; an empty salvage with [readToEnd] false when nothing
         * in it can be trusted.
         *
         * The stored checksum is not checked: truncation, the usual failure, breaks it first. The
         * header must match this schema, format and language, and every record must pass the
         * content and ordering checks of `TpersValidator`. The ascending-order check detects a
         * corrupted length byte that puts the cursor mid-payload.
         *
         * Parsing stops at the first bad record; everything before it is kept. Bad input does not
         * throw, but callers still wrap the call in a `try` for I/O errors.
         */
        fun read(file: File, requestedSubtypeId: String): PersonalQuarantineSalvage? {
            val length = try {
                if (file.isFile) file.length() else return null
            } catch (_: Exception) {
                return null
            }
            val alphabet = PersonalSubtypes.alphabetFor(requestedSubtypeId) ?: return NOTHING
            if (length < TpersFormat.HEADER_SIZE) return NOTHING

            val cap = TpersFormat.MAX_FILE_SIZE
            val bytes = readAtMost(file, minOf(length, cap).toInt()) ?: return NOTHING
            if (bytes.size < TpersFormat.HEADER_SIZE) return NOTHING
            // A file larger than the writer could produce is damaged, but its head may still hold
            // words, so it is read up to the cap.
            var readToEnd = length <= cap && bytes.size.toLong() == length

            val header = ByteBuffer.wrap(bytes, 0, TpersFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(TpersFormat.MAGIC_SIZE)
            header.get(magic)
            if (!magic.contentEquals(TpersFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) return NOTHING
            val schemaId = header.short.toInt() and 0xffff
            val formatVersion = header.short.toInt() and 0xffff
            val headerSize = header.short.toInt() and 0xffff
            val checksumAlgorithm = header.short.toInt() and 0xffff
            val entryCount = header.int.toLong() and TpersFormat.MAX_U32
            val payloadSize = header.int.toLong() and TpersFormat.MAX_U32
            val subtypeTagBytes = ByteArray(TpersFormat.SUBTYPE_TAG_SIZE)
            header.get(subtypeTagBytes)

            if (schemaId != TpersFormat.SCHEMA_ID) return NOTHING
            if (formatVersion != TpersFormat.FORMAT_VERSION) return NOTHING
            if (headerSize != TpersFormat.HEADER_SIZE) return NOTHING
            if (checksumAlgorithm != TpersFormat.CHECKSUM_ALGORITHM_SHA256) return NOTHING
            // Words saved in another language never appear in this one's list.
            if (decodeSubtypeTag(subtypeTagBytes) != requestedSubtypeId) return NOTHING

            if (payloadSize != length - TpersFormat.HEADER_SIZE) readToEnd = false
            var declared = entryCount
            if (declared > TpersFormat.MAX_PERSONAL_ENTRIES) {
                declared = TpersFormat.MAX_PERSONAL_ENTRIES
                readToEnd = false
            }

            val rawForms = ArrayList<String>()
            val normalizedForms = ArrayList<String>()
            val cursor = ByteBuffer
                .wrap(bytes, TpersFormat.HEADER_SIZE, bytes.size - TpersFormat.HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            var previousNormalizedBytes: ByteArray? = null
            var index = 0L
            while (index < declared) {
                if (cursor.remaining() < TpersFormat.RECORD_HEADER_SIZE) break
                val wordByteLength = cursor.get().toInt() and 0xff
                val usageCount = cursor.short.toInt() and 0xffff
                cursor.int // lastUseSerial: read to advance the cursor; a restored word starts fresh.
                if (usageCount < 1) break
                if (wordByteLength < 1) break
                if (cursor.remaining() < wordByteLength) break
                val encoded = ByteArray(wordByteLength)
                cursor.get(encoded)

                val rawWord = decodeStrictUtf8(encoded) ?: break
                val normalized = PersonalWordFilter.acceptedNormalizedForm(rawWord, alphabet) ?: break
                val normalizedBytes = normalized.toByteArray(StandardCharsets.UTF_8)
                val previous = previousNormalizedBytes
                if (previous != null && compareUnsigned(previous, normalizedBytes) >= 0) break
                previousNormalizedBytes = normalizedBytes

                rawForms.add(rawWord)
                normalizedForms.add(normalized)
                index++
            }
            if (index != declared || cursor.remaining() != 0) readToEnd = false

            return PersonalQuarantineSalvage(rawForms, normalizedForms, readToEnd)
        }

        /** A file that exists and yields nothing: no words, not read to the end. */
        private val NOTHING = PersonalQuarantineSalvage(emptyList(), emptyList(), false)

        /**
         * Reads at most [limit] bytes, unlike [File.readBytes], so a corrupt length field cannot
         * request a huge array.
         */
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

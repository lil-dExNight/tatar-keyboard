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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * What could be read out of a quarantined `.tpersb` copy (P1 of Phase 2, docs/ROADMAP-P2.md): the
 * pairs, and whether the file ran out before it said it would. The bigram analogue of
 * [PersonalQuarantineSalvage].
 *
 * NOT a Kotlin `data class`: it carries the user's own words, and a synthesised `toString` would
 * print them at the first interpolation. Nothing here is ever logged.
 *
 * [readToEnd] is the honesty flag, exactly as in the words salvage: it is true only when every
 * record the header declared was read, nothing was left over and no cap cut the read short, and
 * whenever it is false the user must be told that the rest of the copy is damaged and lost.
 */
internal class PersonalBigramQuarantineSalvage internal constructor(
    /** Context words in the NORMALIZED form (the only form the format stores for them), as read. */
    val contexts: List<String>,
    /** Successor words in their ORIGINAL on-disk form, as read. */
    val successorRawForms: List<String>,
    /** The parallel NFC lowercase successor forms. */
    val successorNormalizedForms: List<String>,
    val readToEnd: Boolean,
) {
    val pairCount: Int
        get() = contexts.size

    companion object {
        /**
         * Reads as much of [file] as parses, for the personal bigrams of [requestedSubtypeId].
         * Returns null when there is no copy at all; an empty salvage with [readToEnd] false when a
         * copy exists but nothing in it can be trusted.
         *
         * The stored checksum is deliberately NOT consulted — a truncated write is the ordinary way
         * this file breaks, and the checksum is the first thing truncation destroys. What stands in
         * its place is the per-record contract: the header must identify this exact schema, format
         * and LANGUAGE, and every record must pass the same content checks the validator enforces,
         * including the pair-key ascending order, which earns its keep here as a resync detector.
         *
         * Parsing stops at the FIRST record that violates anything; everything before it is kept.
         * Nothing in here throws on bad input — a broken file has no right to end a process — but
         * callers still run it inside their own `try`, because `File` I/O can fail for its own
         * reasons.
         */
        fun read(file: File, requestedSubtypeId: String): PersonalBigramQuarantineSalvage? {
            val length = try {
                if (file.isFile) file.length() else return null
            } catch (_: Exception) {
                return null
            }
            val alphabet = PersonalSubtypes.alphabetFor(requestedSubtypeId) ?: return NOTHING
            if (length < TpersbFormat.HEADER_SIZE) return NOTHING

            val cap = TpersbFormat.MAX_FILE_SIZE
            val bytes = readAtMost(file, minOf(length, cap).toInt()) ?: return NOTHING
            if (bytes.size < TpersbFormat.HEADER_SIZE) return NOTHING
            // A file longer than the writer could ever produce is already not whole, but its head
            // may still hold pairs, so it is read up to the cap rather than refused.
            var readToEnd = length <= cap && bytes.size.toLong() == length

            val header = ByteBuffer.wrap(bytes, 0, TpersbFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(TpersbFormat.MAGIC_SIZE)
            header.get(magic)
            if (!magic.contentEquals(TpersbFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) return NOTHING
            val schemaId = header.short.toInt() and 0xffff
            val formatVersion = header.short.toInt() and 0xffff
            val headerSize = header.short.toInt() and 0xffff
            val checksumAlgorithm = header.short.toInt() and 0xffff
            val pairCount = header.int.toLong() and TpersbFormat.MAX_U32
            val payloadSize = header.int.toLong() and TpersbFormat.MAX_U32
            val subtypeTagBytes = ByteArray(TpersbFormat.SUBTYPE_TAG_SIZE)
            header.get(subtypeTagBytes)

            if (schemaId != TpersbFormat.SCHEMA_ID) return NOTHING
            if (formatVersion != TpersbFormat.FORMAT_VERSION) return NOTHING
            if (headerSize != TpersbFormat.HEADER_SIZE) return NOTHING
            if (checksumAlgorithm != TpersbFormat.CHECKSUM_ALGORITHM_SHA256) return NOTHING
            // The language tag is not negotiable: pairs saved while writing another language have
            // no business appearing in this one's predictions, however readable they are.
            if (decodeSubtypeTag(subtypeTagBytes) != requestedSubtypeId) return NOTHING

            if (payloadSize != length - TpersbFormat.HEADER_SIZE) readToEnd = false
            var declared = pairCount
            if (declared > TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS) {
                declared = TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS
                readToEnd = false
            }

            val contexts = ArrayList<String>()
            val successorRawForms = ArrayList<String>()
            val successorNormalizedForms = ArrayList<String>()
            val cursor = ByteBuffer
                .wrap(bytes, TpersbFormat.HEADER_SIZE, bytes.size - TpersbFormat.HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            var previousContextBytes: ByteArray? = null
            var previousSuccessorBytes: ByteArray? = null
            var index = 0L
            while (index < declared) {
                if (cursor.remaining() < TpersbFormat.RECORD_HEADER_SIZE) break
                val contextByteLength = cursor.get().toInt() and 0xff
                val successorByteLength = cursor.get().toInt() and 0xff
                cursor.short // usageCount: read to advance the cursor; a restored pair starts fresh.
                val frequencyCount = cursor.short.toInt() and 0xffff
                cursor.int // lastUseSerial: read to advance the cursor; a restored pair starts fresh.
                if (contextByteLength < 1) break
                if (successorByteLength < 1) break
                if (frequencyCount < 1) break
                if (cursor.remaining() < contextByteLength + successorByteLength) break
                val contextBytes = ByteArray(contextByteLength)
                cursor.get(contextBytes)
                val successorBytes = ByteArray(successorByteLength)
                cursor.get(successorBytes)

                val context = decodeStrictUtf8(contextBytes) ?: break
                // The context is stored normalized, so it must filter as its own normalized form.
                if (PersonalBigramWordFilter.acceptedNormalizedForm(context, alphabet) != context) break
                val successorRaw = decodeStrictUtf8(successorBytes) ?: break
                val successorNormalized =
                    PersonalBigramWordFilter.acceptedNormalizedForm(successorRaw, alphabet) ?: break
                val previousContext = previousContextBytes
                if (previousContext != null && previousSuccessorBytes != null) {
                    val contextOrder = compareUnsigned(previousContext, contextBytes)
                    if (contextOrder > 0) break
                    if (contextOrder == 0 &&
                        compareUnsigned(previousSuccessorBytes, successorBytes) >= 0
                    ) {
                        break
                    }
                }
                previousContextBytes = contextBytes
                previousSuccessorBytes = successorBytes

                contexts.add(context)
                successorRawForms.add(successorRaw)
                successorNormalizedForms.add(successorNormalized)
                index++
            }
            if (index != declared || cursor.remaining() != 0) readToEnd = false

            return PersonalBigramQuarantineSalvage(
                contexts, successorRawForms, successorNormalizedForms, readToEnd,
            )
        }

        /** A copy that exists and yields nothing: no pairs, and certainly not read to the end. */
        private val NOTHING = PersonalBigramQuarantineSalvage(emptyList(), emptyList(), emptyList(), false)

        /**
         * Reads at most [limit] bytes. Deliberately capped rather than [File.readBytes]: a corrupt
         * length field is exactly the kind of thing that would otherwise ask for a several-hundred-
         * megabyte array on a phone that has none.
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

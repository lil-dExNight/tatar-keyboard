/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TsentFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.ValidatedSentStartOverlay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The immutable in-memory model of one subtype's sentence-initial overlay: the mutation and
 * LRU-eviction logic, kept apart from all I/O so plain JVM tests cover it. The small counterpart
 * of [PersonalEntries]: the same four parallel arrays ordered by the NORMALIZED form ascending
 * (byte-unsigned order of the UTF-8 encoding — the order `TsentValidator` enforces on disk), a
 * monotonic file serial driving LRU, and a hard record cap, [TsentFormat.MAX_SENTSTART_ENTRIES]
 * in production.
 *
 * Every mutation returns a new instance; nothing is changed in place. A plain class without a
 * generated `toString`, because it carries the user's words.
 */
internal class SentStartOverlayEntries private constructor(
    private val rawForms: Array<String>,
    private val normalizedForms: Array<String>,
    private val usageCounts: IntArray,
    private val lastUseSerials: LongArray,
    val nextSerial: Long,
    private val maxEntries: Int,
) {
    val size: Int
        get() = rawForms.size

    val isEmpty: Boolean
        get() = rawForms.isEmpty()

    fun containsNormalized(normalized: String): Boolean = indexOfNormalized(normalized) >= 0

    /**
     * Records one sentence-initial commit of [normalized] (storing [rawWord] as its on-disk form
     * when the word is new): a saved word gets a counter bump and a touched LRU serial and keeps
     * its stored raw form, a new word enters with count 1. When the result exceeds [maxEntries]
     * the entry with the smallest last-use serial is evicted. Returns a new instance.
     */
    fun noted(rawWord: String, normalized: String): SentStartOverlayEntries {
        val existing = indexOfNormalized(normalized)
        val raw = rawForms.toMutableList()
        val norm = normalizedForms.toMutableList()
        val counts = usageCounts.toMutableList()
        val serials = lastUseSerials.toMutableList()

        if (existing >= 0) {
            counts[existing] = incrementCapped(counts[existing])
            serials[existing] = nextSerial
        } else {
            val position = insertionPoint(normalized)
            raw.add(position, rawWord)
            norm.add(position, normalized)
            counts.add(position, 1)
            serials.add(position, nextSerial)
        }

        if (raw.size > maxEntries) {
            val victim = minSerialIndex(serials)
            raw.removeAt(victim)
            norm.removeAt(victim)
            counts.removeAt(victim)
            serials.removeAt(victim)
        }

        return of(raw, norm, counts, serials, nextSerial + 1, maxEntries)
    }

    /**
     * The strip order: raw forms by usage count descending, ties broken by last-use serial
     * descending (the most recent first). Computed at publish time, never on the strip's thread.
     */
    fun wordsByUsage(): List<String> {
        if (isEmpty) return emptyList()
        val order = rawForms.indices.sortedWith(
            compareByDescending<Int> { usageCounts[it] }.thenByDescending { lastUseSerials[it] }
        )
        return order.map { rawForms[it] }
    }

    /** The in-memory bytes to be written whole to disk, in the frozen `.tsent` layout. */
    fun serialize(subtypeTag: String): ByteArray {
        val encoded = rawForms.map { it.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = encoded.sumOf { TsentFormat.RECORD_HEADER_SIZE + it.size }
        val fileSize = TsentFormat.HEADER_SIZE + payloadSize

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TsentFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(TsentFormat.SCHEMA_ID.toShort())
        buffer.putShort(TsentFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TsentFormat.HEADER_SIZE.toShort())
        buffer.putShort(TsentFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt(size)
        buffer.putInt(payloadSize)
        val tag = ByteArray(TsentFormat.SUBTYPE_TAG_SIZE)
        val tagBytes = subtypeTag.toByteArray(StandardCharsets.US_ASCII)
        tagBytes.copyInto(tag, 0, 0, minOf(tagBytes.size, tag.size))
        buffer.put(tag)
        buffer.put(ByteArray(TsentFormat.CHECKSUM_SIZE))
        for (index in rawForms.indices) {
            val bytes = encoded[index]
            buffer.put(bytes.size.toByte())
            buffer.putShort(usageCounts[index].toShort())
            buffer.putInt(lastUseSerials[index].toInt())
            buffer.put(bytes)
        }

        val image = buffer.array()
        image.fill(0, TsentFormat.CHECKSUM_OFFSET, TsentFormat.CHECKSUM_OFFSET + TsentFormat.CHECKSUM_SIZE)
        val checksum = MessageDigest.getInstance("SHA-256").digest(image)
        checksum.copyInto(image, TsentFormat.CHECKSUM_OFFSET)
        return image
    }

    private fun indexOfNormalized(normalized: String): Int {
        var low = 0
        var high = normalizedForms.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val order = compareNormalized(normalizedForms[mid], normalized)
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun insertionPoint(normalized: String): Int {
        var low = 0
        var high = normalizedForms.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareNormalized(normalizedForms[mid], normalized) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        fun empty(maxEntries: Int = TsentFormat.MAX_SENTSTART_ENTRIES.toInt()): SentStartOverlayEntries =
            SentStartOverlayEntries(emptyArray(), emptyArray(), IntArray(0), LongArray(0), 1L, maxEntries)

        fun fromValidated(
            validated: ValidatedSentStartOverlay,
            maxEntries: Int = TsentFormat.MAX_SENTSTART_ENTRIES.toInt(),
        ): SentStartOverlayEntries {
            val maxSerial = validated.lastUseSerials.maxOrNull() ?: 0L
            return SentStartOverlayEntries(
                validated.rawForms.toTypedArray(),
                validated.normalizedForms.toTypedArray(),
                validated.usageCounts.copyOf(),
                validated.lastUseSerials.copyOf(),
                maxSerial + 1L,
                maxEntries,
            )
        }

        private fun of(
            raw: List<String>,
            norm: List<String>,
            counts: List<Int>,
            serials: List<Long>,
            nextSerial: Long,
            maxEntries: Int,
        ): SentStartOverlayEntries = SentStartOverlayEntries(
            raw.toTypedArray(),
            norm.toTypedArray(),
            counts.toIntArray(),
            serials.toLongArray(),
            nextSerial,
            maxEntries,
        )

        private fun incrementCapped(count: Int): Int =
            if (count >= TsentFormat.MAX_U16.toInt()) TsentFormat.MAX_U16.toInt() else count + 1

        private fun minSerialIndex(serials: List<Long>): Int {
            var victim = 0
            for (index in 1 until serials.size) {
                if (serials[index] < serials[victim]) victim = index
            }
            return victim
        }

        /**
         * Compares two normalized forms by their UTF-8 bytes, unsigned — the exact order
         * `TsentValidator` requires on disk. For the Tatar and Russian Cyrillic alphabets (all
         * BMP) this coincides with `String` natural order.
         */
        private fun compareNormalized(first: String, second: String): Int {
            val a = first.toByteArray(StandardCharsets.UTF_8)
            val b = second.toByteArray(StandardCharsets.UTF_8)
            val count = minOf(a.size, b.size)
            for (index in 0 until count) {
                val difference = (a[index].toInt() and 0xff) - (b[index].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return a.size - b.size
        }
    }
}

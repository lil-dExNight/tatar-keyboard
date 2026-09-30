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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.ValidatedPersonalDictionary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The immutable in-memory model of one subtype's personal dictionary: the mutation and LRU-eviction
 * logic, kept apart from all I/O so plain JVM tests cover it.
 *
 * Three parallel arrays plus a parallel serial array, all ordered by the NORMALIZED form ascending
 * (byte-unsigned order of the UTF-8 encoding — the same order `TpersValidator` enforces on disk):
 * - [rawFormAt] — words as the user entered them (persisted and shown);
 * - [normalizedFormAt] — the NFC lowercase forms used for search, dedup and equality;
 * - [usageCountAt] — per-word usage counters (u16, >= 1);
 * - [lastUseSerialAt] — per-word monotonic last-use serials (u32) driving LRU.
 *
 * Every mutation returns a new instance; nothing is changed in place. A plain class without a
 * generated `toString`, because it carries the user's words.
 *
 * LRU is keyed by a monotonic file serial ([nextSerial]), never the system clock: eviction removes
 * the entry with the smallest last-use serial. The record cap [maxEntries] is injectable so the
 * eviction rule is testable at small sizes; production uses [TpersFormat.MAX_PERSONAL_ENTRIES].
 */
internal class PersonalEntries private constructor(
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

    fun rawFormAt(index: Int): String = rawForms[index]
    fun normalizedFormAt(index: Int): String = normalizedForms[index]
    fun usageCountAt(index: Int): Int = usageCounts[index]
    fun lastUseSerialAt(index: Int): Long = lastUseSerials[index]

    fun containsNormalized(normalized: String): Boolean = indexOfNormalized(normalized) >= 0

    /**
     * Adds [normalized] (storing [rawWord] as its on-disk form) or, if it is already present, bumps
     * its usage counter and touches its LRU serial. When the result exceeds [maxEntries] the entry
     * with the smallest last-use serial is evicted. Returns a new instance.
     *
     * An existing entry keeps its stored raw form: a later add with different casing does not
     * overwrite it.
     */
    fun upsert(rawWord: String, normalized: String): PersonalEntries {
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
     * Records a use of an EXISTING [normalized] entry (an accepted personal suggestion): bumps its
     * counter and touches its LRU serial. Returns a new instance, or null when the word is absent
     * (no phantom entry is ever created). The size does not change, so nothing is evicted.
     */
    fun noteUse(normalized: String): PersonalEntries? {
        val index = indexOfNormalized(normalized)
        if (index < 0) return null
        val counts = usageCounts.copyOf()
        val serials = lastUseSerials.copyOf()
        counts[index] = incrementCapped(counts[index])
        serials[index] = nextSerial
        return PersonalEntries(rawForms, normalizedForms, counts, serials, nextSerial + 1, maxEntries)
    }

    /** Removes [normalized] if present, returning a new instance; returns `this` unchanged if absent. */
    fun remove(normalized: String): PersonalEntries {
        val index = indexOfNormalized(normalized)
        if (index < 0) return this
        val raw = rawForms.toMutableList().apply { removeAt(index) }
        val norm = normalizedForms.toMutableList().apply { removeAt(index) }
        val counts = usageCounts.toMutableList().apply { removeAt(index) }
        val serials = lastUseSerials.toMutableList().apply { removeAt(index) }
        return of(raw, norm, counts, serials, nextSerial, maxEntries)
    }

    /** The in-memory bytes to be written whole to disk, in the frozen `.tpers` layout. */
    fun serialize(subtypeTag: String): ByteArray {
        val encoded = rawForms.map { it.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = encoded.sumOf { TpersFormat.RECORD_HEADER_SIZE + it.size }
        val fileSize = TpersFormat.HEADER_SIZE + payloadSize

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TpersFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(TpersFormat.SCHEMA_ID.toShort())
        buffer.putShort(TpersFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TpersFormat.HEADER_SIZE.toShort())
        buffer.putShort(TpersFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt(size)
        buffer.putInt(payloadSize)
        val tag = ByteArray(TpersFormat.SUBTYPE_TAG_SIZE)
        val tagBytes = subtypeTag.toByteArray(StandardCharsets.US_ASCII)
        tagBytes.copyInto(tag, 0, 0, minOf(tagBytes.size, tag.size))
        buffer.put(tag)
        buffer.put(ByteArray(TpersFormat.CHECKSUM_SIZE))
        for (index in rawForms.indices) {
            val bytes = encoded[index]
            buffer.put(bytes.size.toByte())
            buffer.putShort(usageCounts[index].toShort())
            buffer.putInt(lastUseSerials[index].toInt())
            buffer.put(bytes)
        }

        val image = buffer.array()
        image.fill(0, TpersFormat.CHECKSUM_OFFSET, TpersFormat.CHECKSUM_OFFSET + TpersFormat.CHECKSUM_SIZE)
        val checksum = MessageDigest.getInstance("SHA-256").digest(image)
        checksum.copyInto(image, TpersFormat.CHECKSUM_OFFSET)
        return image
    }

    /** A fresh immutable snapshot for the engine's worker thread. */
    fun toSnapshot(subtypeTag: String): PersonalDictionary {
        if (isEmpty) return PersonalDictionary.EMPTY
        return PersonalDictionary.of(
            ValidatedPersonalDictionary(
                rawForms = rawForms.toList(),
                normalizedForms = normalizedForms.toList(),
                usageCounts = usageCounts.copyOf(),
                lastUseSerials = lastUseSerials.copyOf(),
                subtypeTag = subtypeTag,
            ),
        )
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
        fun empty(maxEntries: Int = TpersFormat.MAX_PERSONAL_ENTRIES.toInt()): PersonalEntries =
            PersonalEntries(emptyArray(), emptyArray(), IntArray(0), LongArray(0), 1L, maxEntries)

        fun fromValidated(
            validated: ValidatedPersonalDictionary,
            maxEntries: Int = TpersFormat.MAX_PERSONAL_ENTRIES.toInt(),
        ): PersonalEntries {
            val maxSerial = validated.lastUseSerials.maxOrNull() ?: 0L
            return PersonalEntries(
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
        ): PersonalEntries = PersonalEntries(
            raw.toTypedArray(),
            norm.toTypedArray(),
            counts.toIntArray(),
            serials.toLongArray(),
            nextSerial,
            maxEntries,
        )

        private fun incrementCapped(count: Int): Int =
            if (count >= TpersFormat.MAX_U16.toInt()) TpersFormat.MAX_U16.toInt() else count + 1

        private fun minSerialIndex(serials: List<Long>): Int {
            var victim = 0
            for (index in 1 until serials.size) {
                if (serials[index] < serials[victim]) victim = index
            }
            return victim
        }

        /**
         * Compares two normalized forms by their UTF-8 bytes, unsigned — the exact order
         * `TpersValidator` requires on disk. For the Tatar Cyrillic alphabet (all BMP) this
         * coincides with `String` natural order, which is what `PersonalDictionary`'s binary search
         * assumes, so writer, validator and reader all agree.
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

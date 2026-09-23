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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.ValidatedPersonalBigrams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The immutable in-memory model of one subtype's personal bigrams (P1 of Phase 2,
 * docs/ROADMAP-P2.md), holding the PURE mutation and LRU-eviction logic kept deliberately apart
 * from all I/O so it is covered by plain JVM tests — the exact role [PersonalEntries] has for the
 * words store.
 *
 * Parallel arrays plus a parallel serial array, all ordered by the pair key ascending — the
 * normalized context first, then the normalized successor, both compared as unsigned UTF-8 bytes
 * (the order [rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbValidator] enforces
 * on disk):
 * - [contextAt] — normalized context words (lookup keys, never displayed);
 * - [successorRawFormAt] — successors as the user typed them (persisted and shown);
 * - [successorNormalizedFormAt] — the NFC lowercase successor forms used for ordering and dedup;
 * - [usageCountAt] — accepted-prediction counters (taps), u16, >= 0;
 * - [frequencyCountAt] — clean typed-observation counters, u16, >= 1;
 * - [lastUseSerialAt] — monotonic last-use serials (u32) driving LRU.
 *
 * Every mutation returns a NEW instance; nothing is changed in place. NOT a Kotlin `data class`:
 * it carries the user's words, and a synthesised `toString` would print them at the first
 * interpolation.
 *
 * LRU is keyed by the monotonic file serial ([nextSerial]), never the system clock: eviction
 * removes the pair with the smallest last-use serial. The pair cap [maxPairs] is injectable so the
 * eviction rule is testable at small sizes; production uses
 * [TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS].
 */
internal class PersonalBigramEntries private constructor(
    private val contexts: Array<String>,
    private val successorRawForms: Array<String>,
    private val successorNormalizedForms: Array<String>,
    private val usageCounts: IntArray,
    private val frequencyCounts: IntArray,
    private val lastUseSerials: LongArray,
    val nextSerial: Long,
    private val maxPairs: Int,
) {
    val size: Int
        get() = contexts.size

    val isEmpty: Boolean
        get() = contexts.isEmpty()

    fun contextAt(index: Int): String = contexts[index]
    fun successorRawFormAt(index: Int): String = successorRawForms[index]
    fun successorNormalizedFormAt(index: Int): String = successorNormalizedForms[index]
    fun usageCountAt(index: Int): Int = usageCounts[index]
    fun frequencyCountAt(index: Int): Int = frequencyCounts[index]
    fun lastUseSerialAt(index: Int): Long = lastUseSerials[index]

    fun containsPair(normalizedContext: String, normalizedSuccessor: String): Boolean =
        indexOfPair(normalizedContext, normalizedSuccessor) >= 0

    /**
     * Adds the pair (storing [successorRaw] as its on-disk form) or, if it is already present,
     * adds [frequencyDelta] to its observation counter and touches its LRU serial. A fresh pair
     * starts with usage 0 and frequency [frequencyDelta] — graduation passes the learn threshold
     * itself, because the pair really was observed that many times. When the result exceeds
     * [maxPairs] the pair with the smallest last-use serial is evicted. Returns a new instance.
     *
     * An existing pair keeps its already-stored successor raw form (its casing is not overwritten
     * by a later observation of a differently-cased spelling), exactly like the words store.
     */
    fun upsert(
        normalizedContext: String,
        successorRaw: String,
        normalizedSuccessor: String,
        frequencyDelta: Int,
    ): PersonalBigramEntries {
        val ctx = contexts.toMutableList()
        val raw = successorRawForms.toMutableList()
        val norm = successorNormalizedForms.toMutableList()
        val usage = usageCounts.toMutableList()
        val frequency = frequencyCounts.toMutableList()
        val serials = lastUseSerials.toMutableList()

        val existing = indexOfPair(normalizedContext, normalizedSuccessor)
        if (existing >= 0) {
            frequency[existing] = incrementCapped(frequency[existing], frequencyDelta)
            serials[existing] = nextSerial
        } else {
            val position = insertionPoint(normalizedContext, normalizedSuccessor)
            ctx.add(position, normalizedContext)
            raw.add(position, successorRaw)
            norm.add(position, normalizedSuccessor)
            usage.add(position, 0)
            frequency.add(position, minOf(frequencyDelta, TpersbFormat.MAX_U16.toInt()))
            serials.add(position, nextSerial)
        }

        if (ctx.size > maxPairs) {
            val victim = minSerialIndex(serials)
            ctx.removeAt(victim)
            raw.removeAt(victim)
            norm.removeAt(victim)
            usage.removeAt(victim)
            frequency.removeAt(victim)
            serials.removeAt(victim)
        }

        return of(ctx, raw, norm, usage, frequency, serials, nextSerial + 1, maxPairs)
    }

    /**
     * Records one more clean typed observation of an EXISTING pair: bumps the frequency counter
     * and touches the LRU serial. Returns a new instance, or null when the pair is absent (no
     * phantom pair is ever created). The size does not change, so nothing is evicted.
     */
    fun noteObservation(normalizedContext: String, normalizedSuccessor: String): PersonalBigramEntries? {
        val index = indexOfPair(normalizedContext, normalizedSuccessor)
        if (index < 0) return null
        val frequency = frequencyCounts.copyOf()
        val serials = lastUseSerials.copyOf()
        frequency[index] = incrementCapped(frequency[index], 1)
        serials[index] = nextSerial
        return PersonalBigramEntries(
            contexts, successorRawForms, successorNormalizedForms, usageCounts, frequency, serials,
            nextSerial + 1, maxPairs,
        )
    }

    /**
     * Records an accepted personal prediction as a use: bumps the usage counter and the LRU
     * serial. Returns a new instance, or null when the pair is absent — a tapped static successor,
     * word form or fallback word takes exactly this branch and nothing happens.
     */
    fun noteUse(normalizedContext: String, normalizedSuccessor: String): PersonalBigramEntries? {
        val index = indexOfPair(normalizedContext, normalizedSuccessor)
        if (index < 0) return null
        val usage = usageCounts.copyOf()
        val serials = lastUseSerials.copyOf()
        usage[index] = incrementCapped(usage[index], 1)
        serials[index] = nextSerial
        return PersonalBigramEntries(
            contexts, successorRawForms, successorNormalizedForms, usage, frequencyCounts, serials,
            nextSerial + 1, maxPairs,
        )
    }

    /** Removes the pair if present, returning a new instance; returns `this` unchanged if absent. */
    fun remove(normalizedContext: String, normalizedSuccessor: String): PersonalBigramEntries {
        val index = indexOfPair(normalizedContext, normalizedSuccessor)
        if (index < 0) return this
        val ctx = contexts.toMutableList().apply { removeAt(index) }
        val raw = successorRawForms.toMutableList().apply { removeAt(index) }
        val norm = successorNormalizedForms.toMutableList().apply { removeAt(index) }
        val usage = usageCounts.toMutableList().apply { removeAt(index) }
        val frequency = frequencyCounts.toMutableList().apply { removeAt(index) }
        val serials = lastUseSerials.toMutableList().apply { removeAt(index) }
        return of(ctx, raw, norm, usage, frequency, serials, nextSerial, maxPairs)
    }

    /** The in-memory bytes to be written whole to disk, in the frozen `.tpersb` layout. */
    fun serialize(subtypeTag: String): ByteArray {
        val encodedContexts = contexts.map { it.toByteArray(StandardCharsets.UTF_8) }
        val encodedSuccessors = successorRawForms.map { it.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = (0 until size).sumOf {
            TpersbFormat.RECORD_HEADER_SIZE + encodedContexts[it].size + encodedSuccessors[it].size
        }
        val fileSize = TpersbFormat.HEADER_SIZE + payloadSize

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TpersbFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(TpersbFormat.SCHEMA_ID.toShort())
        buffer.putShort(TpersbFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TpersbFormat.HEADER_SIZE.toShort())
        buffer.putShort(TpersbFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt(size)
        buffer.putInt(payloadSize)
        val tag = ByteArray(TpersbFormat.SUBTYPE_TAG_SIZE)
        val tagBytes = subtypeTag.toByteArray(StandardCharsets.US_ASCII)
        tagBytes.copyInto(tag, 0, 0, minOf(tagBytes.size, tag.size))
        buffer.put(tag)
        buffer.put(ByteArray(TpersbFormat.CHECKSUM_SIZE))
        for (index in 0 until size) {
            buffer.put(encodedContexts[index].size.toByte())
            buffer.put(encodedSuccessors[index].size.toByte())
            buffer.putShort(usageCounts[index].toShort())
            buffer.putShort(frequencyCounts[index].toShort())
            buffer.putInt(lastUseSerials[index].toInt())
            buffer.put(encodedContexts[index])
            buffer.put(encodedSuccessors[index])
        }

        val image = buffer.array()
        image.fill(
            0, TpersbFormat.CHECKSUM_OFFSET, TpersbFormat.CHECKSUM_OFFSET + TpersbFormat.CHECKSUM_SIZE,
        )
        val checksum = MessageDigest.getInstance("SHA-256").digest(image)
        checksum.copyInto(image, TpersbFormat.CHECKSUM_OFFSET)
        return image
    }

    /** A fresh immutable snapshot for the engine's worker thread. */
    fun toSnapshot(subtypeTag: String): PersonalBigramDictionary {
        if (isEmpty) return PersonalBigramDictionary.EMPTY
        return PersonalBigramDictionary.of(
            ValidatedPersonalBigrams(
                contexts = contexts.toList(),
                successorRawForms = successorRawForms.toList(),
                successorNormalizedForms = successorNormalizedForms.toList(),
                usageCounts = usageCounts.copyOf(),
                frequencyCounts = frequencyCounts.copyOf(),
                lastUseSerials = lastUseSerials.copyOf(),
                subtypeTag = subtypeTag,
            ),
        )
    }

    /** Estimated on-disk size (header + records), for the pre-write free-space check. */
    fun estimatedFileSize(): Int =
        TpersbFormat.HEADER_SIZE + (0 until size).sumOf {
            TpersbFormat.RECORD_HEADER_SIZE +
                contexts[it].toByteArray(StandardCharsets.UTF_8).size +
                successorRawForms[it].toByteArray(StandardCharsets.UTF_8).size
        }

    private fun indexOfPair(normalizedContext: String, normalizedSuccessor: String): Int {
        var low = 0
        var high = contexts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val order = comparePair(
                contexts[mid], successorNormalizedForms[mid], normalizedContext, normalizedSuccessor,
            )
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun insertionPoint(normalizedContext: String, normalizedSuccessor: String): Int {
        var low = 0
        var high = contexts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (comparePair(
                    contexts[mid], successorNormalizedForms[mid],
                    normalizedContext, normalizedSuccessor,
                ) < 0
            ) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    companion object {
        fun empty(maxPairs: Int = TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS.toInt()): PersonalBigramEntries =
            PersonalBigramEntries(
                emptyArray(), emptyArray(), emptyArray(), IntArray(0), IntArray(0), LongArray(0),
                1L, maxPairs,
            )

        fun fromValidated(
            validated: ValidatedPersonalBigrams,
            maxPairs: Int = TpersbFormat.MAX_PERSONAL_BIGRAM_PAIRS.toInt(),
        ): PersonalBigramEntries {
            val maxSerial = validated.lastUseSerials.maxOrNull() ?: 0L
            return PersonalBigramEntries(
                validated.contexts.toTypedArray(),
                validated.successorRawForms.toTypedArray(),
                validated.successorNormalizedForms.toTypedArray(),
                validated.usageCounts.copyOf(),
                validated.frequencyCounts.copyOf(),
                validated.lastUseSerials.copyOf(),
                maxSerial + 1L,
                maxPairs,
            )
        }

        private fun of(
            ctx: List<String>,
            raw: List<String>,
            norm: List<String>,
            usage: List<Int>,
            frequency: List<Int>,
            serials: List<Long>,
            nextSerial: Long,
            maxPairs: Int,
        ): PersonalBigramEntries = PersonalBigramEntries(
            ctx.toTypedArray(),
            raw.toTypedArray(),
            norm.toTypedArray(),
            usage.toIntArray(),
            frequency.toIntArray(),
            serials.toLongArray(),
            nextSerial,
            maxPairs,
        )

        private fun incrementCapped(count: Int, delta: Int): Int =
            minOf(count + delta, TpersbFormat.MAX_U16.toInt())

        private fun minSerialIndex(serials: List<Long>): Int {
            var victim = 0
            for (index in 1 until serials.size) {
                if (serials[index] < serials[victim]) victim = index
            }
            return victim
        }

        /**
         * Compares two pair keys member by member — context first, successor on a tie — each by
         * its UTF-8 bytes unsigned, the exact order the validator requires on disk. The byte
         * boundary between the words is part of the key: («аб», «вг») and («абв», «г») are
         * different pairs that a concatenation compare would call equal.
         */
        private fun comparePair(
            firstContext: String,
            firstSuccessor: String,
            secondContext: String,
            secondSuccessor: String,
        ): Int {
            val contextOrder = compareUnsignedBytes(firstContext, secondContext)
            if (contextOrder != 0) return contextOrder
            return compareUnsignedBytes(firstSuccessor, secondSuccessor)
        }

        private fun compareUnsignedBytes(first: String, second: String): Int {
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

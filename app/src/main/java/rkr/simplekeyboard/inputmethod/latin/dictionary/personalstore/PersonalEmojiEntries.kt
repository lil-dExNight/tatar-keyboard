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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.ValidatedPersonalEmoji
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The immutable in-memory model of one subtype's learned (word, emoji) entries; the emoji
 * counterpart of [PersonalEntries].
 *
 * Parallel arrays plus a parallel serial array, all ordered by the entry key ascending — the
 * normalized word first, then the emoji cluster, both compared as unsigned UTF-8 bytes (the order
 * [rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemValidator] enforces on disk):
 * - [wordAt] — normalized words (lookup keys; also what the settings list shows);
 * - [emojiAt] — the raw emoji clusters (persisted, shown and inserted as-is);
 * - [usageCountAt] — accepted-suggestion counters (taps), u16, >= 0;
 * - [frequencyCountAt] — clean co-usage observation counters, u16, >= 1;
 * - [lastUseSerialAt] — monotonic last-use serials (u32) driving LRU.
 *
 * Immutability, privacy and LRU rules are those of [PersonalEntries]; the cap [maxEntries]
 * defaults to [TpersemFormat.MAX_PERSONAL_EMOJI_ENTRIES] in production.
 */
internal class PersonalEmojiEntries private constructor(
    private val words: Array<String>,
    private val emojiClusters: Array<String>,
    private val usageCounts: IntArray,
    private val frequencyCounts: IntArray,
    private val lastUseSerials: LongArray,
    val nextSerial: Long,
    private val maxEntries: Int,
) {
    val size: Int
        get() = words.size

    val isEmpty: Boolean
        get() = words.isEmpty()

    fun wordAt(index: Int): String = words[index]
    fun emojiAt(index: Int): String = emojiClusters[index]
    fun usageCountAt(index: Int): Int = usageCounts[index]
    fun frequencyCountAt(index: Int): Int = frequencyCounts[index]
    fun lastUseSerialAt(index: Int): Long = lastUseSerials[index]

    fun containsEntry(normalizedWord: String, emoji: String): Boolean =
        indexOfEntry(normalizedWord, emoji) >= 0

    /**
     * Adds the entry or, if present, adds [frequencyDelta] to its observation counter and touches
     * its LRU serial. See [PersonalBigramEntries.upsert].
     */
    fun upsert(
        normalizedWord: String,
        emoji: String,
        frequencyDelta: Int,
    ): PersonalEmojiEntries {
        val wrd = words.toMutableList()
        val emo = emojiClusters.toMutableList()
        val usage = usageCounts.toMutableList()
        val frequency = frequencyCounts.toMutableList()
        val serials = lastUseSerials.toMutableList()

        val existing = indexOfEntry(normalizedWord, emoji)
        if (existing >= 0) {
            frequency[existing] = incrementCapped(frequency[existing], frequencyDelta)
            serials[existing] = nextSerial
        } else {
            val position = insertionPoint(normalizedWord, emoji)
            wrd.add(position, normalizedWord)
            emo.add(position, emoji)
            usage.add(position, 0)
            frequency.add(position, minOf(frequencyDelta, TpersemFormat.MAX_U16.toInt()))
            serials.add(position, nextSerial)
        }

        if (wrd.size > maxEntries) {
            val victim = minSerialIndex(serials)
            wrd.removeAt(victim)
            emo.removeAt(victim)
            usage.removeAt(victim)
            frequency.removeAt(victim)
            serials.removeAt(victim)
        }

        return of(wrd, emo, usage, frequency, serials, nextSerial + 1, maxEntries)
    }

    /**
     * Records one more clean co-usage observation of an EXISTING entry: bumps the frequency counter
     * and touches the LRU serial. Returns a new instance, or null when the entry is absent (no
     * phantom entry is ever created). The size does not change, so nothing is evicted.
     */
    fun noteObservation(normalizedWord: String, emoji: String): PersonalEmojiEntries? {
        val index = indexOfEntry(normalizedWord, emoji)
        if (index < 0) return null
        val frequency = frequencyCounts.copyOf()
        val serials = lastUseSerials.copyOf()
        frequency[index] = incrementCapped(frequency[index], 1)
        serials[index] = nextSerial
        return PersonalEmojiEntries(
            words, emojiClusters, usageCounts, frequency, serials, nextSerial + 1, maxEntries,
        )
    }

    /**
     * Records an accepted learned emoji as a use: bumps the usage counter and the LRU serial.
     * Returns a new instance, or null when the entry is absent (the tapped emoji was not learned).
     */
    fun noteUse(normalizedWord: String, emoji: String): PersonalEmojiEntries? {
        val index = indexOfEntry(normalizedWord, emoji)
        if (index < 0) return null
        val usage = usageCounts.copyOf()
        val serials = lastUseSerials.copyOf()
        usage[index] = incrementCapped(usage[index], 1)
        serials[index] = nextSerial
        return PersonalEmojiEntries(
            words, emojiClusters, usage, frequencyCounts, serials, nextSerial + 1, maxEntries,
        )
    }

    /** Removes the entry if present, returning a new instance; returns `this` unchanged if absent. */
    fun remove(normalizedWord: String, emoji: String): PersonalEmojiEntries {
        val index = indexOfEntry(normalizedWord, emoji)
        if (index < 0) return this
        val wrd = words.toMutableList().apply { removeAt(index) }
        val emo = emojiClusters.toMutableList().apply { removeAt(index) }
        val usage = usageCounts.toMutableList().apply { removeAt(index) }
        val frequency = frequencyCounts.toMutableList().apply { removeAt(index) }
        val serials = lastUseSerials.toMutableList().apply { removeAt(index) }
        return of(wrd, emo, usage, frequency, serials, nextSerial, maxEntries)
    }

    /** The in-memory bytes to be written whole to disk, in the frozen `.tpersem` layout. */
    fun serialize(subtypeTag: String): ByteArray {
        val encodedWords = words.map { it.toByteArray(StandardCharsets.UTF_8) }
        val encodedEmoji = emojiClusters.map { it.toByteArray(StandardCharsets.UTF_8) }
        val payloadSize = (0 until size).sumOf {
            TpersemFormat.RECORD_HEADER_SIZE + encodedWords[it].size + encodedEmoji[it].size
        }
        val fileSize = TpersemFormat.HEADER_SIZE + payloadSize

        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(TpersemFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(TpersemFormat.SCHEMA_ID.toShort())
        buffer.putShort(TpersemFormat.FORMAT_VERSION.toShort())
        buffer.putShort(TpersemFormat.HEADER_SIZE.toShort())
        buffer.putShort(TpersemFormat.CHECKSUM_ALGORITHM_SHA256.toShort())
        buffer.putInt(size)
        buffer.putInt(payloadSize)
        val tag = ByteArray(TpersemFormat.SUBTYPE_TAG_SIZE)
        val tagBytes = subtypeTag.toByteArray(StandardCharsets.US_ASCII)
        tagBytes.copyInto(tag, 0, 0, minOf(tagBytes.size, tag.size))
        buffer.put(tag)
        buffer.put(ByteArray(TpersemFormat.CHECKSUM_SIZE))
        for (index in 0 until size) {
            buffer.put(encodedWords[index].size.toByte())
            buffer.put(encodedEmoji[index].size.toByte())
            buffer.putShort(usageCounts[index].toShort())
            buffer.putShort(frequencyCounts[index].toShort())
            buffer.putInt(lastUseSerials[index].toInt())
            buffer.put(encodedWords[index])
            buffer.put(encodedEmoji[index])
        }

        val image = buffer.array()
        image.fill(
            0, TpersemFormat.CHECKSUM_OFFSET,
            TpersemFormat.CHECKSUM_OFFSET + TpersemFormat.CHECKSUM_SIZE,
        )
        val checksum = MessageDigest.getInstance("SHA-256").digest(image)
        checksum.copyInto(image, TpersemFormat.CHECKSUM_OFFSET)
        return image
    }

    /** A fresh immutable snapshot for the engine's worker thread. */
    fun toSnapshot(subtypeTag: String): PersonalEmojiDictionary {
        if (isEmpty) return PersonalEmojiDictionary.EMPTY
        return PersonalEmojiDictionary.of(
            ValidatedPersonalEmoji(
                words = words.toList(),
                emojiClusters = emojiClusters.toList(),
                usageCounts = usageCounts.copyOf(),
                frequencyCounts = frequencyCounts.copyOf(),
                lastUseSerials = lastUseSerials.copyOf(),
                subtypeTag = subtypeTag,
            ),
        )
    }

    private fun indexOfEntry(normalizedWord: String, emoji: String): Int {
        var low = 0
        var high = words.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val order = compareEntry(words[mid], emojiClusters[mid], normalizedWord, emoji)
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun insertionPoint(normalizedWord: String, emoji: String): Int {
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareEntry(words[mid], emojiClusters[mid], normalizedWord, emoji) < 0) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    companion object {
        fun empty(maxEntries: Int = TpersemFormat.MAX_PERSONAL_EMOJI_ENTRIES.toInt()): PersonalEmojiEntries =
            PersonalEmojiEntries(
                emptyArray(), emptyArray(), IntArray(0), IntArray(0), LongArray(0), 1L, maxEntries,
            )

        fun fromValidated(
            validated: ValidatedPersonalEmoji,
            maxEntries: Int = TpersemFormat.MAX_PERSONAL_EMOJI_ENTRIES.toInt(),
        ): PersonalEmojiEntries {
            val maxSerial = validated.lastUseSerials.maxOrNull() ?: 0L
            return PersonalEmojiEntries(
                validated.words.toTypedArray(),
                validated.emojiClusters.toTypedArray(),
                validated.usageCounts.copyOf(),
                validated.frequencyCounts.copyOf(),
                validated.lastUseSerials.copyOf(),
                maxSerial + 1L,
                maxEntries,
            )
        }

        private fun of(
            wrd: List<String>,
            emo: List<String>,
            usage: List<Int>,
            frequency: List<Int>,
            serials: List<Long>,
            nextSerial: Long,
            maxEntries: Int,
        ): PersonalEmojiEntries = PersonalEmojiEntries(
            wrd.toTypedArray(),
            emo.toTypedArray(),
            usage.toIntArray(),
            frequency.toIntArray(),
            serials.toLongArray(),
            nextSerial,
            maxEntries,
        )

        private fun incrementCapped(count: Int, delta: Int): Int =
            minOf(count + delta, TpersemFormat.MAX_U16.toInt())

        private fun minSerialIndex(serials: List<Long>): Int {
            var victim = 0
            for (index in 1 until serials.size) {
                if (serials[index] < serials[victim]) victim = index
            }
            return victim
        }

        /**
         * Compares two entry keys member by member (word first, emoji on a tie), each by its
         * UTF-8 bytes unsigned, the order the validator requires on disk. The emoji is compared as
         * bytes, not as a String: variation selectors and supplementary code points order
         * differently in UTF-16 units.
         */
        private fun compareEntry(
            firstWord: String,
            firstEmoji: String,
            secondWord: String,
            secondEmoji: String,
        ): Int {
            val wordOrder = compareUnsignedBytes(firstWord, secondWord)
            if (wordOrder != 0) return wordOrder
            return compareUnsignedBytes(firstEmoji, secondEmoji)
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

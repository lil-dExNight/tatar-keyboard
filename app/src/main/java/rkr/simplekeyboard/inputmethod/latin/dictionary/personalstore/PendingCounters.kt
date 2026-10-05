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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The progress a word has made towards being learned, WITHOUT storing the word.
 *
 * A key is the first 8 bytes of `SHA-256(salt ‖ normalized word)`; the value is a count and a
 * monotonic serial. Counting uses the normalized form, so «Гүзәл» and «гүзәл» share one counter.
 *
 * The truncated salted hash protects a file extracted by accident (a stray backup, a dump, a file
 * manager). It does not protect against someone with both the file and the salt, who can hash a
 * candidate word list and compare.
 *
 * Entries expire: at every flush, anything whose serial lags the current one by more than
 * [LIFETIME_SERIALS] is dropped, so a word typed once does not stay until eviction.
 *
 * Pure logic, no I/O and no Android: the store owns the file, this owns the arithmetic.
 */
internal class PendingCounters private constructor(
    private val keys: LongArray,
    private val counts: IntArray,
    private val serials: LongArray,
    val nextSerial: Long,
) {
    val size: Int
        get() = keys.size

    fun countOf(key: Long): Int {
        val index = indexOf(key)
        return if (index < 0) 0 else counts[index]
    }

    /** Records one clean completion of [key], evicting the oldest entry if the cap is reached. */
    fun note(key: Long): PendingCounters {
        val index = indexOf(key)
        if (index >= 0) {
            val newCounts = counts.copyOf()
            val newSerials = serials.copyOf()
            newCounts[index] = minOf(newCounts[index] + 1, MAX_COUNT)
            newSerials[index] = nextSerial
            return PendingCounters(keys, newCounts, newSerials, nextSerial + 1)
        }
        val insertion = insertionPoint(key)
        val newKeys = insertAt(keys, insertion, key)
        val newCounts = insertAt(counts, insertion, 1)
        val newSerials = insertAt(serials, insertion, nextSerial)
        val grown = PendingCounters(newKeys, newCounts, newSerials, nextSerial + 1)
        return if (grown.size > MAX_PENDING) grown.withoutOldest() else grown
    }

    /** Drops [key] — the word graduated to the dictionary, or the user erased it. */
    fun without(key: Long): PendingCounters {
        val index = indexOf(key)
        if (index < 0) return this
        return PendingCounters(
            removeAt(keys, index), removeAt(counts, index), removeAt(serials, index), nextSerial,
        )
    }

    /** What is actually written: everything still within [LIFETIME_SERIALS] of the current serial. */
    fun prunedForFlush(): PendingCounters {
        val cutoff = nextSerial - LIFETIME_SERIALS
        if (keys.isEmpty() || serials.all { it > cutoff }) return this
        val kept = keys.indices.filter { serials[it] > cutoff }
        return PendingCounters(
            LongArray(kept.size) { keys[kept[it]] },
            IntArray(kept.size) { counts[kept[it]] },
            LongArray(kept.size) { serials[kept[it]] },
            nextSerial,
        )
    }

    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + size * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putShort(FORMAT_VERSION.toShort())
        buffer.putShort(size.toShort())
        buffer.putLong(nextSerial)
        for (index in keys.indices) {
            buffer.putLong(keys[index])
            buffer.putShort(counts[index].toShort())
            buffer.putInt(serials[index].toInt())
        }
        return buffer.array()
    }

    private fun withoutOldest(): PendingCounters {
        var victim = 0
        for (index in 1 until serials.size) {
            if (serials[index] < serials[victim]) victim = index
        }
        return PendingCounters(
            removeAt(keys, victim), removeAt(counts, victim), removeAt(serials, victim), nextSerial,
        )
    }

    private fun indexOf(key: Long): Int {
        var low = 0
        var high = keys.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                keys[mid] < key -> low = mid + 1
                keys[mid] > key -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun insertionPoint(key: Long): Int {
        var low = 0
        var high = keys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keys[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /** Clean completions a word needs before it is written into the dictionary itself. */
        const val LEARN_THRESHOLD = 3

        /** Entries kept at once, per subtype. */
        const val MAX_PENDING = 256

        /** How far a pending entry may lag the current serial before a flush drops it. */
        const val LIFETIME_SERIALS = 500L

        private const val MAGIC = "TATPEND\u0000"
        private const val FORMAT_VERSION = 1
        private const val HEADER_SIZE = 20
        private const val RECORD_SIZE = 14
        private const val MAX_COUNT = 0xFFFF

        /**
         * The largest byte count [parse] can accept: one header plus [MAX_PENDING] records. The
         * store checks `File.length()` against this before reading, so an oversized file is
         * rejected without allocating for it (past 2 GiB that would throw `OutOfMemoryError`).
         */
        const val MAX_SERIALIZED_BYTES: Int = HEADER_SIZE + MAX_PENDING * RECORD_SIZE

        /**
         * The largest nextSerial [parse] accepts. 2^40 is far past any real count — a note every
         * millisecond would need decades to reach it — while keeping `nextSerial + 1` in [note]
         * millions of times short of Long overflow, so a corrupt file cannot stall learning by
         * wrapping the serial negative or by making every flush prune everything.
         */
        const val MAX_NEXT_SERIAL: Long = 1L shl 40

        val EMPTY = PendingCounters(LongArray(0), IntArray(0), LongArray(0), 1L)

        /**
         * The key of [normalizedWord] under [salt]. Deliberately takes the NORMALIZED form: the
         * caller must not be able to key two casings of one word separately.
         */
        fun keyOf(salt: ByteArray, normalizedWord: String): Long {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            digest.update(normalizedWord.toByteArray(StandardCharsets.UTF_8))
            return truncated(digest.digest())
        }

        /**
         * The key of the pair ([normalizedContext], [normalizedSuccessor]) under [salt]. Both
         * halves are normalized, and a zero byte separates them in the digest, so («аб», «вг») and
         * («абв», «г») get different keys.
         */
        fun keyOfPair(salt: ByteArray, normalizedContext: String, normalizedSuccessor: String): Long {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            digest.update(normalizedContext.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(normalizedSuccessor.toByteArray(StandardCharsets.UTF_8))
            return truncated(digest.digest())
        }

        private fun truncated(hash: ByteArray): Long {
            var key = 0L
            for (index in 0 until 8) {
                key = (key shl 8) or (hash[index].toLong() and 0xff)
            }
            return key
        }

        /** Fail-closed: anything unreadable yields empty counters rather than an exception. */
        fun parse(bytes: ByteArray): PendingCounters {
            if (bytes.size < HEADER_SIZE) return EMPTY
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.length)
            buffer.get(magic)
            if (String(magic, StandardCharsets.US_ASCII) != MAGIC) return EMPTY
            if (buffer.short.toInt() != FORMAT_VERSION) return EMPTY
            val count = buffer.short.toInt() and 0xFFFF
            val nextSerial = buffer.long
            if (count > MAX_PENDING) return EMPTY
            if (bytes.size != HEADER_SIZE + count * RECORD_SIZE) return EMPTY
            val keys = LongArray(count)
            val counts = IntArray(count)
            val serials = LongArray(count)
            for (index in 0 until count) {
                keys[index] = buffer.long
                counts[index] = buffer.short.toInt() and 0xFFFF
                serials[index] = buffer.int.toLong() and 0xFFFFFFFFL
                if (counts[index] < 1) return EMPTY
                if (index > 0 && keys[index] <= keys[index - 1]) return EMPTY
            }
            if (nextSerial < 1 || nextSerial > MAX_NEXT_SERIAL) return EMPTY
            return PendingCounters(keys, counts, serials, nextSerial)
        }

        private fun insertAt(source: LongArray, index: Int, value: Long): LongArray {
            val result = LongArray(source.size + 1)
            System.arraycopy(source, 0, result, 0, index)
            result[index] = value
            System.arraycopy(source, index, result, index + 1, source.size - index)
            return result
        }

        private fun insertAt(source: IntArray, index: Int, value: Int): IntArray {
            val result = IntArray(source.size + 1)
            System.arraycopy(source, 0, result, 0, index)
            result[index] = value
            System.arraycopy(source, index, result, index + 1, source.size - index)
            return result
        }

        private fun removeAt(source: LongArray, index: Int): LongArray {
            val result = LongArray(source.size - 1)
            System.arraycopy(source, 0, result, 0, index)
            System.arraycopy(source, index + 1, result, index, source.size - index - 1)
            return result
        }

        private fun removeAt(source: IntArray, index: Int): IntArray {
            val result = IntArray(source.size - 1)
            System.arraycopy(source, 0, result, 0, index)
            System.arraycopy(source, index + 1, result, index, source.size - index - 1)
            return result
        }
    }
}

package rkr.simplekeyboard.inputmethod.latin.morphology

import java.io.InputStream
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StemLexiconArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatStemValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.ValidatedStemLexicon

/**
 * Matches normalized Tatar words against bare stems and enabled suffix chains.
 * Callers own normalization; malformed, out-of-alphabet and oversized input is a non-match.
 * One monitor protects the fixed scratch space, so concurrent callers allocate no lookup objects.
 */
class TatarMorphologicalAcceptor private constructor(
    private val lexicon: ValidatedStemLexicon,
    spec: StemLexiconArtifactSpec,
    private val exceptions: Array<LexicalException>,
    @Suppress("UNUSED_PARAMETER") marker: Boolean,
) {
    constructor(
        lexicon: ValidatedStemLexicon,
        spec: StemLexiconArtifactSpec,
    ) : this(lexicon, spec, TatarWordformExceptions.STEMS, true)

    internal constructor(
        lexicon: ValidatedStemLexicon,
        spec: StemLexiconArtifactSpec,
        exceptions: Array<LexicalException>,
    ) : this(lexicon, spec, exceptions, true)

    val dictionaryRawSha256: String = spec.expectedDictionaryRawSha256

    private val queryChars = CharArray(MAX_QUERY_CODE_POINTS)
    private val queryBytes = ByteArray(MAX_QUERY_CODE_POINTS * 2)
    private val queryByteOffsets = IntArray(MAX_QUERY_CODE_POINTS + 1)
    private val alternateChars = CharArray(MAX_QUERY_CODE_POINTS)
    private val alternateBytes = ByteArray(MAX_QUERY_CODE_POINTS * 2)
    private val stemBytes = ByteArray(MAX_STEM_BYTES)
    private val matcher = TatarWordformMatcher()
    private val exceptionFlags = IntArray(exceptions.size)
    private var queryLength = 0

    init {
        for (index in exceptions.indices) {
            val exception = exceptions[index]
            exceptionFlags[index] = lookup(exception.utf8, exception.utf8.size)
        }
    }

    /** Raw image, matcher scratch, and the retained lexical arrays and string payloads. */
    val retainedByteEstimate: Long = lexicon.raw.size.toLong() +
        queryChars.size * Char.SIZE_BYTES.toLong() + queryBytes.size +
        queryByteOffsets.size * Int.SIZE_BYTES + alternateChars.size * Char.SIZE_BYTES +
        alternateBytes.size + stemBytes.size +
        exceptionFlags.size * Int.SIZE_BYTES + matcher.retainedByteEstimate +
        estimateExceptionPayload(exceptions)

    @Synchronized
    fun accepts(normalizedWord: String): Boolean {
        val length = copyQuery(normalizedWord)
        if (length < 0) return false
        val queryByteLength = queryByteOffsets[length]
        if (lookup(queryBytes, queryByteLength) != 0) return true

        for (prefixLength in MIN_STEM_CODE_POINTS until length) {
            if (matchCandidate(queryChars, prefixLength, queryBytes, queryByteOffsets[prefixLength])) {
                return true
            }
        }
        if (matchAlteredStemPrefixes(length)) return true

        // Suppletive and overridden forms need not begin with their dictionary stem.
        for (index in exceptions.indices) {
            val flags = exceptionFlags[index]
            if (flags == 0 || flags and (FLAG_NOUN_CHAIN or FLAG_VERB_CHAIN) == 0) continue
            val exception = exceptions[index]
            if (exception.chars.size < length && startsWith(exception.chars)) continue
            if (matcher.matches(
                    exception.chars, exception.chars.size, flags, exception, queryChars, length,
                    flags and FLAG_NOUN_CHAIN != 0, flags and FLAG_VERB_CHAIN != 0,
                )
            ) {
                return true
            }
        }
        return false
    }

    /** Tries stem spellings changed by voicing, contraction, soft signs and one-syllable й. */
    private fun matchAlteredStemPrefixes(length: Int): Boolean {
        for (prefixLength in 1..length) {
            if (prefixLength >= MIN_STEM_CODE_POINTS) {
                val last = queryChars[prefixLength - 1]
                val original = when (last) {
                    'б' -> 'п'
                    'г' -> 'к'
                    else -> '\u0000'
                }
                if (original != '\u0000' && queryChars[prefixLength - 2] in VOWELS) {
                    copyAlternate(prefixLength, queryByteOffsets[prefixLength])
                    alternateChars[prefixLength - 1] = original
                    val byteOffset = queryByteOffsets[prefixLength] - 2
                    encode(original, byteOffset)
                    if (matchAlternate(prefixLength, queryByteOffsets[prefixLength])) return true
                }
            }
            if (prefixLength < length) {
                for (insertionIndex in 0 until INSERTED_FINALS.length) {
                    copyAlternate(prefixLength, queryByteOffsets[prefixLength])
                    val inserted = INSERTED_FINALS[insertionIndex]
                    alternateChars[prefixLength] = inserted
                    encode(inserted, queryByteOffsets[prefixLength])
                    if (matchAlternate(prefixLength + 1, queryByteOffsets[prefixLength] + 2)) return true
                }
            }
            if (queryChars[prefixLength - 1] == 'и') {
                for (vowelIndex in VOWELS.indices) {
                    val vowel = VOWELS[vowelIndex]
                    if (vowel == 'и') continue
                    copyAlternate(prefixLength - 1, queryByteOffsets[prefixLength - 1])
                    alternateChars[prefixLength - 1] = vowel
                    encode(vowel, queryByteOffsets[prefixLength - 1])
                    if (matchAlternate(prefixLength, queryByteOffsets[prefixLength - 1] + 2)) return true
                }
            }
            if (prefixLength >= 3 && queryChars[prefixLength - 2] == 'ы' &&
                queryChars[prefixLength - 1] == 'й'
            ) {
                for (vowelIndex in VOWELS.indices) {
                    val vowel = VOWELS[vowelIndex]
                    copyAlternate(prefixLength - 2, queryByteOffsets[prefixLength - 2])
                    alternateChars[prefixLength - 2] = vowel
                    encode(vowel, queryByteOffsets[prefixLength - 2])
                    if (matchAlternate(prefixLength - 1, queryByteOffsets[prefixLength - 2] + 2)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun copyAlternate(charLength: Int, byteLength: Int) {
        queryChars.copyInto(alternateChars, 0, 0, charLength)
        queryBytes.copyInto(alternateBytes, 0, 0, byteLength)
    }

    private fun encode(char: Char, offset: Int) {
        alternateBytes[offset] = (0xc0 or (char.code ushr 6)).toByte()
        alternateBytes[offset + 1] = (0x80 or (char.code and 0x3f)).toByte()
    }

    private fun matchAlternate(charLength: Int, byteLength: Int): Boolean =
        matchCandidate(alternateChars, charLength, alternateBytes, byteLength)

    private fun matchCandidate(
        stem: CharArray,
        stemLength: Int,
        bytes: ByteArray,
        byteLength: Int,
    ): Boolean {
        val flags = lookup(bytes, byteLength)
        if (flags == 0) return false
        val exception = findException(stem, stemLength)
        return matcher.matches(
            stem, stemLength, flags, exception, queryChars, queryLength,
            flags and FLAG_NOUN_CHAIN != 0, flags and FLAG_VERB_CHAIN != 0,
        )
    }

    private fun startsWith(prefix: CharArray): Boolean {
        for (index in prefix.indices) if (queryChars[index] != prefix[index]) return false
        return true
    }

    private fun findException(stem: CharArray, length: Int): LexicalException? {
        for (index in exceptions.indices) {
            val exception = exceptions[index]
            if (exception.chars.size != length) continue
            var equal = true
            for (charIndex in 0 until length) {
                if (exception.chars[charIndex] != stem[charIndex]) {
                    equal = false
                    break
                }
            }
            if (equal) return exception
        }
        return null
    }

    /** Returns the byte length, or -1 when the input cannot occur in a validated lexicon. */
    private fun copyQuery(word: String): Int {
        queryLength = word.length
        val length = queryLength
        if (length < MIN_STEM_CODE_POINTS || length > MAX_QUERY_CODE_POINTS) return -1
        var byteOffset = 0
        var previousWasLetter = false
        queryByteOffsets[0] = 0
        for (index in 0 until length) {
            val char = word[index]
            if (char == '-') {
                if (!previousWasLetter || index + 1 >= length) return -1
                previousWasLetter = false
                queryBytes[byteOffset++] = char.code.toByte()
            } else {
                if (char.code >= ALPHABET_TABLE.size || !ALPHABET_TABLE[char.code]) return -1
                previousWasLetter = true
                queryBytes[byteOffset++] = (0xc0 or (char.code ushr 6)).toByte()
                queryBytes[byteOffset++] = (0x80 or (char.code and 0x3f)).toByte()
            }
            queryChars[index] = char
            queryByteOffsets[index + 1] = byteOffset
        }
        return length
    }

    /** Front-coded block binary search; zero means that no exact stem matched. */
    private fun lookup(target: ByteArray, targetLength: Int): Int {
        var low = 0
        var high = lexicon.blockCount - 1
        var candidate = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val offset = readInt(lexicon.blockIndexOffset + middle * Int.SIZE_BYTES)
            val firstLength = unsignedByte(offset)
            val comparison = compareRaw(offset + 1, firstLength, target, targetLength)
            when {
                comparison == 0 -> return unsignedByte(offset + 1 + firstLength)
                comparison < 0 -> {
                    candidate = middle
                    low = middle + 1
                }
                else -> high = middle - 1
            }
        }
        if (candidate < 0) return 0

        val blockStart = readInt(lexicon.blockIndexOffset + candidate * Int.SIZE_BYTES)
        val firstLength = unsignedByte(blockStart)
        val firstStart = blockStart + 1
        lexicon.raw.copyInto(stemBytes, 0, firstStart, firstStart + firstLength)
        var comparison = compareStem(firstLength, target, targetLength)
        if (comparison == 0) return unsignedByte(firstStart + firstLength)
        var cursor = firstStart + firstLength + 1
        val entries = minOf(BLOCK_SIZE, lexicon.stemCount - candidate * BLOCK_SIZE)
        for (entry in 1 until entries) {
            var prefixLength = 0
            var shift = 0
            while (true) {
                val byte = unsignedByte(cursor++)
                prefixLength = prefixLength or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            val suffixLength = unsignedByte(cursor++)
            lexicon.raw.copyInto(stemBytes, 0, firstStart, firstStart + prefixLength)
            lexicon.raw.copyInto(stemBytes, prefixLength, cursor, cursor + suffixLength)
            cursor += suffixLength
            val stemLength = prefixLength + suffixLength
            comparison = compareStem(stemLength, target, targetLength)
            if (comparison == 0) return unsignedByte(cursor)
            if (comparison > 0) return 0
            cursor++
        }
        return 0
    }

    private fun compareRaw(offset: Int, length: Int, target: ByteArray, targetLength: Int): Int {
        val common = minOf(length, targetLength)
        for (index in 0 until common) {
            val difference = (lexicon.raw[offset + index].toInt() and 0xff) -
                (target[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return length - targetLength
    }

    private fun compareStem(length: Int, target: ByteArray, targetLength: Int): Int {
        val common = minOf(length, targetLength)
        for (index in 0 until common) {
            val difference = (stemBytes[index].toInt() and 0xff) -
                (target[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return length - targetLength
    }

    private fun readInt(offset: Int): Int =
        (lexicon.raw[offset].toInt() and 0xff) or
            ((lexicon.raw[offset + 1].toInt() and 0xff) shl 8) or
            ((lexicon.raw[offset + 2].toInt() and 0xff) shl 16) or
            ((lexicon.raw[offset + 3].toInt() and 0xff) shl 24)

    private fun unsignedByte(offset: Int): Int = lexicon.raw[offset].toInt() and 0xff

    companion object {
        private const val BLOCK_SIZE = 8
        private const val MAX_STEM_BYTES = 128
        private const val MIN_STEM_CODE_POINTS = 2
        private const val FLAG_BACK = 8
        private const val FLAG_NOUN_CHAIN = 16
        private const val FLAG_VERB_CHAIN = 32
        private const val FLAG_FORCE_BACK = 64
        private const val ALPHABET = "аәбвгдеёжҗзийклмнңоөпрстуүфхһцчшщъыьэюя"
        private const val VOWELS = "аыоуюяәеиөүэ"
        private const val INSERTED_FINALS = "йь"
        private val ALPHABET_TABLE = BooleanArray(0x500).also { table ->
            for (index in ALPHABET.indices) table[ALPHABET[index].code] = true
        }

        private fun estimateExceptionPayload(exceptions: Array<LexicalException>): Long {
            var bytes = exceptions.size * Int.SIZE_BYTES.toLong()
            for (exception in exceptions) {
                bytes += exception.stem.length * Char.SIZE_BYTES
                bytes += exception.chars.size * Char.SIZE_BYTES
                bytes += exception.utf8.size
                exception.possessiveBase?.let { bytes += it.length * Char.SIZE_BYTES }
                exception.pronouns?.let { forms ->
                    bytes += forms.size * Int.SIZE_BYTES
                    for (form in forms) bytes += form.length * Char.SIZE_BYTES
                }
                bytes += exception.overrides.size * 2L * Int.SIZE_BYTES
                for ((label, form) in exception.overrides) {
                    bytes += (label.length + form.length) * Char.SIZE_BYTES
                }
            }
            return bytes
        }

        @JvmStatic
        @JvmOverloads
        fun open(
            source: InputStream,
            spec: StemLexiconArtifactSpec = StemLexiconArtifactSpec.TATAR_STEMS_V1,
        ): TatarMorphologicalAcceptor =
            TatarMorphologicalAcceptor(TatStemValidator().inflateAsset(source, spec), spec)
    }
}

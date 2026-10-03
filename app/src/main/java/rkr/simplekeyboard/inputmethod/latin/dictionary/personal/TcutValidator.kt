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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Thrown by [TcutValidator] on any violation. The message is always a constant: it never contains
 * the offending pair, the file path or any other user text.
 */
class TextShortcutValidationException internal constructor(message: String) :
    Exception(message)

/**
 * A validated `.tcut` file, ready to become an immutable snapshot. A plain class without a
 * generated `toString`, because it carries the user's text.
 */
class ValidatedTextShortcuts internal constructor(
    /** Shortcuts in their NFC form, in byte-ascending order. */
    val shortcuts: List<String>,
    /** The parallel verbatim expansions. */
    val expansions: List<String>,
) {
    val entryCount: Int
        get() = shortcuts.size
}

/**
 * Strict validator for the `.tcut` format, modelled on `TpersValidator`.
 *
 * Every check applies to either the shortcut or the expansion of a record. Any violation throws
 * [TextShortcutValidationException]; the reader turns that into an empty shortcut list. Nothing
 * here logs, and no message carries user text.
 */
class TcutValidator {
    /**
     * Validates [file] and returns the parsed, ordered pairs.
     *
     * @throws TextShortcutValidationException on any structural, checksum, UTF-8, content, length,
     *   ordering or duplicate violation.
     */
    fun validate(file: File): ValidatedTextShortcuts {
        val length = file.length()
        if (length > TcutFormat.MAX_FILE_SIZE) fail("file size limit exceeded")
        if (length < TcutFormat.HEADER_SIZE) fail("file is shorter than its header")

        val bytes = file.readBytes()
        if (bytes.size.toLong() != length) fail("file length changed during read")
        return validate(bytes)
    }

    /** The in-memory twin of [validate] for a caller that already holds the bytes (backup). */
    fun validate(bytes: ByteArray): ValidatedTextShortcuts {
        if (bytes.size.toLong() > TcutFormat.MAX_FILE_SIZE) fail("file size limit exceeded")
        if (bytes.size < TcutFormat.HEADER_SIZE) fail("file is shorter than its header")
        val header = ByteBuffer.wrap(bytes, 0, TcutFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(TcutFormat.MAGIC_SIZE)
        header.get(magic)
        if (!magic.contentEquals(TcutFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            fail("wrong magic")
        }
        val schemaId = header.short.toInt() and 0xffff
        val formatVersion = header.short.toInt() and 0xffff
        val headerSize = header.short.toInt() and 0xffff
        val checksumAlgorithm = header.short.toInt() and 0xffff
        val entryCount = header.int.toLong() and TcutFormat.MAX_U32
        val payloadSize = header.int.toLong() and TcutFormat.MAX_U32
        val reserved = ByteArray(TcutFormat.RESERVED_SIZE)
        header.get(reserved)
        val storedChecksum = ByteArray(TcutFormat.CHECKSUM_SIZE)
        header.get(storedChecksum)

        if (schemaId != TcutFormat.SCHEMA_ID) fail("unsupported schema id")
        if (formatVersion != TcutFormat.FORMAT_VERSION) fail("unsupported format version")
        if (headerSize != TcutFormat.HEADER_SIZE) fail("unexpected header size")
        if (checksumAlgorithm != TcutFormat.CHECKSUM_ALGORITHM_SHA256) {
            fail("unsupported checksum algorithm")
        }
        if (entryCount > TcutFormat.MAX_SHORTCUT_ENTRIES) fail("entry count limit exceeded")
        if (reserved.any { it.toInt() != 0 }) fail("reserved header bytes must be zero")

        if (payloadSize != bytes.size.toLong() - TcutFormat.HEADER_SIZE) {
            fail("payload size does not match file")
        }

        if (!MessageDigest.isEqual(storedChecksum, digestWithChecksumZeroed(bytes))) {
            fail("checksum mismatch")
        }

        return parsePayload(bytes, entryCount.toInt())
    }

    private fun parsePayload(bytes: ByteArray, entryCount: Int): ValidatedTextShortcuts {
        val shortcuts = ArrayList<String>(entryCount)
        val expansions = ArrayList<String>(entryCount)

        val cursor = ByteBuffer.wrap(bytes, TcutFormat.HEADER_SIZE, bytes.size - TcutFormat.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        var previousShortcut: String? = null
        for (index in 0 until entryCount) {
            if (cursor.remaining() < TcutFormat.RECORD_HEADER_SIZE) fail("record header truncated")
            val shortcutByteLength = cursor.get().toInt() and 0xff
            val expansionByteLength = cursor.short.toInt() and 0xffff
            if (shortcutByteLength < 1) fail("invalid shortcut byte length")
            if (expansionByteLength < 1) fail("invalid expansion byte length")
            if (cursor.remaining() < shortcutByteLength + expansionByteLength) {
                fail("record bytes truncated")
            }
            val encodedShortcut = ByteArray(shortcutByteLength)
            cursor.get(encodedShortcut)
            val encodedExpansion = ByteArray(expansionByteLength)
            cursor.get(encodedExpansion)

            val shortcut = decodeStrictUtf8(encodedShortcut)
            if (!isWellFormedShortcut(shortcut)) fail("shortcut content is not a typed word")
            val expansion = decodeStrictUtf8(encodedExpansion)
            if (!isWellFormedExpansion(expansion)) fail("expansion content is not a fixed phrase")

            previousShortcut?.let { previous ->
                val order = TextShortcuts.compareShortcuts(previous, shortcut)
                if (order == 0) fail("duplicate shortcut")
                if (order > 0) fail("shortcuts are not sorted")
            }
            previousShortcut = shortcut

            shortcuts.add(shortcut)
            expansions.add(expansion)
        }
        if (cursor.remaining() != 0) fail("trailing payload bytes")

        return ValidatedTextShortcuts(shortcuts = shortcuts, expansions = expansions)
    }

    private fun digestWithChecksumZeroed(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        copy.fill(0, TcutFormat.CHECKSUM_OFFSET, TcutFormat.CHECKSUM_OFFSET + TcutFormat.CHECKSUM_SIZE)
        return MessageDigest.getInstance("SHA-256").digest(copy)
    }

    private fun decodeStrictUtf8(encoded: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    } catch (_: Exception) {
        // Constant message: never echo the bytes that failed to decode.
        fail("record is not valid UTF-8")
    }

    private fun fail(message: String): Nothing = throw TextShortcutValidationException(message)

    companion object {
        /**
         * The content rule of a shortcut, shared by the writer, the validator and the screen's
         * filter: NFC, within the length bounds, and made of exactly the characters
         * `TatarWordUtils.extractTrailingWord` can return — a leading letter, then letters and
         * combining marks — so a stored shortcut can always be typed as one word.
         */
        fun isWellFormedShortcut(shortcut: String): Boolean {
            val codePointCount = shortcut.codePointCount(0, shortcut.length)
            if (codePointCount < TcutFormat.MIN_SHORTCUT_CODE_POINTS ||
                codePointCount > TcutFormat.MAX_SHORTCUT_CODE_POINTS
            ) {
                return false
            }
            if (Normalizer.normalize(shortcut, Normalizer.Form.NFC) != shortcut) return false
            var offset = 0
            var first = true
            while (offset < shortcut.length) {
                val codePoint = shortcut.codePointAt(offset)
                if (Character.isLetter(codePoint)) {
                    // A letter is always allowed.
                } else if (!first && isCombiningMark(codePoint)) {
                    // A combining mark may only continue a letter.
                } else {
                    return false
                }
                first = false
                offset += Character.charCount(codePoint)
            }
            return true
        }

        /**
         * The content rule of an expansion: within the length bounds and free of control
         * characters (line breaks included), so what the cell shows and what the commit inserts
         * are single-line fixed phrases.
         */
        fun isWellFormedExpansion(expansion: String): Boolean {
            val codePointCount = expansion.codePointCount(0, expansion.length)
            if (codePointCount < TcutFormat.MIN_EXPANSION_CODE_POINTS ||
                codePointCount > TcutFormat.MAX_EXPANSION_CODE_POINTS
            ) {
                return false
            }
            var offset = 0
            while (offset < expansion.length) {
                val codePoint = expansion.codePointAt(offset)
                if (Character.getType(codePoint) == Character.CONTROL.toInt()) return false
                offset += Character.charCount(codePoint)
            }
            return true
        }

        private fun isCombiningMark(codePoint: Int): Boolean {
            val type = Character.getType(codePoint)
            return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
        }
    }
}

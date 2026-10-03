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

package rkr.simplekeyboard.inputmethod.latin.settings.backup

/**
 * A minimal, strict JSON reader and writer for the backup manifest. The app has no JSON library
 * (zero third-party runtime dependencies), and the manifest schema is fixed and small, so a
 * hand-rolled reader is both enough and easier to reason about than a general parser.
 *
 * Reader strictness: integer numbers only, duplicate object keys rejected, unescaped control
 * characters rejected, lone surrogates rejected, nesting depth capped, trailing content rejected.
 * Values come out as null, Boolean, Long, String, `List<Any?>` or `Map<String, Any?>` (insertion
 * order kept). All failure messages are constants: a manifest never carries user text, but the
 * rule holds anyway.
 *
 * The writer emits the same subset: no non-integer numbers, every control character escaped.
 */
object BackupJson {

    class JsonException internal constructor(message: String) : Exception(message)

    /** Parses [text] into the value model above. @throws JsonException on any violation. */
    fun parse(text: String): Any? = Reader(text).readDocument()

    /** Serializes a value of the model above. @throws JsonException on an unsupported value. */
    fun write(value: Any?): String {
        val out = StringBuilder()
        writeValue(out, value)
        return out.toString()
    }

    private const val MAX_DEPTH = 8

    private class Reader(private val text: String) {
        private var index = 0

        fun readDocument(): Any? {
            skipWhitespace()
            val value = readValue(0)
            skipWhitespace()
            if (index != text.length) fail("trailing content")
            return value
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (index >= text.length) fail("unexpected end")
            return when (val char = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> fail("unexpected character '$char'")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            index++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                if (index >= text.length || text[index] != '"') fail("object key must be a string")
                val key = readString()
                if (map.containsKey(key)) fail("duplicate object key")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (peek('}')) {
                    index++
                    return map
                }
                expect(',')
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            index++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek(']')) {
                index++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue(depth + 1))
                skipWhitespace()
                if (peek(']')) {
                    index++
                    return list
                }
                expect(',')
            }
        }

        private fun readString(): String {
            index++ // '"'
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("unterminated string")
                when (val char = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> {
                        if (char < ' ') fail("unescaped control character")
                        if (Character.isHighSurrogate(char)) {
                            // A high surrogate must pair with an escaped or literal low one;
                            // pairing only the literal form keeps lone halves out.
                            if (index >= text.length || !Character.isLowSurrogate(text[index])) {
                                fail("lone surrogate")
                            }
                            out.append(char)
                            out.append(text[index++])
                        } else if (Character.isLowSurrogate(char)) {
                            fail("lone surrogate")
                        } else {
                            out.append(char)
                        }
                    }
                }
            }
        }

        private fun readEscape(): Char {
            if (index >= text.length) fail("unterminated escape")
            return when (val escape = text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> ''
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) fail("truncated unicode escape")
                    val hex = text.substring(index, index + 4)
                    index += 4
                    val codePoint = hex.toIntOrNull(16) ?: fail("bad unicode escape")
                    val unit = codePoint.toChar()
                    if (Character.isSurrogate(unit)) fail("lone surrogate escape")
                    unit
                }
                else -> fail("unknown escape '$escape'")
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) fail("bad literal")
            index += literal.length
            return value
        }

        private fun readNumber(): Long {
            val start = index
            if (peek('-')) index++
            if (index >= text.length || text[index] !in '0'..'9') fail("bad number")
            if (text[index] == '0') {
                index++
            } else {
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && (text[index] == '.' || text[index] == 'e' ||
                    text[index] == 'E' || text[index] == '+')) {
                fail("only integer numbers are allowed")
            }
            return text.substring(start, index).toLongOrNull() ?: fail("number out of range")
        }

        private fun skipWhitespace() {
            while (index < text.length &&
                (text[index] == ' ' || text[index] == '\t' || text[index] == '\n' || text[index] == '\r')) {
                index++
            }
        }

        private fun peek(char: Char): Boolean = index < text.length && text[index] == char

        private fun expect(char: Char) {
            if (!peek(char)) fail("expected '$char'")
            index++
        }

        private fun fail(message: String): Nothing = throw JsonException(message)
    }

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Long -> out.append(value)
            is Int -> out.append(value)
            is String -> writeString(out, value)
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { position, item ->
                    if (position > 0) out.append(',')
                    writeValue(out, item)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (key !is String) throw JsonException("object key must be a string")
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    writeValue(out, item)
                }
                out.append('}')
            }
            else -> throw JsonException("unsupported value type")
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char < ' ') {
                    out.append("\\u")
                    out.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }
}

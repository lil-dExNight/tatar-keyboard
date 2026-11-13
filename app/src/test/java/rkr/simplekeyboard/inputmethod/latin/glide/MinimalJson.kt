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

package rkr.simplekeyboard.inputmethod.latin.glide

/**
 * Minimal JSON reader for the real-gesture diagnostic corpora: objects, arrays, strings (with
 * escapes), numbers (as Double), true/false/null. Fails with [IllegalArgumentException] on
 * malformed input; the corpora are machine-generated, so no recovery is attempted.
 */
internal object MinimalJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        if (reader.pos != text.length) reader.error()
        return value
    }

    private class Reader(private val text: String) {
        var pos = 0

        fun error(): Nothing = throw IllegalArgumentException("malformed JSON at $pos")

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun value(): Any? {
            skipWhitespace()
            if (pos >= text.length) error()
            return when (text[pos]) {
                '{' -> {
                    pos++
                    val map = LinkedHashMap<String, Any?>()
                    skipWhitespace()
                    if (pos < text.length && text[pos] == '}') {
                        pos++
                        return map
                    }
                    while (true) {
                        skipWhitespace()
                        val key = value() as? String ?: error()
                        skipWhitespace()
                        if (pos >= text.length || text[pos] != ':') error()
                        pos++
                        map[key] = value()
                        skipWhitespace()
                        if (pos >= text.length) error()
                        if (text[pos] == '}') {
                            pos++
                            return map
                        }
                        if (text[pos] != ',') error()
                        pos++
                    }
                }
                '[' -> {
                    pos++
                    val list = ArrayList<Any?>()
                    skipWhitespace()
                    if (pos < text.length && text[pos] == ']') {
                        pos++
                        return list
                    }
                    while (true) {
                        list.add(value())
                        skipWhitespace()
                        if (pos >= text.length) error()
                        if (text[pos] == ']') {
                            pos++
                            return list
                        }
                        if (text[pos] != ',') error()
                        pos++
                    }
                }
                '"' -> {
                    pos++
                    val out = StringBuilder()
                    while (pos < text.length && text[pos] != '"') {
                        val c = text[pos]
                        if (c == '\\') {
                            pos++
                            if (pos >= text.length) error()
                            out.append(
                                when (val escaped = text[pos]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    'b' -> '\b'
                                    'f' -> ''
                                    'u' -> {
                                        if (pos + 4 >= text.length) error()
                                        val code = text.substring(pos + 1, pos + 5).toInt(16)
                                        pos += 4
                                        code.toChar()
                                    }
                                    else -> escaped
                                },
                            )
                        } else {
                            out.append(c)
                        }
                        pos++
                    }
                    if (pos >= text.length) error()
                    pos++
                    out.toString()
                }
                't' -> {
                    if (!text.startsWith("true", pos)) error()
                    pos += 4
                    true
                }
                'f' -> {
                    if (!text.startsWith("false", pos)) error()
                    pos += 5
                    false
                }
                'n' -> {
                    if (!text.startsWith("null", pos)) error()
                    pos += 4
                    null
                }
                else -> {
                    val start = pos
                    while (pos < text.length && text[pos] in "-+0123456789.eE") pos++
                    if (start == pos) error()
                    text.substring(start, pos).toDouble()
                }
            }
        }
    }
}

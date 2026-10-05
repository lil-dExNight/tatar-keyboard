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

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The settings entry of the backup archive: the preference map in the SharedPreferences XML shape,
 * so the file stays readable and inspectable outside the app.
 *
 * The writer covers the SharedPreferences value types (boolean, int, long, float, string, string
 * set) with keys sorted, one fixed attribute order and UTF-8. A value of any other type, or one
 * holding characters XML 1.0 cannot represent, is skipped: the app's own keys never have one, and
 * an unrepresentable value is not restorable anyway.
 *
 * The parser reads exactly that subset and nothing else: no DOCTYPE, no entities beyond the five
 * predefined ones, no CDATA, no unknown elements or attributes, no duplicate keys, no text outside
 * a string value. The strictness is what makes a hand-crafted file fail closed instead of being
 * half-understood. All failure messages are constants.
 */
object BackupPreferencesXml {

    class PreferencesXmlException internal constructor(message: String) : Exception(message)

    private const val DECLARATION = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"

    /** Serializes the preference map. Keys come out sorted, so equal maps give equal bytes. */
    fun serialize(values: Map<String, Any?>): ByteArray {
        val out = StringBuilder()
        out.append(DECLARATION)
        out.append("<map>\n")
        for ((key, value) in values.toSortedMap()) {
            val name = escapeAttribute(key)
            when (value) {
                null -> Unit // a removed key read back as null: nothing to write
                is Boolean -> out.append("    <boolean name=\"$name\" value=\"$value\" />\n")
                is Int -> out.append("    <int name=\"$name\" value=\"$value\" />\n")
                is Long -> out.append("    <long name=\"$name\" value=\"$value\" />\n")
                is Float -> out.append("    <float name=\"$name\" value=\"$value\" />\n")
                is String -> if (isXmlTextSafe(value)) {
                    out.append("    <string name=\"$name\">${escapeText(value)}</string>\n")
                }
                is Set<*> -> if (value.all { it is String && isXmlTextSafe(it) }) {
                    out.append("    <set name=\"$name\">\n")
                    for (element in value) {
                        out.append("        <string>${escapeText(element as String)}</string>\n")
                    }
                    out.append("    </set>\n")
                }
                else -> Unit // not a SharedPreferences type: not written
            }
        }
        out.append("</map>\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses one settings document into the preference map.
     * @throws PreferencesXmlException on any structural, escaping or value violation.
     */
    fun parse(bytes: ByteArray): Map<String, Any> {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw PreferencesXmlException("settings document is not UTF-8")
        }
        return Parser(text).parseDocument()
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): Map<String, Any> {
            skipIgnorable()
            expectStartTag("map")
            val values = LinkedHashMap<String, Any>()
            while (true) {
                skipIgnorable()
                if (tryEndTag("map")) {
                    skipIgnorable()
                    if (index != text.length) fail("content after the root element")
                    return values
                }
                readEntry(values)
            }
        }

        /** One child of the map: a scalar element with name and value, or a set element. */
        private fun readEntry(values: LinkedHashMap<String, Any>) {
            val tag = readStartTag()
            val name = tag.attributes["name"] ?: fail("entry without a name")
            if (name.isEmpty()) fail("empty entry name")
            if (tag.attributes.keys != setOf("name") &&
                tag.attributes.keys != setOf("name", "value")) {
                fail("unexpected attribute on an entry")
            }
            if (values.containsKey(name)) fail("duplicate entry name")
            when (tag.name) {
                "boolean" -> {
                    values[name] = when (requiredValue(tag)) {
                        "true" -> true
                        "false" -> false
                        else -> fail("bad boolean value")
                    }
                    expectEndTagOrEmpty(tag)
                }
                "int" -> {
                    values[name] = requiredValue(tag).toIntOrNull() ?: fail("bad int value")
                    expectEndTagOrEmpty(tag)
                }
                "long" -> {
                    values[name] = requiredValue(tag).toLongOrNull() ?: fail("bad long value")
                    expectEndTagOrEmpty(tag)
                }
                "float" -> {
                    values[name] = parseFloat(requiredValue(tag))
                    expectEndTagOrEmpty(tag)
                }
                "string" -> {
                    if (tag.attributes.size != 1) fail("unexpected attribute on a string entry")
                    values[name] = if (tag.selfClosing) "" else readTextUntilEndTag("string")
                }
                "set" -> {
                    if (tag.attributes.size != 1) fail("unexpected attribute on a set entry")
                    val elements = LinkedHashSet<String>()
                    if (!tag.selfClosing) {
                        while (true) {
                            skipIgnorable()
                            if (tryEndTag("set")) break
                            val element = readStartTag()
                            if (element.name != "string" || element.attributes.isNotEmpty()) {
                                fail("a set holds only plain string elements")
                            }
                            val item = if (element.selfClosing) "" else readTextUntilEndTag("string")
                            if (!elements.add(item)) fail("duplicate string in a set")
                        }
                    }
                    values[name] = elements
                }
                else -> fail("unknown element in the map")
            }
        }

        private fun requiredValue(tag: Tag): String =
            tag.attributes["value"] ?: fail("entry without a value")

        /**
         * Floats come from Float.toString on write; only that finite domain is accepted back —
         * a NaN or infinite geometry scale must fail the import, not reach the keyboard.
         */
        private fun parseFloat(raw: String): Float {
            val value = raw.toFloatOrNull() ?: fail("bad float value")
            if (value.isNaN() || value.isInfinite()) fail("bad float value")
            return value
        }

        /** After a scalar's value attribute: either `/>` was read already, or a `</tag>` follows. */
        private fun expectEndTagOrEmpty(tag: Tag) {
            if (tag.selfClosing) return
            skipTextlessWhitespace()
            if (!tryEndTag(tag.name)) fail("a scalar entry must be empty")
        }

        /** Reads text up to `</name>`; the only place character content is legal. */
        private fun readTextUntilEndTag(name: String): String {
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("unterminated string element")
                if (text.startsWith("</", index)) {
                    expectEndTag(name)
                    return out.toString()
                }
                if (text[index] == '<') fail("element inside a string value")
                if (text[index] == '&') {
                    out.append(readEntity())
                } else {
                    out.append(text[index++])
                }
            }
        }

        // ------------------------------------------------------------------
        // Tokenizer. Everything not produced by the writer is a failure.
        // ------------------------------------------------------------------

        private class Tag(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean)

        /** Reads `<name ...>` or `<name ... />`. The caller checked that `<` starts an element. */
        private fun readStartTag(): Tag {
            if (index >= text.length || text[index] != '<') fail("expected an element")
            if (index + 1 < text.length && text[index + 1] == '!') {
                fail("markup declarations are not allowed")
            }
            if (index + 1 < text.length && text[index + 1] == '?') fail("misplaced processing instruction")
            index++ // '<'
            val name = readName()
            val attributes = LinkedHashMap<String, String>()
            while (true) {
                skipWhitespace()
                if (index >= text.length) fail("unterminated element")
                when (text[index]) {
                    '>' -> {
                        index++
                        return Tag(name, attributes, false)
                    }
                    '/' -> {
                        index++
                        if (index >= text.length || text[index] != '>') fail("bad empty-element tag")
                        index++
                        return Tag(name, attributes, true)
                    }
                    else -> {
                        val attributeName = readName()
                        if (attributes.containsKey(attributeName)) fail("duplicate attribute")
                        skipWhitespace()
                        if (index >= text.length || text[index] != '=') fail("attribute without a value")
                        index++
                        skipWhitespace()
                        attributes[attributeName] = readAttributeValue()
                    }
                }
            }
        }

        private fun expectStartTag(name: String) {
            val tag = readStartTag()
            if (tag.name != name) fail("expected the root element <$name>")
            if (tag.selfClosing) fail("the root element must not be empty")
            if (tag.attributes.isNotEmpty()) fail("the root element takes no attributes")
        }

        private fun tryEndTag(name: String): Boolean {
            if (!text.startsWith("</", index)) return false
            expectEndTag(name)
            return true
        }

        private fun expectEndTag(name: String) {
            if (!text.startsWith("</", index)) fail("expected </$name>")
            index += 2
            val actual = readName()
            if (actual != name) fail("mismatched end tag")
            skipWhitespace()
            if (index >= text.length || text[index] != '>') fail("bad end tag")
            index++
        }

        private fun readName(): String {
            val start = index
            while (index < text.length) {
                val char = text[index]
                if (char.isLetterOrDigit() || char == '_' || char == '-' || char == '.') {
                    index++
                } else {
                    break
                }
            }
            if (index == start) fail("expected a name")
            return text.substring(start, index)
        }

        private fun readAttributeValue(): String {
            if (index >= text.length) fail("unterminated attribute")
            val quote = text[index]
            if (quote != '"' && quote != '\'') fail("attribute value must be quoted")
            index++
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("unterminated attribute")
                when (text[index]) {
                    quote -> {
                        index++
                        return out.toString()
                    }
                    '<' -> fail("'<'' in an attribute value")
                    '&' -> out.append(readEntity())
                    else -> out.append(text[index++])
                }
            }
        }

        /** Exactly the five predefined entities; nothing else is needed or allowed. */
        private fun readEntity(): Char {
            // text[index] is '&'.
            val semicolon = text.indexOf(';', index + 1)
            if (semicolon < 0) fail("unterminated entity")
            val entity = text.substring(index + 1, semicolon)
            val char = when (entity) {
                "amp" -> '&'
                "lt" -> '<'
                "gt" -> '>'
                "quot" -> '"'
                "apos" -> '\''
                else -> fail("unknown entity")
            }
            index = semicolon + 1
            return char
        }

        /** Whitespace between tokens: the only place XML allows it without meaning. */
        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        /** Whitespace, comments and processing instructions: the ignorable content of the map. */
        private fun skipIgnorable() {
            while (index < text.length) {
                when {
                    text[index].isWhitespace() -> index++
                    text.startsWith("<!--", index) -> {
                        val end = text.indexOf("-->", index + 4)
                        if (end < 0) fail("unterminated comment")
                        if (text.indexOf("--", index + 4) != end) fail("bad comment")
                        index = end + 3
                    }
                    text.startsWith("<?", index) -> {
                        val end = text.indexOf("?>", index + 2)
                        if (end < 0) fail("unterminated processing instruction")
                        index = end + 2
                    }
                    else -> return
                }
            }
        }

        /** Inside a scalar element only whitespace may precede the end tag. */
        private fun skipTextlessWhitespace() = skipIgnorable()

        private fun fail(message: String): Nothing = throw PreferencesXmlException(message)
    }

    private fun escapeAttribute(raw: String): String {
        if (!isXmlTextSafe(raw)) throw PreferencesXmlException("preference name is not XML-safe")
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun escapeText(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** XML 1.0 character range, paired surrogates included; the writer skips what it cannot hold. */
    private fun isXmlTextSafe(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '\t' || char == '\n' || char == '\r' -> index++
                char < ' ' -> return false
                char == '\uFFFE' || char == '\uFFFF' -> return false
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        return false
                    }
                    index += 2
                }
                Character.isLowSurrogate(char) -> return false
                else -> index++
            }
        }
        return true
    }
}

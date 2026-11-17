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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

/**
 * The reviewed proper-name display table: normalized dictionary keys to the display forms approved
 * for them. The parser accepts only the production asset's `key<TAB>display` rows and fails closed
 * to [EMPTY].
 */
class ProperNameIndex private constructor(
    private val displays: Map<String, String>,
) {
    val entryCount: Int get() = displays.size

    val isEmpty: Boolean get() = displays.isEmpty()

    /** The reviewed display form of an NFC lowercase dictionary key, or null. */
    fun displayFor(normalizedWord: String): String? = displays[normalizedWord]

    companion object {
        /** Upper bound on the length of a single asset line; anything longer is junk. */
        private const val MAX_LINE_CHARS = 512

        /** Hard cap on the record count. */
        private const val MAX_RECORDS = 4096

        val EMPTY = ProperNameIndex(emptyMap())

        /**
         * Fail-closed parser. Comment (`#`) and blank lines are skipped; a malformed or invalid row
         * is dropped, a duplicate key keeps its first row, and a fully unreadable input yields
         * [EMPTY]; no exception escapes.
         */
        @JvmStatic
        fun parse(text: String): ProperNameIndex =
            try {
                parseOrThrow(text)
            } catch (_: Exception) {
                EMPTY
            }

        /** Reads [input] as UTF-8 and parses it; an unreadable stream yields [EMPTY]. */
        @JvmStatic
        fun parse(input: InputStream): ProperNameIndex {
            val text = try {
                input.reader(Charsets.UTF_8).readText()
            } catch (_: Exception) {
                return EMPTY
            }
            return parse(text)
        }

        private fun parseOrThrow(text: String): ProperNameIndex {
            val displays = LinkedHashMap<String, String>()
            for (rawLine in text.split('\n')) {
                val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
                if (line.isEmpty() || line.length > MAX_LINE_CHARS) continue
                if (line.startsWith('#')) continue
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab >= line.length - 1) continue
                if (line.indexOf('\t', tab + 1) >= 0) continue
                val key = line.substring(0, tab)
                val display = line.substring(tab + 1)
                if (!isNfc(key) || key.lowercase(Locale.ROOT) != key) continue
                if (!isNfc(display) || display.lowercase(Locale.ROOT) != key) continue
                if (displays.putIfAbsent(key, display) != null) continue
                if (displays.size >= MAX_RECORDS) break
            }
            if (displays.isEmpty()) return EMPTY
            return ProperNameIndex(java.util.Collections.unmodifiableMap(displays))
        }

        private fun isNfc(text: String): Boolean =
            Normalizer.isNormalized(text, Normalizer.Form.NFC)
    }
}

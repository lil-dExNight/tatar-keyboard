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

package rkr.simplekeyboard.inputmethod.latin.emoji

/**
 * The emoji-search query: the text the user types while the search is open.
 *
 * It lives here and nowhere else. The query is NEVER sent to the editor: nothing the user types
 * while searching is committed to the application's text field, no marked region is ever started
 * there, and nothing already in that field is read. What the keyboard normally routes into
 * `InputLogic` is diverted into this object for as long as the search is open, and the editor sees
 * only the emoji finally picked, through LatinIME's explicit `onEmojiTextInput` path.
 *
 * Pure and Android-free, so every rule below is verifiable on the plain JVM: the length bound, the
 * rejection of control characters, code-point-aware backspace (a surrogate pair is one press), and
 * "backspace on an empty query is a request to leave the search".
 */
class EmojiSearchQuery {
    private val builder = StringBuilder()

    /** The last text handed out, so an unchanged query allocates no new string. */
    private var cached: String = ""
    private var cacheValid = true

    val length: Int get() = builder.length

    fun isEmpty(): Boolean = builder.isEmpty()

    /** The query as text; repeated calls without a change return the same instance. */
    fun text(): String {
        if (!cacheValid) {
            cached = builder.toString()
            cacheValid = true
        }
        return cached
    }

    /**
     * Appends one typed code point. Control characters (including Enter and Tab) and anything past
     * [MAX_LENGTH] are refused; returns true when the query changed.
     */
    fun appendCodePoint(codePoint: Int): Boolean {
        if (codePoint <= 0 || Character.isISOControl(codePoint)) return false
        if (!Character.isValidCodePoint(codePoint)) return false
        val width = Character.charCount(codePoint)
        if (builder.length + width > MAX_LENGTH) return false
        builder.appendCodePoint(codePoint)
        cacheValid = false
        return true
    }

    /**
     * Appends a keyboard output-text event code point by code point, with the same control and
     * length rules as [appendCodePoint]. Returns true when any code point was appended.
     */
    fun appendText(text: CharSequence): Boolean {
        var changed = false
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            changed = appendCodePoint(codePoint) || changed
            index += Character.charCount(codePoint)
        }
        return changed
    }

    /**
     * Deletes the last code point, so one press removes one character even when it is a surrogate
     * pair. Returns false when the query was already empty — the caller reads that as "leave the
     * search", which is what a backspace on an empty query means.
     */
    fun backspace(): Boolean {
        if (builder.isEmpty()) return false
        val end = builder.length
        val start = builder.offsetByCodePoints(end, -1)
        builder.delete(start, end)
        cacheValid = false
        return true
    }

    /** Empties the query; returns true when it held anything. */
    fun clear(): Boolean {
        if (builder.isEmpty()) return false
        builder.setLength(0)
        cacheValid = false
        return true
    }

    companion object {
        /** A search query longer than this is not a search; the bound also caps the drawn text. */
        const val MAX_LENGTH = 48
    }
}

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

/**
 * The in-memory holder behind the strip's recent-clip cell: the one freshest text clip the system
 * clipboard held while the keyboard's input view was shown, with the moment it was set.
 *
 * RAM only: nothing here is ever written to disk, logged or learned from. The clip is dropped when
 * the input view hides and when the cell's tap consumes it, and it expires [TTL_MILLIS] after it
 * was set — the cell offers only a clip copied moments ago, never an old clipboard's content.
 *
 * Pure and Android-free so plain JVM tests cover the TTL and the display form; the
 * `ClipboardManager` listener lives in LatinIME.
 */
internal class RecentClipCell(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** The full clip text as offered, or null when there is nothing fresh to offer. */
    private var clipText: String? = null
    private var setAtMillis: Long = 0L

    /**
     * Records the clipboard's new state: [text] is the clip's plain text (null or blank when the
     * clip holds none, which clears the offer), [setAtMillis] when the clip was set (the platform's
     * clip timestamp where available, the event's own moment otherwise).
     */
    fun noteClip(text: String?, setAtMillis: Long) {
        if (text.isNullOrBlank()) {
            clear()
            return
        }
        clipText = text
        this.setAtMillis = setAtMillis
    }

    /** Drops the held clip: the input view hid, or the cell's tap consumed it. */
    fun clear() {
        clipText = null
        setAtMillis = 0L
    }

    /**
     * The cell's display text — the clip's first line, trimmed and capped with an ellipsis — or
     * null when the held clip has expired. A null answer also means a painted cell must not be
     * tappable anymore.
     */
    fun offerText(): String? {
        val text = freshText() ?: return null
        val firstLineBreak = text.indexOfFirst { it == '\n' || it == '\r' }
        val hasMoreLines = firstLineBreak >= 0
        val firstLine = (if (hasMoreLines) text.substring(0, firstLineBreak) else text).trim()
        if (firstLine.isEmpty()) return null
        val overCap = firstLine.codePointCount(0, firstLine.length) > MAX_CELL_CODE_POINTS
        if (!hasMoreLines && !overCap) return firstLine
        val head = if (overCap) {
            firstLine.substring(0, firstLine.offsetByCodePoints(0, MAX_CELL_CODE_POINTS - 1)).trimEnd()
        } else {
            firstLine
        }
        return head + ELLIPSIS
    }

    /**
     * The full clip text for the cell's commit, or null when it has expired between the offer and
     * the tap. The commit inserts the whole clip, not the truncated cell text.
     */
    fun fullTextForCommit(): String? = freshText()

    private fun freshText(): String? {
        val text = clipText ?: return null
        val age = clock() - setAtMillis
        if (age < 0 || age > TTL_MILLIS) return null
        return text
    }

    companion object {
        /**
         * How long a clip counts as fresh: two minutes. Long enough to copy in one app and paste in
         * the next field; short enough that the cell never offers something the user forgot about.
         */
        const val TTL_MILLIS = 2L * 60L * 1000L

        /** Cap on the cell's display text, in code points, before the ellipsis. */
        const val MAX_CELL_CODE_POINTS = 40

        private const val ELLIPSIS = "…"
    }
}

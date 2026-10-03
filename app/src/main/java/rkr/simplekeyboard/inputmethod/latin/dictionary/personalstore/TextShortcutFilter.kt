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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TcutValidator
import java.text.Normalizer

/**
 * Pure input filter for a pair about to enter the text-shortcut store. It applies the per-record
 * checks `TcutValidator` enforces, so an accepted pair round-trips through the writer and the
 * validator. Content checks only: no I/O, no logging, no messages.
 */
internal object TextShortcutFilter {
    /** The NFC form a shortcut is keyed by, surrounding whitespace trimmed first. */
    fun normalizeShortcut(rawShortcut: String): String =
        Normalizer.normalize(rawShortcut.trim(), Normalizer.Form.NFC)

    /**
     * Returns the normalized form of [rawShortcut] if it can ever be typed as one word, or null.
     * See `TcutValidator.isWellFormedShortcut`.
     */
    fun acceptedShortcut(rawShortcut: String): String? {
        val normalized = normalizeShortcut(rawShortcut)
        return if (TcutValidator.isWellFormedShortcut(normalized)) normalized else null
    }

    /**
     * Returns the verbatim [rawExpansion], trimmed of surrounding whitespace, if it satisfies
     * `TcutValidator.isWellFormedExpansion`, or null. The trimmed form is what gets committed, so
     * an accidental trailing space never doubles the separator.
     */
    fun acceptedExpansion(rawExpansion: String): String? {
        val trimmed = rawExpansion.trim()
        return if (TcutValidator.isWellFormedExpansion(trimmed)) trimmed else null
    }
}

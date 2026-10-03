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

/**
 * The read side of the user's text shortcuts, consulted by the suggestion strip: the expansion
 * stored for [typedWord] exactly as typed (NFC-folded, casing kept), or null. Implementations read
 * a published in-memory snapshot, so a call is a map lookup, never I/O.
 */
fun interface TextShortcutSource {
    fun expansionFor(typedWord: String): String?
}

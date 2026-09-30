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

/**
 * Reads a `.tpers` file for one subtype into an immutable [PersonalDictionary], read-only.
 *
 * Fail-closed at every step. A missing file, an unsupported subtype, a validation failure or any
 * unexpected error all yield [PersonalDictionary.EMPTY] rather than an exception escaping to the
 * caller. Nothing is logged and no error path carries the user's word or the file path.
 *
 * Lives in the test sourceset: production code never reads a `.tpers` back (writing and in-memory
 * use go through the personal store), only the personal-dictionary tests do.
 */
class PersonalDictionaryReader(private val validator: TpersValidator = TpersValidator()) {
    /**
     * Reads the personal dictionary for [subtypeId] from [file], or [PersonalDictionary.EMPTY] on
     * any problem. The [subtypeId] both selects the alphabet and is checked against the file's
     * subtypeTag — a mismatch (a file belonging to another subtype) yields an empty dictionary.
     */
    fun read(file: File?, subtypeId: String): PersonalDictionary {
        if (file == null || !file.isFile) return PersonalDictionary.EMPTY
        if (!PersonalSubtypes.isSupported(subtypeId)) return PersonalDictionary.EMPTY
        return try {
            PersonalDictionary.of(validator.validate(file, subtypeId))
        } catch (_: PersonalDictionaryValidationException) {
            PersonalDictionary.EMPTY
        } catch (_: Throwable) {
            // Fail closed on any unexpected error (I/O, decoding); ordinary input is never affected.
            PersonalDictionary.EMPTY
        }
    }
}

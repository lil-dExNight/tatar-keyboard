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

import java.io.File

/**
 * The read half of a personal store's open, kept apart from validation so that the two failures
 * stay apart. A read that throws is an I/O error: the store stays not loaded, writes nothing and
 * retries at its next prime. A file over its size cap, or bytes its validator rejects, is a damaged
 * file, which the store moves to quarantine.
 */
internal object PersonalFileReads {
    /** The bytes of [file], or null when it is longer than [maxBytes]; throws on an I/O error. */
    fun readCapped(file: File, maxBytes: Long): ByteArray? {
        if (file.length() > maxBytes) return null
        return file.readBytes()
    }
}

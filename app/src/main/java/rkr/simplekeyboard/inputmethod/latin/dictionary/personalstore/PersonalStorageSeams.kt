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
import java.io.FileOutputStream

/**
 * The seam that owns only the personal-dictionary directory. Separate from the dictionary asset's
 * device-protected directory seam and from the recent-emoji file seam, which have different storage
 * and owners. Production resolves it under the base (credential-protected) `noBackupFilesDir`.
 */
fun interface PersonalDirectoryProvider {
    fun personalDirectory(): File
}

/**
 * Opens the exclusive temp for writing. A seam so JVM tests can inject faults into the write and
 * flush steps. Production returns a plain [FileOutputStream]; the store owns the fsync, the atomic
 * replace and the directory fsync.
 */
fun interface PersonalOutputOpener {
    fun open(temp: File): FileOutputStream
}

/**
 * The outcome of one mutation the user asked for, delivered after it has run on the store's worker.
 * Logging is not allowed here, so this boolean is the only way a screen learns a write failed; the
 * word and the path stay inside the store.
 *
 * Called on the store's worker thread. A caller that touches UI must marshal it itself.
 */
internal fun interface PersonalMutationOutcome {
    fun onFinished(succeeded: Boolean)
}

/**
 * Told once when an unreadable personal file has been quarantined, so the empty list can be
 * explained. Separate from [PersonalMutationOutcome] because nothing was requested; it carries no
 * data, since the word and the path do not leave the store.
 *
 * Called on the store's worker thread. A caller that touches UI must marshal it itself.
 */
internal fun interface PersonalQuarantineNotice {
    fun onQuarantined()
}

/**
 * What a quarantined file turned out to hold, delivered on the store's worker after it was read.
 * Two numbers and no words: [wordCount] is how many words came back, [readToEnd] whether nothing
 * is known to be lost. A plain class without a generated `toString`, like every type here.
 */
internal class PersonalQuarantineReport internal constructor(
    val wordCount: Int,
    val readToEnd: Boolean,
)

/**
 * Told what a quarantined file holds: `null` means there is no file, a zero count means there is
 * one (which can still be removed) but nothing was readable.
 *
 * Called on the store's worker thread. A caller that touches UI must marshal it itself.
 */
internal fun interface PersonalQuarantineReportSink {
    fun onInspected(report: PersonalQuarantineReport?)
}

/**
 * Whether a normalized word is known for [subtypeId]: in the bundled dictionary of that language or
 * in its personal dictionary. The pairs store asks this on its worker when a pending pair reaches
 * the learn threshold, never per keystroke. A pair with an unknown context (a typo, a number,
 * another language's word) is dropped.
 */
fun interface PersonalBigramContextMembership {
    fun isKnownContext(subtypeId: String, normalizedContext: String): Boolean
}

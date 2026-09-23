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
 * The seam that owns ONLY the personal-dictionary directory.
 *
 * It is deliberately its own seam, not the dictionary asset's device-protected directory-provider
 * seam (whose name would become a lie for a credential-protected file) and not the emoji recents
 * provider (same root, but a different directory, format and owner — sharing one provider would put
 * "erase the personal dictionary" and "clear the recent emoji" next to each other in code with no
 * reason). Production resolves it under the base (credential-protected) `noBackupFilesDir`.
 */
fun interface PersonalDirectoryProvider {
    fun personalDirectory(): File
}

/**
 * Opens the exclusive temp for writing.
 *
 * It is a seam so the WRITE and FLUSH steps of the whole-file sequence are independently
 * fault-injectable in a plain JVM test. Production returns a plain [FileOutputStream]; the store
 * owns the fsync (via [rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps.syncFile]),
 * the atomic replace and the directory fsync around it.
 */
fun interface PersonalOutputOpener {
    fun open(temp: File): FileOutputStream
}

/**
 * The outcome of ONE mutation the user asked for by hand, delivered AFTER the event has actually
 * run on the store's worker — never before it.
 *
 * It exists because the subsystem had no channel at all for "this did not work": logging is
 * forbidden here by the privacy policy, and every mutation is an event on a background worker whose
 * result nobody waited for, so a screen could only ever report the queueing, not the writing. This
 * carries one boolean and nothing else — the word and the path stay inside the store, exactly as
 * that same policy requires.
 *
 * Called on the store's worker thread. A caller that touches UI must marshal it itself.
 */
internal fun interface PersonalMutationOutcome {
    fun onFinished(succeeded: Boolean)
}

/**
 * Told once when an unreadable personal file has been set aside, so that the empty list the user is
 * about to see can be explained instead of just appearing.
 *
 * Its own seam rather than a second use of [PersonalMutationOutcome]: this is not the outcome of
 * anything the user asked for. It arrives unprompted, on the store's first open, and it carries no
 * boolean because there is nothing to succeed or fail — the words could not be read, and that is the
 * whole message. It carries nothing else for the same reason as everything else here: the word and
 * the path do not leave the store.
 *
 * Called on the store's worker thread. A caller that touches UI must marshal it itself.
 */
internal fun interface PersonalQuarantineNotice {
    fun onQuarantined()
}

/**
 * What a quarantined copy of an unreadable personal file turned out to hold, delivered on the
 * store's worker after the copy has actually been read.
 *
 * Two numbers and no words: [wordCount] is how many words came back, and [readToEnd] is whether
 * anything is known lost. The words themselves go straight into the dictionary if the user asks for
 * that; they do not travel through this seam, because a screen only needs to know how many there
 * are before the person decides.
 *
 * NOT a Kotlin `data class` for the usual reason of this package — nothing here may grow a
 * synthesised `toString` that prints its way into a log.
 */
internal class PersonalQuarantineReport internal constructor(
    val wordCount: Int,
    val readToEnd: Boolean,
)

/**
 * Told what a quarantined copy holds, or that there is none: `null` means no copy at all, which is a
 * different answer from a copy that yielded nothing (that one arrives as a report with a zero count,
 * because the file is still there and can still be removed).
 *
 * Called on the store's worker thread. A caller that touches UI must marshal it itself.
 */
internal fun interface PersonalQuarantineReportSink {
    fun onInspected(report: PersonalQuarantineReport?)
}

/**
 * P1 of Phase 2 (docs/ROADMAP-P2.md): whether a normalized word is one the keyboard knows for
 * [subtypeId] — a word of the shipped dictionary of that language, or a word of its personal
 * dictionary. The personal-bigram store consults this on its worker at GRADUATION time (the moment
 * a pending pair has survived the learn threshold), never per keystroke: a pair whose context the
 * keyboard does not know is dropped rather than written, so a context that is a typo, a number or
 * another language's word can never seed a prediction.
 *
 * Carries the word and returns a boolean and nothing else — the word and the verdict stay inside
 * the subsystem, exactly as the privacy policy of this package requires.
 */
fun interface PersonalBigramContextMembership {
    fun isKnownContext(subtypeId: String, normalizedContext: String): Boolean
}

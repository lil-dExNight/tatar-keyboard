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

import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TrefFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalEmojiStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalOutputOpener
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.RefusedCorrectionStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Real personal stores over a temp directory, producing real `.tpers` / `.tpersb` / `.tpersem` /
 * `.tref` bytes for the backup tests. Learning goes through the stores' own APIs and thresholds, so
 * the fixture bytes are exactly what a device would hold.
 */
internal object BackupTestStores {

    val directExecutor = Executor { it.run() }

    val realOps: DurableFileOps = object : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()
        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()
        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }
        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }
        override fun syncDirectory(directory: File) {}
        override fun delete(file: File): Boolean = file.delete()
    }

    val realOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) }

    fun wordStore(directory: File, subtypeId: String): PersonalDictionaryStore =
        PersonalDictionaryStore(
            subtypeId = subtypeId,
            directoryProvider = { directory },
            fileOps = realOps,
            outputOpener = realOpener,
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L },
            executor = directExecutor,
        )

    fun pairStore(directory: File, subtypeId: String): PersonalBigramStore =
        PersonalBigramStore(
            subtypeId = subtypeId,
            directoryProvider = { directory },
            fileOps = realOps,
            outputOpener = realOpener,
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L },
            executor = directExecutor,
            contextMembership = { _, _ -> true },
        )

    fun emojiStore(directory: File, subtypeId: String): PersonalEmojiStore =
        PersonalEmojiStore(
            subtypeId = subtypeId,
            directoryProvider = { directory },
            fileOps = realOps,
            outputOpener = realOpener,
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L },
            executor = directExecutor,
        )

    /** A word file holding [words], learned through the store's manual add. */
    fun writeWords(directory: File, subtypeId: String, words: List<String>): ByteArray {
        val store = wordStore(directory, subtypeId)
        words.forEach(store::addManually)
        return File(directory, TpersFormat.personalFileName(subtypeId)).readBytes()
    }

    /** A pairs file holding one pair, learned through the store's threshold. */
    fun writePairs(directory: File, subtypeId: String, pair: Pair<String, String>): ByteArray {
        val store = pairStore(directory, subtypeId)
        repeat(2) { store.notePair(pair.first, pair.second) }
        return File(directory, TpersbFormat.personalBigramsFileName(subtypeId)).readBytes()
    }

    /** An emoji file holding one entry, learned through the store's threshold. */
    fun writeEmoji(directory: File, subtypeId: String, entry: Pair<String, String>): ByteArray {
        val store = emojiStore(directory, subtypeId)
        repeat(2) { store.noteObservation(entry.first, entry.second) }
        return File(directory, TpersemFormat.personalEmojiFileName(subtypeId)).readBytes()
    }

    fun refusedStore(directory: File, subtypeId: String): RefusedCorrectionStore =
        RefusedCorrectionStore(
            subtypeId = subtypeId,
            directoryProvider = { directory },
            fileOps = realOps,
            outputOpener = realOpener,
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L },
            executor = directExecutor,
        )

    /** A refused-corrections file holding one suppressed pair, recorded through the store. */
    fun writeRefused(directory: File, subtypeId: String, pair: Pair<String, String>): ByteArray {
        val store = refusedStore(directory, subtypeId)
        repeat(2) { store.noteRefusal(pair.first, pair.second) }
        return File(directory, TrefFormat.refusedCorrectionsFileName(subtypeId)).readBytes()
    }
}

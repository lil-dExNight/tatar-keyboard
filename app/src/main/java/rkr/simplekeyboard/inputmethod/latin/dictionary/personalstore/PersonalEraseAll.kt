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

import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One store's erasure: its `clearAll`, answering through [PersonalMutationOutcome]. */
internal fun interface PersonalErasable {
    fun clearAll(outcome: PersonalMutationOutcome)
}

/**
 * "Erase everything saved": every store empties its memory and deletes its own files, then a sweep
 * deletes whatever is still in the personal directory (temp files, notice marks, quarantined
 * copies, files no store of this build names). [executor] must be the worker the [stores] run on:
 * the sweep is queued behind their erasures, so no write of theirs can race it.
 */
internal class PersonalEraseAll(
    private val stores: List<PersonalErasable>,
    private val directoryProvider: PersonalDirectoryProvider,
    private val fileOps: DurableFileOps,
    private val executor: Executor,
    private val unlockGate: () -> Boolean,
) {
    /**
     * Runs the erasure. [onFinished] gets `true` only when every store reported its files gone and
     * the directory is empty afterwards; it runs on the worker.
     */
    fun run(onFinished: (Boolean) -> Unit) {
        val everyStoreCleared = AtomicBoolean(true)
        for (store in stores) {
            try {
                store.clearAll { cleared -> if (!cleared) everyStoreCleared.set(false) }
            } catch (_: Exception) {
                everyStoreCleared.set(false)
            }
        }
        executor.execute {
            val swept = try {
                sweep()
            } catch (_: Exception) {
                false
            }
            answer(onFinished, everyStoreCleared.get() && swept)
        }
    }

    /**
     * Tells [onReady], on the worker, whether the personal directory holds any file at all. An
     * unreadable directory counts as holding something, so the erase action stays offered.
     */
    fun hasSavedFiles(onReady: (Boolean) -> Unit) {
        executor.execute {
            val found = try {
                unlockGate() && directoryProvider.personalDirectory().let { directory ->
                    directory.exists() && (directory.list()?.isNotEmpty() ?: true)
                }
            } catch (_: Exception) {
                true
            }
            answer(onFinished = onReady, value = found)
        }
    }

    /** Deletes every entry under the personal directory; true when nothing is left. */
    private fun sweep(): Boolean {
        if (!unlockGate()) return false
        val directory = directoryProvider.personalDirectory()
        if (!directory.exists()) return true
        if (!directory.isDirectory) return fileOps.delete(directory) || !directory.exists()
        deleteContents(directory)
        fileOps.syncDirectory(directory)
        return directory.list()?.isEmpty() ?: false
    }

    /**
     * Deletes the children of [directory], depth first. A child directory is entered only when its
     * canonical path stays inside [directory], so a link never leads the sweep outside it.
     */
    private fun deleteContents(directory: File) {
        val children = directory.listFiles() ?: return
        val root = directory.canonicalPath + File.separator
        for (child in children) {
            if (child.isDirectory && child.canonicalPath.startsWith(root)) deleteContents(child)
            fileOps.delete(child)
        }
    }

    /** The callback may post to a screen that has gone away; a throw must not kill the worker. */
    private fun answer(onFinished: (Boolean) -> Unit, value: Boolean) {
        try {
            onFinished(value)
        } catch (_: Exception) {
        }
    }
}

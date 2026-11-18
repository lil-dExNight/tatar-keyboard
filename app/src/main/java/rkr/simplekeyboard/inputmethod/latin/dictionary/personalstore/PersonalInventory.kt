/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore

import android.content.Context
import android.os.UserManager
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps

/** The kinds of saved personal data the inventory lists and erases separately. */
internal enum class PersonalInventoryCategory {
    WORDS,
    PAIRS,
    EMOJI,
    SENTENCE_STARTS,
    REFUSED_CORRECTIONS,
    TEXT_SHORTCUTS,
    PENDING_COUNTERS,
    QUARANTINE,
}

/**
 * What one store could read: its saved entries and its pending learning counters. Counts only —
 * no stored word leaves the store. [available] is false when the store could not be opened.
 */
internal class PersonalStoreInventory(
    val available: Boolean,
    val entryCount: Int,
    val pendingCount: Int,
    val pendingDataPresent: Boolean,
)

/** The worker-side operations the inventory needs from one personal store. */
internal interface PersonalInventoryTarget {
    /** Opens and reads the store on its worker; no other thread may call this. */
    fun readInventoryOnWorker(): PersonalStoreInventory

    /** Deletes the saved entries and nothing else; no other thread may call this. */
    fun clearEntriesOnWorker(): Boolean

    /** Deletes this store's pending counters and salt; no other thread may call this. */
    fun clearPendingOnWorker(): Boolean = true
}

/** Counts by category for the inventory screen; no stored word is part of it. */
internal class PersonalInventorySnapshot(
    val unlocked: Boolean,
    val complete: Boolean,
    private val counts: Map<PersonalInventoryCategory, Int>,
    val pendingDataPresent: Boolean,
) {
    fun countOf(category: PersonalInventoryCategory): Int = counts[category] ?: 0

    fun hasData(category: PersonalInventoryCategory): Boolean =
        countOf(category) > 0 ||
            (category == PersonalInventoryCategory.PENDING_COUNTERS && pendingDataPresent)

    val isEmpty: Boolean
        get() = PersonalInventoryCategory.values().all { !hasData(it) }
}

/**
 * Reads the inventory and erases one category without touching another. Every store operation runs
 * on [executor], the shared personal-store worker, so a category deletion cannot race a write.
 */
internal class PersonalInventoryEngine(
    private val targetsByCategory: Map<PersonalInventoryCategory, List<PersonalInventoryTarget>>,
    private val directoryProvider: PersonalDirectoryProvider,
    private val fileOps: DurableFileOps,
    private val executor: Executor,
    private val unlockGate: () -> Boolean,
) {
    /** Reads every category once and answers on [executor]. */
    fun snapshot(onReady: (PersonalInventorySnapshot) -> Unit) = executor.execute {
        answer(onReady, readOnWorker())
    }

    /** Erases [category], re-reads the inventory and answers both on [executor]. */
    fun clear(
        category: PersonalInventoryCategory,
        onFinished: (Boolean, PersonalInventorySnapshot) -> Unit,
    ) = executor.execute {
        val cleared = if (unlockGate()) clearOnWorker(category) else false
        answer(onFinished, cleared, readOnWorker())
    }

    private fun readOnWorker(): PersonalInventorySnapshot {
        if (!unlockGate()) {
            return PersonalInventorySnapshot(false, false, emptyMap(), false)
        }
        var complete = true
        val reads = IdentityHashMap<PersonalInventoryTarget, PersonalStoreInventory>()
        val counts = LinkedHashMap<PersonalInventoryCategory, Int>()
        for ((category, targets) in targetsByCategory) {
            var total = 0
            for (target in targets) {
                val read = reads[target] ?: readTarget(target).also { reads[target] = it }
                if (!read.available) complete = false
                total += if (category == PersonalInventoryCategory.PENDING_COUNTERS) {
                    read.pendingCount
                } else {
                    read.entryCount
                }
            }
            counts[category] = total
        }
        val pendingDataPresent = targetsByCategory[PersonalInventoryCategory.PENDING_COUNTERS]
            .orEmpty()
            .any { reads[it]?.pendingDataPresent == true }
        val quarantine = scanQuarantine()
        complete = complete && quarantine.readable
        counts[PersonalInventoryCategory.QUARANTINE] = quarantine.fileCount
        return PersonalInventorySnapshot(true, complete, counts, pendingDataPresent)
    }

    private fun readTarget(target: PersonalInventoryTarget): PersonalStoreInventory = try {
        target.readInventoryOnWorker()
    } catch (_: Exception) {
        PersonalStoreInventory(false, 0, 0, false)
    }

    private fun clearOnWorker(category: PersonalInventoryCategory): Boolean {
        if (category == PersonalInventoryCategory.QUARANTINE) return clearQuarantineOnWorker()
        var cleared = true
        for (target in targetsByCategory[category].orEmpty()) {
            val result = try {
                if (category == PersonalInventoryCategory.PENDING_COUNTERS) {
                    target.clearPendingOnWorker()
                } else {
                    target.clearEntriesOnWorker()
                }
            } catch (_: Exception) {
                false
            }
            if (!result) cleared = false
        }
        return cleared
    }

    private fun scanQuarantine(): QuarantineScan {
        val directory = try {
            directoryProvider.personalDirectory()
        } catch (_: Exception) {
            return QuarantineScan(0, false)
        }
        if (!directory.exists()) return QuarantineScan(0, true)
        if (!directory.isDirectory) return QuarantineScan(0, false)
        val files = directory.listFiles() ?: return QuarantineScan(0, false)
        var count = 0
        for (file in files) {
            if (file.isFile && file.name.endsWith(QUARANTINE_SUFFIX)) count++
        }
        return QuarantineScan(count, true)
    }

    private fun clearQuarantineOnWorker(): Boolean {
        val directory = try {
            directoryProvider.personalDirectory()
        } catch (_: Exception) {
            return false
        }
        if (!directory.exists()) return true
        if (!directory.isDirectory) return false
        val files = directory.listFiles() ?: return false
        var attempted = false
        var cleared = true
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(QUARANTINE_SUFFIX)) continue
            attempted = true
            try {
                if (!fileOps.delete(file) && file.exists()) cleared = false
            } catch (_: Exception) {
                cleared = false
            }
        }
        if (attempted) {
            try {
                fileOps.syncDirectory(directory)
            } catch (_: Exception) {
                cleared = false
            }
        }
        val after = scanQuarantine()
        return cleared && after.readable && after.fileCount == 0
    }

    private fun answer(sink: (PersonalInventorySnapshot) -> Unit, value: PersonalInventorySnapshot) {
        try {
            sink(value)
        } catch (_: Exception) {
        }
    }

    private fun answer(
        sink: (Boolean, PersonalInventorySnapshot) -> Unit,
        cleared: Boolean,
        value: PersonalInventorySnapshot,
    ) {
        try {
            sink(cleared, value)
        } catch (_: Exception) {
        }
    }

    private class QuarantineScan(val fileCount: Int, val readable: Boolean)

    private companion object {
        /** Only these files are damaged copies; pending-counter copies use it too. */
        const val QUARANTINE_SUFFIX = ".quarantine"
    }
}

/** Wires the inventory to the process-wide stores and the credential-protected personal folder. */
internal object PersonalInventory {
    /** Reads counts only; [onReady] runs on the shared personal-store worker. */
    fun snapshot(
        context: Context,
        subtypeIds: List<String>,
        onReady: (PersonalInventorySnapshot) -> Unit,
    ) {
        engine(context, subtypeIds).snapshot(onReady)
    }

    /**
     * Erases one category and answers with the fresh inventory; [onFinished] runs on the store
     * worker. The open suggestion strip is unbound when the erased category could be shown there.
     */
    fun clear(
        context: Context,
        subtypeIds: List<String>,
        category: PersonalInventoryCategory,
        onFinished: (Boolean, PersonalInventorySnapshot) -> Unit,
    ) {
        engine(context, subtypeIds).clear(category, onFinished)
        when (category) {
            PersonalInventoryCategory.WORDS,
            PersonalInventoryCategory.SENTENCE_STARTS,
            -> PersonalDictionaries.notifyErased()
            PersonalInventoryCategory.PAIRS -> PersonalBigramDictionaries.notifyErased()
            PersonalInventoryCategory.EMOJI -> PersonalEmojiDictionaries.notifyErased()
            PersonalInventoryCategory.TEXT_SHORTCUTS -> TextShortcutStores.notifyErased()
            PersonalInventoryCategory.REFUSED_CORRECTIONS,
            PersonalInventoryCategory.PENDING_COUNTERS,
            PersonalInventoryCategory.QUARANTINE,
            -> Unit
        }
    }

    private fun engine(context: Context, subtypeIds: List<String>): PersonalInventoryEngine {
        val appContext = context.applicationContext
        val ids = subtypeIds.filter { PersonalSubtypes.isSupported(it) }.distinct()
        val words: List<PersonalInventoryTarget> =
            ids.map { PersonalDictionaries.storeFor(appContext, it) }
        val pairs: List<PersonalInventoryTarget> =
            ids.map { PersonalBigramDictionaries.storeFor(appContext, it) }
        val emoji: List<PersonalInventoryTarget> =
            ids.map { PersonalEmojiDictionaries.storeFor(appContext, it) }
        val starts: List<PersonalInventoryTarget> =
            ids.map { SentStartOverlays.storeFor(appContext, it) }
        val refusals: List<PersonalInventoryTarget> =
            ids.map { RefusedCorrectionStores.storeFor(appContext, it) }
        val shortcuts: List<PersonalInventoryTarget> =
            listOf(TextShortcutStores.storeFor(appContext))
        val pending = words + pairs + emoji + starts
        val userManager = appContext.getSystemService(UserManager::class.java)
        return PersonalInventoryEngine(
            targetsByCategory = mapOf(
                PersonalInventoryCategory.WORDS to words,
                PersonalInventoryCategory.PAIRS to pairs,
                PersonalInventoryCategory.EMOJI to emoji,
                PersonalInventoryCategory.SENTENCE_STARTS to starts,
                PersonalInventoryCategory.REFUSED_CORRECTIONS to refusals,
                PersonalInventoryCategory.TEXT_SHORTCUTS to shortcuts,
                PersonalInventoryCategory.PENDING_COUNTERS to pending,
                PersonalInventoryCategory.QUARANTINE to emptyList(),
            ),
            directoryProvider = {
                File(appContext.noBackupFilesDir, AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME)
            },
            fileOps = AndroidDurableFileOps,
            executor = PersonalDictionaries.sharedStoreExecutor(),
            unlockGate = { userManager?.isUserUnlocked ?: false },
        )
    }
}

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

import android.content.Context
import android.os.UserManager
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalEmojiSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SnapshotPersonalEmojiSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.AndroidDurableFileOps
import java.io.File
import java.io.FileOutputStream

/**
 * The process-wide owner of the learned-emoji stores, one per subtype, on
 * [PersonalDictionaries.sharedStoreExecutor]. See [PersonalDictionaries]. There is no
 * context-membership probe (see [PersonalEmojiStore]).
 *
 * The store is built here ([createStore]) with the same seams as [AndroidPersonalDictionaryStorage]:
 * the credential-protected `noBackupFilesDir`, the shared durable ops and the `isUserUnlocked` gate.
 */
object PersonalEmojiDictionaries {

    private val lock = Any()
    private val stores = HashMap<String, PersonalEmojiStore>()

    /** Notified after entries are erased. See `PersonalDictionaries.erasureListener`. */
    @Volatile
    private var erasureListener: Runnable? = null

    /** Notified when a store quarantines an unreadable file. See `PersonalDictionaries.quarantineListener`. */
    @Volatile
    private var quarantineListener: Runnable? = null

    /** Guarded by [lock]; one entry per language that lost something. */
    private val pendingQuarantineNotices = LinkedHashSet<String>()

    /** The store for [subtypeId], created on first use. Safe to call from any thread. */
    internal fun storeFor(context: Context, subtypeId: String): PersonalEmojiStore =
        synchronized(lock) {
            stores.getOrPut(subtypeId) {
                createStore(context, subtypeId) {
                    notifyQuarantined(subtypeId)
                }
            }
        }

    /**
     * The engine's read side for [subtypeId]; see [PersonalDictionaries.sourceFor]. Performs no I/O
     * (the store opens lazily on its worker), so it may be called for every emoji-cell query.
     */
    @JvmStatic
    fun sourceFor(
        context: Context,
        subtypeId: String,
        gate: PersonalDictionaryGate,
    ): PersonalEmojiSource {
        val store = storeFor(context, subtypeId)
        if (gate.isOn()) store.prime()
        // Built in the `personal` package from a snapshot supplier; see PersonalDictionaries.sourceFor.
        return SnapshotPersonalEmojiSource {
            if (gate.isOn()) store.snapshot else PersonalEmojiDictionary.EMPTY
        }
    }

    /** The current snapshot of [subtypeId], for the "Personal dictionary" screen. */
    internal fun snapshotFor(context: Context, subtypeId: String): PersonalEmojiDictionary =
        storeFor(context, subtypeId).also { it.prime() }.snapshot

    /** The backup restore for one language. See [PersonalDictionaries.replaceAll]. */
    internal fun replaceAll(
        context: Context,
        subtypeId: String,
        bytes: ByteArray?,
        outcome: PersonalMutationOutcome,
    ) = storeFor(context, subtypeId).replaceAll(bytes, outcome)

    @JvmStatic
    fun setErasureListener(listener: Runnable?) {
        erasureListener = listener
    }

    /** Called by the screen once an erasure event has been queued on the store's worker. */
    internal fun notifyErased() {
        erasureListener?.run()
    }

    @JvmStatic
    fun setQuarantineListener(listener: Runnable?) {
        quarantineListener = listener
    }

    /** Whether a notice is still waiting to be shown; see [quarantineListener]. */
    @JvmStatic
    fun hasPendingQuarantineNotice(): Boolean =
        synchronized(lock) { pendingQuarantineNotices.isNotEmpty() }

    /** Takes one waiting notice. See [PersonalDictionaries.consumeQuarantineNotice]. */
    @JvmStatic
    fun consumeQuarantineNotice(): Boolean {
        val subtypeId = synchronized(lock) {
            val next = pendingQuarantineNotices.firstOrNull() ?: return false
            pendingQuarantineNotices.remove(next)
            next
        }
        synchronized(lock) { stores[subtypeId] }?.noticeDelivered()
        return true
    }

    /**
     * The factory wiring: what [AndroidPersonalDictionaryStorage.createBigrams] assembles, without
     * the membership gate.
     */
    private fun createStore(
        context: Context,
        subtypeId: String,
        quarantineNotice: PersonalQuarantineNotice,
    ): PersonalEmojiStore {
        val appContext = context.applicationContext
        val userManager = appContext.getSystemService(UserManager::class.java)
        return PersonalEmojiStore(
            subtypeId = subtypeId,
            directoryProvider = {
                File(appContext.noBackupFilesDir, AndroidPersonalDictionaryStorage.PERSONAL_DIRECTORY_NAME)
            },
            fileOps = AndroidDurableFileOps,
            outputOpener = { temp -> FileOutputStream(temp) },
            spaceProbe = { directory -> directory.usableSpace },
            clock = { System.currentTimeMillis() },
            executor = PersonalDictionaries.sharedStoreExecutor(),
            unlockGate = { userManager?.isUserUnlocked ?: false },
            quarantineNotice = quarantineNotice,
        )
    }

    /** Called on the store's worker when it quarantined an unreadable file. */
    private fun notifyQuarantined(subtypeId: String) {
        synchronized(lock) { pendingQuarantineNotices.add(subtypeId) }
        quarantineListener?.run()
    }

    /** Test hook: drops every cached store so an isolated test starts from nothing. */
    internal fun resetForTest() {
        synchronized(lock) {
            stores.clear()
            pendingQuarantineNotices.clear()
        }
        erasureListener = null
        quarantineListener = null
    }
}

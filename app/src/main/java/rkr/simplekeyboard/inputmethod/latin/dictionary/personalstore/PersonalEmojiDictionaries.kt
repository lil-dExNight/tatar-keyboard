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
 * The ONE process-wide owner of the personal-emoji stores — one store per subtype, serialized on
 * the SAME single background executor as the words and pairs stores (see
 * [PersonalDictionaries.sharedStoreExecutor]), so the three features can never race each other's
 * in-flight temp files in the shared `personal/` directory.
 *
 * Everything else mirrors [PersonalBigramDictionaries]: the settings screen and the IME share the
 * one process, so the screen can forget an entry or erase all of them through the same serialized
 * owner the engine reads from; and reading is gated live through [PersonalDictionaryGate], so
 * turning the personal-dictionary setting off stops personal emoji on the very next lookup.
 *
 * Unlike the pairs facade there is no context-membership probe to install: the emoji store's word
 * half is real committed editor text and needs no dictionary gate (see the store's class doc).
 *
 * The store construction is wired HERE rather than added to [AndroidPersonalDictionaryStorage]
 * because this feature layer was built without touching the existing factory: the seams are the
 * factory's own, field for field — the base (credential-protected) `noBackupFilesDir`, the shared
 * durable ops, the `isUserUnlocked` gate.
 */
object PersonalEmojiDictionaries {

    private val lock = Any()
    private val stores = HashMap<String, PersonalEmojiStore>()

    /**
     * Notified after entries are erased ("Erase all" / "Forget"), so the IME can unbind whatever is
     * still displayed — the "erased means erased" guarantee of the words store, for emoji.
     */
    @Volatile
    private var erasureListener: Runnable? = null

    /** Notified when an unreadable emoji file has been set aside on a store's first open. */
    @Volatile
    private var quarantineListener: Runnable? = null

    /** Guarded by [lock]; one entry per language that lost something (2026-09-24 audit, F13). */
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
     * The engine's read side for [subtypeId]. Returns [PersonalEmojiSource.EMPTY] semantics
     * whenever the setting is off, so a disabled personal dictionary costs the suggestion path
     * nothing beyond one boolean read.
     *
     * Performs no I/O — the store's lazy open stays on its worker — so the learned-emoji read
     * path may call this per emoji-candidate query from the band fill; the map lookup and the
     * small wrapper allocation are the only cost.
     */
    @JvmStatic
    fun sourceFor(
        context: Context,
        subtypeId: String,
        gate: PersonalDictionaryGate,
    ): PersonalEmojiSource {
        val store = storeFor(context, subtypeId)
        if (gate.isOn()) store.prime()
        // The source itself is built in the `personal` package, which owns the read model: this
        // package hands it nothing but a supplier of the published snapshot. That is also what
        // keeps the frozen privacy rule of the store package true — no method name here names
        // typed text.
        return SnapshotPersonalEmojiSource {
            if (gate.isOn()) store.snapshot else PersonalEmojiDictionary.EMPTY
        }
    }

    /** The current snapshot of [subtypeId], for the "Personal dictionary" screen. */
    internal fun snapshotFor(context: Context, subtypeId: String): PersonalEmojiDictionary =
        storeFor(context, subtypeId).also { it.prime() }.snapshot

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

    /**
     * Takes ONE waiting notice — the earliest-raised language's — if there is one; see
     * [PersonalDictionaries.consumeQuarantineNotice] for the contract this mirrors, including
     * spending the durable mark of THAT language's store only, so a second language that lost
     * something keeps its own notice (2026-09-24 audit, finding 13).
     */
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
     * The factory wiring, field for field what [AndroidPersonalDictionaryStorage.createBigrams]
     * assembles minus the membership gate the emoji store does not have (see the class doc for why
     * this lives here).
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

    /** Called on the store's worker when it set an unreadable file aside. */
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

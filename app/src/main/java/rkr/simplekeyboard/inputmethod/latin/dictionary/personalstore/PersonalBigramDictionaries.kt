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
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SnapshotPersonalBigramSource

/**
 * The process-wide owner of the learned-pairs stores, one per subtype, on
 * [PersonalDictionaries.sharedStoreExecutor]. See [PersonalDictionaries].
 *
 * The context-membership probe ([setContextMembershipProbe]) answers whether the bundled dictionary
 * knows a context word; the store asks it on its worker at graduation. It lives here because it
 * needs the live engines, which the IME owns. Without a probe only the personal dictionary answers,
 * and an unknown context does not graduate.
 */
object PersonalBigramDictionaries {

    private val lock = Any()
    private val stores = HashMap<String, PersonalBigramStore>()

    /** Notified after pairs are erased. See `PersonalDictionaries.erasureListener`. */
    @Volatile
    private var erasureListener: Runnable? = null

    /** Notified when a store quarantines an unreadable file. See `PersonalDictionaries.quarantineListener`. */
    @Volatile
    private var quarantineListener: Runnable? = null

    /** Guarded by [lock]; one entry per language that lost something. */
    private val pendingQuarantineNotices = LinkedHashSet<String>()

    /**
     * The bundled-dictionary half of the context gate, installed by the IME and cleared on its
     * destroy. Called on a store's worker thread, never on the UI thread.
     */
    @Volatile
    private var contextMembershipProbe: PersonalBigramContextMembership? = null

    /** The store for [subtypeId], created on first use. Safe to call from any thread. */
    internal fun storeFor(context: Context, subtypeId: String): PersonalBigramStore =
        synchronized(lock) {
            stores.getOrPut(subtypeId) {
                AndroidPersonalDictionaryStorage.createBigrams(
                    context,
                    subtypeId,
                    PersonalDictionaries.sharedStoreExecutor(),
                    contextMembership(context),
                ) {
                    notifyQuarantined(subtypeId)
                }
            }
        }

    /** The engine's read side for [subtypeId]. See [PersonalDictionaries.sourceFor]. */
    @JvmStatic
    fun sourceFor(
        context: Context,
        subtypeId: String,
        gate: PersonalDictionaryGate,
    ): PersonalBigramSource {
        val store = storeFor(context, subtypeId)
        if (gate.isOn()) store.prime()
        // Built in the `personal` package from a snapshot supplier; see PersonalDictionaries.sourceFor.
        return SnapshotPersonalBigramSource {
            if (gate.isOn()) store.snapshot else PersonalBigramDictionary.EMPTY
        }
    }

    /** The current snapshot of [subtypeId], for the "Personal dictionary" screen. */
    internal fun snapshotFor(context: Context, subtypeId: String): PersonalBigramDictionary =
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

    /** Installs (or clears, with null) the dictionary half of the context gate. See the class doc. */
    @JvmStatic
    fun setContextMembershipProbe(probe: PersonalBigramContextMembership?) {
        contextMembershipProbe = probe
    }

    /**
     * The context gate a store is built with: first the subtype's personal dictionary snapshot (no
     * I/O, works without an engine), then the IME-installed probe. An unopened personal store and a
     * missing probe both answer "unknown", so only a positive answer lets a pair graduate.
     */
    private fun contextMembership(context: Context): PersonalBigramContextMembership {
        val appContext = context.applicationContext
        return PersonalBigramContextMembership { subtypeId, normalizedContext ->
            val personalKnown = try {
                PersonalDictionaries.snapshotFor(appContext, subtypeId)
                    .indexOfNormalized(normalizedContext) >= 0
            } catch (_: Exception) {
                false
            }
            personalKnown ||
                contextMembershipProbe?.isKnownContext(subtypeId, normalizedContext) == true
        }
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
        contextMembershipProbe = null
    }
}

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
 * The ONE process-wide owner of the personal-bigram stores (P1 of Phase 2, docs/ROADMAP-P2.md) —
 * one store per subtype, serialized on the SAME single background executor as the words stores
 * (see [PersonalDictionaries.sharedStoreExecutor]), so the two features can never race each
 * other's in-flight temp files in the shared `personal/` directory.
 *
 * Everything else mirrors [PersonalDictionaries]: the settings screen and the IME share the one
 * process, so the screen can forget a pair or erase all of them through the same serialized owner
 * the engine reads from; and reading is gated live through [PersonalDictionaryGate], so turning
 * the personal-dictionary setting off stops personal predictions on the very next lookup.
 *
 * The context-membership probe ([setContextMembershipProbe]) is the dictionary half of the
 * learn-time context gate: the store consults it on its worker at graduation, and it is owned
 * here — not by the store — because answering it needs the live engines, which belong to the IME
 * that wires the probe. With no probe installed the personal-dictionary half alone answers, and
 * an unknown context simply does not graduate: fail-closed, exactly like a cold engine.
 */
object PersonalBigramDictionaries {

    private val lock = Any()
    private val stores = HashMap<String, PersonalBigramStore>()

    /**
     * Notified after pairs are erased ("Erase all" / "Forget"), so the IME can unbind whatever is
     * still displayed — the "erased means erased" guarantee of the words store, for predictions.
     */
    @Volatile
    private var erasureListener: Runnable? = null

    /** Notified when an unreadable bigram file has been set aside on a store's first open. */
    @Volatile
    private var quarantineListener: Runnable? = null

    /** Guarded by [lock]; one entry per language that lost something (2026-09-24 audit, F13). */
    private val pendingQuarantineNotices = LinkedHashSet<String>()

    /**
     * The dictionary-membership half of the context gate, installed by the IME (which is the only
     * place that can reach the engines) and cleared on its destroy. Called on a store's worker
     * thread, never on the UI thread.
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

    /**
     * The engine's read side for [subtypeId]. Returns [PersonalBigramSource.EMPTY] semantics
     * whenever the setting is off, so a disabled personal dictionary costs the prediction path
     * nothing beyond one boolean read.
     *
     * Called from the controller's background executor at engine start (the store's first open
     * reads a file), never from the UI thread.
     */
    @JvmStatic
    fun sourceFor(
        context: Context,
        subtypeId: String,
        gate: PersonalDictionaryGate,
    ): PersonalBigramSource {
        val store = storeFor(context, subtypeId)
        if (gate.isOn()) store.prime()
        // The source itself is built in the `personal` package, which owns the read model: this
        // package hands it nothing but a supplier of the published snapshot. That is also what
        // keeps the frozen privacy rule of the store package true — no method name here names
        // typed text.
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

    /** Installs (or clears, with null) the dictionary half of the context gate. See the class doc. */
    @JvmStatic
    fun setContextMembershipProbe(probe: PersonalBigramContextMembership?) {
        contextMembershipProbe = probe
    }

    /**
     * The context gate one store is constructed with: the personal dictionary of the subtype first
     * (a word the user saved is a known word even with no engine running — read off the published
     * snapshot, which costs no I/O), then the IME-installed probe (the shipped dictionary's half).
     * Both fail closed: a personal store that was never opened answers EMPTY, and a missing probe
     * answers unknown, so a context graduates only on a positive answer somebody really gave.
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
        contextMembershipProbe = null
    }
}

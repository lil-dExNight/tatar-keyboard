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

package rkr.simplekeyboard.inputmethod.latin.suggestions

/**
 * Everything that belongs to ONE language: its storage seams, its readiness, its engine and
 * the bookkeeping of its preparation and release.
 *
 * Slots are what makes switching layouts free. Both dictionaries are separate artifacts in
 * separate device-protected directories with separate leases, so the engine of a language the
 * user leaves is simply idled ([EngineHandle.finishInput]) and kept warm — it is NOT torn down.
 * A teardown blocks the UI thread for up to 240 ms and its release is deliberately deferred to a
 * lifecycle boundary, so tearing down on every press of the globe key would either stall the
 * keystroke or leave the user with no suggestions until they left the field. Warm slots cost one
 * idle worker thread and one read-only mapping each; the mapping is file-backed, so its pages are
 * evictable and only the ones actually touched by a lookup are resident.
 *
 * Pure move from `SuggestionsController.kt` (ROADMAP Phase 6, T2): the class referenced no outer
 * state, so the `inner` modifier is simply gone; `internal` replaces file-private for the same
 * reason.
 */
internal class LanguageSlot(val subtypeId: String) {
    /** Storage seam, created on this language's first preparation request. */
    @Volatile
    var preparation: DictionaryPreparation? = null

    /** E5c two-stage readiness: same lazy-seam shape as [preparation], for the bigram table. */
    @Volatile
    var bigramPreparation: BigramPreparation? = null

    @Volatile
    var dictionaryReady: Boolean = false

    // Read by [SuggestionsController.engineContainsWord] from the personal store's worker thread
    // (P1), hence `@Volatile`; the handle behind it answers cross-thread membership reads by
    // construction.
    @Volatile
    var engine: EngineHandle? = null
    var starting: Boolean = false

    // Lifecycle of the "preparation requested" flag, in one place because nothing below it
    // de-duplicates: it is set the moment preparation is requested, it is NEVER cleared after a
    // Published result (readiness survives every later transition of the setting and the engine
    // is restarted from the already published file), and it is cleared ONLY when the last known
    // result was Unavailable and a fresh OFF -> ON transition of the setting has been observed.
    var preparationRequested: Boolean = false
    var lastPreparationUnavailable: Boolean = false

    // Provenance of the outstanding request, so an Unavailable result can tell the two callers
    // of requestPreparationIfNeeded apart. Only a request made because the user turned the
    // setting on is reported to the controller's dictionary-unavailable listener.
    var preparationRequestedByExplicitEnable: Boolean = false

    // Set when the setting goes ON -> OFF. The blocking engine teardown is deferred to the next
    // lifecycle boundary instead of running inside the settings handler, where it would hold
    // the UI thread for up to 240 ms on the very keystroke that flipped the setting.
    var releasePending: Boolean = false

    // True once a deferred release has been attempted at a boundary and refused. The attempt is
    // not free of consequences: the engine has already been told to stop and rejects every
    // later lookup, so it is no longer a correct mapping and the setting coming back on may no
    // longer cancel its release. Without this the cancellation would strand a permanently dead
    // engine — nothing would release it and nothing would replace it — and the user would see
    // an empty band for the rest of the process.
    var releaseAttemptFailed: Boolean = false
}

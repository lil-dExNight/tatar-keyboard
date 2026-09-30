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
 * Slots make switching layouts cheap. The engine of a language the user leaves is only idled
 * ([EngineHandle.finishInput]) and kept warm, not torn down: a teardown blocks the UI thread and
 * its release is deferred to a lifecycle boundary, so tearing down on every globe-key press would
 * stall the keystroke or leave the strip empty until the user left the field. A warm slot costs
 * one idle worker thread and one read-only, file-backed mapping whose pages are evictable.
 */
internal class LanguageSlot(val subtypeId: String) {
    /** Storage seam, created on this language's first preparation request. */
    @Volatile
    var preparation: DictionaryPreparation? = null

    /** Storage seam of the bigram table, created lazily like [preparation]. */
    @Volatile
    var bigramPreparation: BigramPreparation? = null

    @Volatile
    var dictionaryReady: Boolean = false

    // Read by [SuggestionsController.engineContainsWord] from the personal store's worker thread,
    // hence `@Volatile`; the handle behind it is safe for cross-thread membership reads.
    @Volatile
    var engine: EngineHandle? = null
    var starting: Boolean = false

    // Set when preparation is requested. Never cleared after a Published result (the engine is
    // restarted from the already published file); cleared only when the last result was
    // Unavailable and the setting has gone OFF -> ON again. Nothing below de-duplicates requests.
    var preparationRequested: Boolean = false
    var lastPreparationUnavailable: Boolean = false

    // Provenance of the outstanding request, so an Unavailable result can tell the two callers
    // of requestPreparationIfNeeded apart. Only a request made because the user turned the
    // setting on is reported to the controller's dictionary-unavailable listener.
    var preparationRequestedByExplicitEnable: Boolean = false

    // Set when the setting goes ON -> OFF. The blocking engine teardown is deferred to the next
    // lifecycle boundary so it does not block the UI thread inside the settings handler.
    var releasePending: Boolean = false

    // True once a deferred release was attempted at a boundary and refused. The engine has
    // already been told to stop and rejects every lookup, so turning the setting back on must not
    // cancel its release; otherwise a dead engine would stay in the slot and the strip would stay
    // empty for the rest of the process.
    var releaseAttemptFailed: Boolean = false
}

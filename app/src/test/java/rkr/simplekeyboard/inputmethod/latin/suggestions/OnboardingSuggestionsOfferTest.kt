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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.common.Constants

/**
 * The onboarding/offer interaction: the done-block switch in SetupActivity writes the same
 * suggestions pref the settings row writes, and the one-shot in-keyboard offer must survive
 * onboarding untouched — pending for a user who leaves the switch OFF, and unspent (never
 * shown, never burned) for a user who turns it ON there. The behavior half runs on the
 * [SuggestionsOfferController] seams; the wiring half is a source contract on SetupActivity.
 */
class OnboardingSuggestionsOfferTest {

    // --- Fakes ---------------------------------------------------------------------------------

    /** Every condition starts in the state that allows the offer. */
    private class FakeEnvironment : OfferEnvironment {
        var suggestionsSettingEnabled = false

        override fun isSuggestionsSettingEnabled(): Boolean = suggestionsSettingEnabled
        override fun isTatarSubtypeActive(): Boolean = true
        override fun isInputViewShownWithWindowToken(): Boolean = true
        override fun editorAllowsSuggestions(): Boolean = true
        override fun isUserUnlocked(): Boolean = true
        override fun isKeyguardLocked(): Boolean = false
        override fun isAnotherDialogShowing(): Boolean = false
        override fun isImeSuppressedByHardwareKeyboard(): Boolean = false
        override fun isInDraggingFinger(): Boolean = false

        /** A three-letter word plus the separator, the shortest text that earns the offer. */
        override fun cachedTextBeforeCursor(): CharSequence = "сүз "
    }

    private class FakeFlagStore : OfferFlagStore {
        var spendCount = 0
            private set
        private var spent = false

        override fun isOfferSpent(): Boolean = spent

        override fun spendOffer() {
            spendCount++
            spent = true
        }
    }

    private class FakePresenter : OfferPresenter {
        var offerCount = 0
            private set

        override fun showEnableOffer(): Boolean {
            offerCount++
            return true
        }

        override fun showUnavailableMessage() {}
    }

    private class Harness {
        val environment = FakeEnvironment()
        val flags = FakeFlagStore()
        val presenter = FakePresenter()
        val controller = SuggestionsOfferController(environment, flags, presenter)
    }

    /** A space after the fake's three-letter word: the offer's only trigger. */
    private fun finishAWord(h: Harness) {
        h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)
    }

    // --- The behavior --------------------------------------------------------------------------

    @Test
    fun leavingTheOnboardingSwitchOffKeepsTheInKeyboardOfferPending() {
        // A switch left alone writes nothing, so the durable flag is still unspent when the
        // onboarding screen closes: the first real word earns the offer as if the card had
        // never existed.
        val h = Harness()

        assertTrue(h.controller.isOfferPending())
        assertEquals(0, h.flags.spendCount)

        finishAWord(h)

        assertEquals(1, h.presenter.offerCount)
        assertEquals(1, h.flags.spendCount)
    }

    @Test
    fun enablingFromOnboardingShowsNoOfferAndBurnsNothing() {
        val h = Harness()
        // What the onboarding switch writes: the setting reads ON from the next read on.
        h.environment.suggestionsSettingEnabled = true

        repeat(5) { finishAWord(h) }

        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.flags.spendCount)
        // Pending, not burned: a later OFF in the settings re-arms the one-shot offer.
        assertTrue(h.controller.isOfferPending())
        h.environment.suggestionsSettingEnabled = false
        finishAWord(h)
        assertEquals(1, h.presenter.offerCount)
        assertEquals(1, h.flags.spendCount)
    }

    // --- The wiring ----------------------------------------------------------------------------

    @Test
    fun setupActivityWritesOnlyTheSettingAndNeverTheOfferFlag() {
        val setup = File(sourceRoot(),
                "java/rkr/simplekeyboard/inputmethod/latin/setup/SetupActivity.kt").readText()

        assertTrue("the card reads the live pref for its initial state",
                setup.contains("readTatarSuggestionsEnabled"))
        assertTrue("the switch writes through Settings.writeTatarSuggestionsEnabled",
                setup.contains("writeTatarSuggestionsEnabled"))
        assertFalse("onboarding must never spend the one-shot offer",
                setup.contains("writeTatarSuggestionsOfferSpent"))
        assertFalse("nor even name its key",
                setup.contains("PREF_TATAR_SUGGESTIONS_OFFER_SPENT"))
    }

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }
}

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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.event.Event
import rkr.simplekeyboard.inputmethod.latin.common.Constants

class SuggestionsOfferControllerTest {

    // --- Fakes ---------------------------------------------------------------------------------

    /** Every condition starts in the state that allows the offer; a test flips the one it studies. */
    private class FakeEnvironment : OfferEnvironment {
        var suggestionsSettingEnabled = false
        var tatarSubtypeActive = true
        var inputViewShownWithWindowToken = true

        /** `mInputAttributes.mShouldShowSuggestions`: what the field's own inputType allows. */
        var editorInputTypeAllowsSuggestions = true

        /**
         * `mInputAttributes.mNoPersonalizedLearning`: the editor put
         * `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` in its imeOptions. Independent of the
         * inputType, which is why it needs a variable of its own: the fields that set it (an
         * incognito browser tab, a messenger's "incognito keyboard" switch) are ordinary text
         * fields whose inputType allows suggestions.
         */
        var editorForbidsPersonalizedLearning = false

        var userUnlocked = true
        var anotherDialogShowing = false
        var imeSuppressedByHardwareKeyboard = false
        var inDraggingFinger = false
        var textBeforeCursor: CharSequence? = "сүз "
        var textReads = 0

        override fun isSuggestionsSettingEnabled(): Boolean = suggestionsSettingEnabled
        override fun isTatarSubtypeActive(): Boolean = tatarSubtypeActive
        override fun isInputViewShownWithWindowToken(): Boolean = inputViewShownWithWindowToken
        /**
         * Mirrors how LatinIME composes the two halves of this condition. The real composition is
         * Android-side and is pinned separately by [SuggestionStripSourceContractTest].
         */
        override fun editorAllowsSuggestions(): Boolean =
            editorInputTypeAllowsSuggestions && !editorForbidsPersonalizedLearning
        override fun isUserUnlocked(): Boolean = userUnlocked
        override fun isAnotherDialogShowing(): Boolean = anotherDialogShowing
        override fun isImeSuppressedByHardwareKeyboard(): Boolean = imeSuppressedByHardwareKeyboard
        override fun isInDraggingFinger(): Boolean = inDraggingFinger

        override fun cachedTextBeforeCursor(): CharSequence? {
            textReads++
            return textBeforeCursor
        }
    }

    private class FakeFlagStore(private var spent: Boolean = false) : OfferFlagStore {
        var spendCount = 0

        override fun isOfferSpent(): Boolean = spent

        override fun spendOffer() {
            spendCount++
            spent = true
        }
    }

    private class FakePresenter : OfferPresenter {
        var offerCount = 0
        var messageCount = 0

        override fun showEnableOffer() {
            offerCount++
        }

        override fun showUnavailableMessage() {
            messageCount++
        }
    }

    private class Harness(offerSpent: Boolean = false) {
        val environment = FakeEnvironment()
        val flags = FakeFlagStore(offerSpent)
        val presenter = FakePresenter()
        val controller = SuggestionsOfferController(environment, flags, presenter)
    }

    // --- The trigger: which key presses finish a word --------------------------------------------

    @Test
    fun enterIsNotATriggerEvenThoughItIsAWordSeparator() {
        val h = Harness()

        // '\n' is a real code point and IS listed in symbols_word_separators, so the editor's own
        // classification says "separator" here. Sending a message must still not spend the offer:
        // the host app hides the keyboard in response and hideWindow() would dismiss the dialog in
        // the same frame, burning the single chance to make the offer.
        h.controller.onKeyPressCommitted(Constants.CODE_ENTER, isWordSeparator = true)

        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.flags.spendCount)
        assertTrue(h.controller.isOfferPending())
        // The text is not even read for a key that cannot be a trigger.
        assertEquals(0, h.environment.textReads)
    }

    @Test
    fun tabIsNotATriggerEvenThoughItIsAWordSeparator() {
        val h = Harness()

        h.controller.onKeyPressCommitted(Constants.CODE_TAB, isWordSeparator = true)

        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.flags.spendCount)
        assertTrue(h.controller.isOfferPending())
    }

    @Test
    fun functionalKeysCarryingNoCodePointAreNotTriggers() {
        val h = Harness()

        // Delete and the language key: negative key code, NOT_A_CODE_POINT, never a separator.
        h.controller.onKeyPressCommitted(Event.NOT_A_CODE_POINT, isWordSeparator = false)

        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.flags.spendCount)
    }

    @Test
    fun spaceAfterAThreeLetterWordShowsTheOfferExactlyOnce() {
        val h = Harness()

        h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)

        assertEquals(1, h.presenter.offerCount)
        assertEquals(1, h.flags.spendCount)
        assertFalse(h.controller.isOfferPending())

        // Second separator: the flag is spent, so nothing happens and nothing is read.
        val readsAfterOffer = h.environment.textReads
        h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)
        assertEquals(1, h.presenter.offerCount)
        assertEquals(1, h.flags.spendCount)
        assertEquals(readsAfterOffer, h.environment.textReads)
    }

    @Test
    fun punctuationAfterAThreeLetterWordShowsTheOffer() {
        val h = Harness()
        h.environment.textBeforeCursor = "сүз."

        h.controller.onKeyPressCommitted(Constants.CODE_PERIOD, isWordSeparator = true)

        assertEquals(1, h.presenter.offerCount)
    }

    @Test
    fun letterKeysAreNotTriggers() {
        val h = Harness()

        h.controller.onKeyPressCommitted('з'.code, isWordSeparator = false)

        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.environment.textReads)
    }

    @Test
    fun wordShorterThanThreeLettersNeverRequestsTheOffer() {
        val h = Harness()
        h.environment.textBeforeCursor = "сү "

        repeat(10) { h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true) }

        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.flags.spendCount)
        assertTrue(h.controller.isOfferPending())
    }

    @Test
    fun isWordFinishingKeyPressExcludesOnlyEnterAndTab() {
        assertFalse(
            SuggestionsOfferController.isWordFinishingKeyPress(Constants.CODE_ENTER, true),
        )
        assertFalse(SuggestionsOfferController.isWordFinishingKeyPress(Constants.CODE_TAB, true))
        assertFalse(
            SuggestionsOfferController.isWordFinishingKeyPress(Event.NOT_A_CODE_POINT, false),
        )
        assertFalse(SuggestionsOfferController.isWordFinishingKeyPress('з'.code, false))
        assertTrue(SuggestionsOfferController.isWordFinishingKeyPress(Constants.CODE_SPACE, true))
        assertTrue(SuggestionsOfferController.isWordFinishingKeyPress(Constants.CODE_PERIOD, true))
        assertTrue(SuggestionsOfferController.isWordFinishingKeyPress(Constants.CODE_COMMA, true))
    }

    // --- Conditions of the offer ------------------------------------------------------------------

    @Test
    fun alreadySpentFlagKeepsTheKeystrokePathSilentAndTextUnread() {
        val h = Harness(offerSpent = true)

        h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)

        assertFalse(h.controller.isOfferPending())
        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.environment.textReads)
    }

    @Test
    fun settingAlreadyOnSuppressesTheOffer() {
        val h = Harness()
        h.environment.suggestionsSettingEnabled = true

        h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)

        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.flags.spendCount)
    }

    @Test
    fun eachEnvironmentConditionSuppressesTheOfferWithoutSpendingTheFlag() {
        val breakers = listOf<(FakeEnvironment) -> Unit>(
            { it.tatarSubtypeActive = false },
            { it.inputViewShownWithWindowToken = false },
            { it.editorInputTypeAllowsSuggestions = false },
            { it.editorForbidsPersonalizedLearning = true },
            { it.userUnlocked = false },
            { it.anotherDialogShowing = true },
            { it.imeSuppressedByHardwareKeyboard = true },
            { it.inDraggingFinger = true },
        )

        breakers.forEach { breaker ->
            val h = Harness()
            breaker(h.environment)

            h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)

            assertEquals(0, h.presenter.offerCount)
            assertEquals(0, h.flags.spendCount)
            assertTrue(h.controller.isOfferPending())
        }
    }

    @Test
    fun aFieldThatForbidsSuggestionsIsNeverRead() {
        val h = Harness()
        h.environment.editorInputTypeAllowsSuggestions = false

        h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)

        assertEquals(0, h.environment.textReads)
    }

    // --- IME_FLAG_NO_PERSONALIZED_LEARNING ---------------------------------------------------------

    /**
     * An incognito field: an ordinary short-message text field whose inputType allows
     * suggestions — so [FakeEnvironment.editorInputTypeAllowsSuggestions] stays true, exactly as in
     * the control test below — but whose imeOptions carry `IME_FLAG_NO_PERSONALIZED_LEARNING`.
     * Signal's "incognito keyboard" switch and a Chrome incognito tab both produce this shape.
     */
    private fun Harness.makeFieldIncognito() {
        environment.editorInputTypeAllowsSuggestions = true
        environment.editorForbidsPersonalizedLearning = true
    }

    @Test
    fun anIncognitoFieldNeverShowsTheOfferAndNeverSpendsTheOneShotFlag() {
        val h = Harness()
        h.makeFieldIncognito()

        repeat(10) { h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true) }

        assertEquals(0, h.presenter.offerCount)
        assertEquals(0, h.flags.spendCount)
        // The single chance is not burned by the incognito field either: refusing to act there must
        // not cost the user the offer they would otherwise get in an ordinary field.
        assertTrue(h.controller.isOfferPending())
        h.environment.editorForbidsPersonalizedLearning = false
        h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)
        assertEquals(1, h.presenter.offerCount)
    }

    @Test
    fun anIncognitoFieldIsNeverRead() {
        val h = Harness()
        h.makeFieldIncognito()

        repeat(10) { h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true) }

        // The whole point of ordering the environment check before the text read: what was typed in
        // a field that asked not to be personalized for is not even looked at.
        assertEquals(0, h.environment.textReads)
    }

    @Test
    fun theSameFieldWithoutTheFlagDoesShowTheOffer() {
        // Control for the two tests above: without IME_FLAG_NO_PERSONALIZED_LEARNING an otherwise
        // identical field still gets the offer, so their silence proves the flag and nothing else.
        val h = Harness()
        h.environment.editorInputTypeAllowsSuggestions = true
        h.environment.editorForbidsPersonalizedLearning = false

        h.controller.onKeyPressCommitted(Constants.CODE_SPACE, isWordSeparator = true)

        assertEquals(1, h.presenter.offerCount)
        assertEquals(1, h.flags.spendCount)
        assertEquals(1, h.environment.textReads)
    }

    @Test
    fun anIncognitoFieldDefersTheUnavailableMessageInsteadOfShowingIt() {
        // The second dialog goes through the same seven conditions, so it is covered too.
        val h = Harness()
        h.environment.suggestionsSettingEnabled = true
        h.makeFieldIncognito()

        h.controller.onDictionaryUnavailableAfterExplicitEnable()
        h.controller.onInputViewStarted()
        assertEquals(0, h.presenter.messageCount)

        // Deferred, not dropped: the next ordinary field still reports the failed enable.
        h.environment.editorForbidsPersonalizedLearning = false
        h.controller.onInputViewStarted()
        assertEquals(1, h.presenter.messageCount)
    }

    // --- The one-shot "could not be turned on" message ---------------------------------------------

    @Test
    fun unavailableMessageIsShownOnceAndDeferredWhileTheEnvironmentIsWrong() {
        val h = Harness()
        h.environment.suggestionsSettingEnabled = true
        h.environment.editorInputTypeAllowsSuggestions = false

        h.controller.onDictionaryUnavailableAfterExplicitEnable()
        assertEquals(0, h.presenter.messageCount)

        // Still wrong at the next boundary: deferred, not lost.
        h.controller.onInputViewStarted()
        assertEquals(0, h.presenter.messageCount)

        h.environment.editorInputTypeAllowsSuggestions = true
        h.controller.onInputViewStarted()
        assertEquals(1, h.presenter.messageCount)

        // At most one per process.
        h.controller.onDictionaryUnavailableAfterExplicitEnable()
        h.controller.onInputViewStarted()
        assertEquals(1, h.presenter.messageCount)
    }

    @Test
    fun turningTheSettingOffCancelsTheDeferredUnavailableMessage() {
        val h = Harness()
        h.environment.suggestionsSettingEnabled = true
        h.environment.inputViewShownWithWindowToken = false

        h.controller.onDictionaryUnavailableAfterExplicitEnable()
        h.controller.onSuggestionsSettingDisabled()
        h.environment.suggestionsSettingEnabled = false
        h.environment.inputViewShownWithWindowToken = true

        h.controller.onInputViewStarted()
        h.controller.onInputViewStarted()

        assertEquals(0, h.presenter.messageCount)
    }
}

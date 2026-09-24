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

package rkr.simplekeyboard.inputmethod.accessibility

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3 source-contract for the TalkBack language-switch announcement. Behavioural TalkBack
 * checks need a device (announceForAccessibility is a framework call), so this guards the
 * frozen shape of the code, in the style of [EmojiPanelAccessibilitySourceContractTest]:
 *
 * - the subtype-change notification carries a userInitiated flag end to end;
 * - the globe key and the subtype picker notify with userInitiated=true, the programmatic
 *   hint-locale switch at field start and settings-side removal notify with false;
 * - LatinIME announces only on userInitiated, in the language's own locale, from the same
 *   display-name source as the spacebar hint;
 * - the announcement rides the keyboard accessibility delegate and its isEnabled gate.
 */
class SubtypeSwitchAnnouncementSourceContractTest {

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun java(path: String) = File(sourceRoot(), "java/$path").readText()

    private val richImm by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/RichInputMethodManager.java")
    }
    private val latinIme by lazy {
        java("rkr/simplekeyboard/inputmethod/latin/LatinIME.java")
    }
    private val mainKeyboardView by lazy {
        java("rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java")
    }
    private val delegate by lazy {
        java("rkr/simplekeyboard/inputmethod/accessibility/KeyboardAccessibilityDelegate.kt")
    }

    // --- The notification carries the origin flag ---------------------------------------------

    @Test
    fun subtypeChangedListenerCarriesUserInitiatedFlag() {
        assertTrue(richImm.contains("void onCurrentSubtypeChanged(boolean userInitiated)"))
        assertTrue(richImm.contains("notifySubtypeChanged(final boolean userInitiated)"))
        assertTrue(richImm.contains("mSubtypeChangedListener.onCurrentSubtypeChanged(userInitiated)"))
    }

    @Test
    fun globeKeyPathNotifiesAsUserInitiated() {
        val cycle = richImm
            .substringAfter("public synchronized boolean switchToNextSubtype(")
            .substringBefore("public synchronized Subtype getCurrentSubtype()")
        assertTrue(cycle.contains("notifySubtypeChanged(true)"))
        // The wrap-around fallback after a failed switch to another IME is still the globe key.
        val outerSwitch = richImm
            .substringAfter("public boolean switchToNextInputMethod(")
            .substringBefore("public Subtype getCurrentSubtype()")
        assertTrue(outerSwitch.contains("mSubtypeList.notifySubtypeChanged(true)"))
    }

    @Test
    fun pickerSelectionIsUserInitiatedHintLocaleSwitchIsSilent() {
        // setCurrentSubtype(index, persist): persist==true is the explicit picker selection,
        // persist==false the temporary hint-locale switch at field start.
        val body = richImm
            .substringAfter("private void setCurrentSubtype(final int index, final boolean persist)")
            .substringBefore("public synchronized boolean switchToNextSubtype(")
        assertTrue(body.contains("notifySubtypeChanged(persist)"))
        // LatinIME's only programmatic switch is the hint-locale one (non-persisted).
        assertTrue(latinIme.contains("mRichImm.setCurrentSubtype(primaryHintLocale)"))
    }

    @Test
    fun settingsSideSubtypeRemovalIsSilent() {
        val body = richImm
            .substringAfter("public synchronized boolean removeSubtype")
            .substringBefore("public synchronized void resetSubtypeCycleOrder")
        assertTrue(body.contains("notifySubtypeChanged(false)"))
    }

    // --- The announcement itself ----------------------------------------------------------------

    @Test
    fun latinImeAnnouncesOnlyUserInitiatedSwitches() {
        val handler = latinIme
            .substringAfter("public void onCurrentSubtypeChanged(final boolean userInitiated)")
            .substringBefore("private void announceCurrentLanguageForAccessibility()")
        assertTrue(handler.contains("if (userInitiated)"))
        assertTrue(handler.contains("announceCurrentLanguageForAccessibility()"))
    }

    @Test
    fun announcementTextIsTheLanguageNameInItsOwnLocale() {
        // Same source the spacebar hint (MainKeyboardView) and the space key description
        // (KeyDescriptionMapper) use, so the announcement reads exactly what the spacebar shows.
        val body = latinIme.substringAfter("private void announceCurrentLanguageForAccessibility()")
        assertTrue(body.contains("LocaleResourceUtils.getLanguageDisplayNameInLocale("))
        assertTrue(body.contains("mRichImm.getCurrentSubtype().getLocale()"))
        assertTrue(body.contains("mainKeyboardView.announceLanguageForAccessibility("))
    }

    @Test
    fun announcementRidesTheKeyboardAccessibilityDelegateWithItsEnabledGate() {
        assertTrue(mainKeyboardView.contains("mAccessibilityDelegate.announceLanguage(languageName)"))
        val announce = delegate.substringAfter("fun announceLanguage(languageName: String)")
        assertTrue(announce.contains("if (!accessibilityManager.isEnabled) return"))
        assertTrue(announce.contains("keyboardView.announceForAccessibility(languageName)"))
    }

    @Test
    fun theNewLayoutGeometryReachesTheEngineBeforeTheSubtypeChangeIsAnnounced() {
        // 2026-09-24 audit, finding 6: onSubtypeChanged may immediately re-derive the band for a
        // warm engine, and that lookup must already see the NEW layout's neighbor table — not the
        // one of the layout the user just left.
        val handler = latinIme
            .substringAfter("public void onCurrentSubtypeChanged(final boolean userInitiated)")
            .substringBefore("private void announceCurrentLanguageForAccessibility()")
        assertTrue(
            "updateKeyNeighbors() must run before the controller hears about the switch",
            handler.indexOf("updateKeyNeighbors()") in
                0 until handler.indexOf("mSuggestionsController.onSubtypeChanged("),
        )
    }
}

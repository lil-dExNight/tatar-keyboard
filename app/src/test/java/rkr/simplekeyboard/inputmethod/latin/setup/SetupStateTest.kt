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

package rkr.simplekeyboard.inputmethod.latin.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Value-level checks of the onboarding state predicates. The lifecycle side
 * of the auto-return (poll while away, relaunch on enable) needs a device.
 */
class SetupStateTest {

    @Test
    fun enabledListMustContainTheExactPackage() {
        assertTrue(SetupState.isImeEnabled(
                listOf("com.other.ime", RELEASE_PACKAGE), RELEASE_PACKAGE))
        assertFalse(SetupState.isImeEnabled(emptyList(), RELEASE_PACKAGE))
        // A prefix is not a match: the release package must not pass on the
        // debug one's entry.
        assertFalse(SetupState.isImeEnabled(
                listOf("$RELEASE_PACKAGE.debug"), RELEASE_PACKAGE))
    }

    @Test
    fun defaultInputMethodMatchesOnlyThisPackageSlashClass() {
        val id = "$RELEASE_PACKAGE/rkr.simplekeyboard.inputmethod.latin.LatinIME"
        assertTrue(SetupState.isImeCurrent(id, RELEASE_PACKAGE))
        assertFalse(SetupState.isImeCurrent(null, RELEASE_PACKAGE))
        assertFalse(SetupState.isImeCurrent("com.other.ime/.MainIme", RELEASE_PACKAGE))
        // A bare package without the class separator is not a component id.
        assertFalse(SetupState.isImeCurrent(RELEASE_PACKAGE, RELEASE_PACKAGE))
    }

    @Test
    fun debugPackageSuffixDoesNotConfuseTheCurrentCheck() {
        val debugId = "$RELEASE_PACKAGE.debug/rkr.simplekeyboard.inputmethod.latin.LatinIME"
        assertTrue(SetupState.isImeCurrent(debugId, "$RELEASE_PACKAGE.debug"))
        assertFalse(SetupState.isImeCurrent(debugId, RELEASE_PACKAGE))
    }

    @Test
    fun setupIsCompleteOnlyWhenEnabledAndSelected() {
        assertTrue(SetupState.isSetupComplete(enabled = true, current = true))
        assertFalse(SetupState.isSetupComplete(enabled = true, current = false))
        assertFalse(SetupState.isSetupComplete(enabled = false, current = true))
        assertFalse(SetupState.isSetupComplete(enabled = false, current = false))
    }

    private companion object {
        const val RELEASE_PACKAGE = "org.tatarkeyboard.ime"
    }
}

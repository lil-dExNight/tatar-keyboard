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

/**
 * Value-level setup-state predicates, kept free of Android types so they run
 * in plain JVM tests. SetupActivity only adapts the live system reads (the
 * IMM enabled-IME list, Settings.Secure.DEFAULT_INPUT_METHOD) to these.
 */
object SetupState {

    /** The system's enabled-IME list contains an entry of this exact package. */
    fun isImeEnabled(enabledPackages: List<String>, packageName: String): Boolean =
            enabledPackages.contains(packageName)

    /**
     * DEFAULT_INPUT_METHOD ("package/class") names this package. Prefix
     * comparison keeps the check correct on debug builds, where the
     * applicationId gets a ".debug" suffix while the IME class name stays.
     */
    fun isImeCurrent(defaultInputMethod: String?, packageName: String): Boolean =
            defaultInputMethod != null && defaultInputMethod.startsWith("$packageName/")

    /** Setup is complete only when the IME is both enabled and selected. */
    fun isSetupComplete(enabled: Boolean, current: Boolean): Boolean = enabled && current

    /**
     * A launch onto a completed setup forwards straight to the settings: the
     * wizard's done block belongs to the session that finished the steps, a
     * later launcher tap wants the settings.
     */
    fun shouldForwardToSettings(setupComplete: Boolean, setupIncompleteSeen: Boolean): Boolean =
            setupComplete && !setupIncompleteSeen
}

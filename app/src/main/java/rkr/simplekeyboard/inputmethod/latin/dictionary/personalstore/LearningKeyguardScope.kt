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

import java.util.function.BooleanSupplier

/**
 * One live keyguard read per input event for the learning predicate. The event dispatch opens a
 * scope; the first check inside it reads live and later checks of the same dispatch reuse that
 * answer. Outside a scope nothing is kept, so every check reads live. The answer lives for one
 * UI-thread dispatch, so one event never mixes two keyguard answers.
 * Confined to the UI thread.
 */
class LearningKeyguardScope {
    private var depth = 0
    private var hasAnswer = false
    private var locked = false

    /** Opens a scope; scopes nest, and the outermost close drops the kept answer. */
    fun open() {
        depth++
    }

    /** Closes the innermost scope. A close without an open is ignored. */
    fun close() {
        if (depth == 0) return
        depth--
        if (depth == 0) {
            hasAnswer = false
            locked = false
        }
    }

    /** The keyguard state for one learning check: [readLive] runs unless this scope already read. */
    fun isLocked(readLive: BooleanSupplier): Boolean {
        if (depth == 0) return readLive.asBoolean
        if (!hasAnswer) {
            locked = readLive.asBoolean
            hasAnswer = true
        }
        return locked
    }
}

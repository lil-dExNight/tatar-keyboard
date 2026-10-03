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

package rkr.simplekeyboard.inputmethod.latin.dictionary.personal

/**
 * The write side of the persisted refused corrections: one undone statistical correction, as the
 * normalized (typed word → replacement) pair. Shaped like [WordCompletionSink]: the controller
 * announces the event, and the decision to persist lives in the store under its learning
 * predicate. A text-shortcut expansion's undo is not reported here — that pair is the user's own
 * setting, managed on its screen, and must not be silently deadened by a counter.
 */
fun interface RefusedCorrectionSink {
    fun onCorrectionRefused(typedWord: String, replacement: String)
}

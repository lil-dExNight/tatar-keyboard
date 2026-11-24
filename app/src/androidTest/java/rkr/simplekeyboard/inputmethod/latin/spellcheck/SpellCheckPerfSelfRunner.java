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

package rkr.simplekeyboard.inputmethod.latin.spellcheck;

import android.test.InstrumentationTestRunner;

/**
 * The legacy runner declared with the test package as its own target, so the spell-checker
 * performance client runs outside the keyboard process and can observe a cold service start.
 * Java, because the test package carries no Kotlin runtime of its own.
 */
public final class SpellCheckPerfSelfRunner extends InstrumentationTestRunner {
}

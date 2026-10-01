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

package rkr.simplekeyboard.inputmethod.latin;

import android.test.InstrumentationTestRunner;

/**
 * The legacy runner under a second name, declared in the test manifest with the test package as
 * its own target. Tests then run in the test package's process, so a notification is posted by
 * the test package (which holds POST_NOTIFICATIONS) instead of by the keyboard under test. Java,
 * because the test package carries no Kotlin runtime of its own.
 */
public final class QuickReplySelfRunner extends InstrumentationTestRunner {
}

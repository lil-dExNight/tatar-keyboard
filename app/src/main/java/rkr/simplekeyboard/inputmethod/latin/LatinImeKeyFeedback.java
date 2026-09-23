/*
 * Copyright (C) 2008 The Android Open Source Project
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

import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;

/**
 * The audio/haptic key feedback of {@link LatinIME} (T2 split, part 2 of 3): the per-press
 * feedback with its key-repeat thinning, and the tick of the cursor gestures. Static methods
 * taking the service, so the bodies moved here verbatim; the feedback itself is performed by
 * {@link AudioAndHapticFeedbackManager} exactly as before.
 */
final class LatinImeKeyFeedback {
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;

    private LatinImeKeyFeedback() {
        // Static methods only.
    }

    static void hapticAndAudioFeedback(final LatinIME ime, final int code, final int repeatCount) {
        final MainKeyboardView keyboardView = ime.mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            // No need to feedback while finger is dragging.
            return;
        }
        if (repeatCount > 0) {
            if (code == Constants.CODE_DELETE && !ime.mInputLogic.mConnection.canDeleteCharacters()) {
                // No need to feedback when repeat delete key will have no effect.
                return;
            }
            // TODO: Use event time that the last feedback has been generated instead of relying on
            // a repeat count to thin out feedback.
            if (repeatCount % PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT == 0) {
                return;
            }
        }
        final AudioAndHapticFeedbackManager feedbackManager = AudioAndHapticFeedbackManager.getInstance();
        if (repeatCount == 0) {
            // TODO: Reconsider how to perform haptic feedback when repeating key.
            feedbackManager.performHapticFeedback(keyboardView);
        }
        feedbackManager.performAudioFeedback(code);
    }

    static void hapticTickFeedback() {
        final AudioAndHapticFeedbackManager feedbackManager = AudioAndHapticFeedbackManager.getInstance();
        feedbackManager.performTickFeedback();
    }
}

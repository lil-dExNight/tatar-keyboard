/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2019 Raimondas Rimkus
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

package rkr.simplekeyboard.inputmethod.keyboard.internal;

import rkr.simplekeyboard.inputmethod.keyboard.Key;
import rkr.simplekeyboard.inputmethod.keyboard.MoreKeysPanel;
import rkr.simplekeyboard.inputmethod.keyboard.PointerTracker;

public interface DrawingProxy {
    /**
     * Called when a key is being pressed.
     * @param key the {@link Key} that is being pressed.
     * @param withPreview true if key popup preview should be displayed.
     */
    void onKeyPressed(Key key, boolean withPreview);

    /**
     * Called when a key is being released.
     * @param key the {@link Key} that is being released.
     * @param withAnimation when true, key popup preview should be dismissed with animation.
     */
    void onKeyReleased(Key key, boolean withAnimation);

    /**
     * Start showing more keys keyboard of a key that is being long pressed.
     * @param key the {@link Key} that is being long pressed and showing more keys keyboard.
     * @param tracker the {@link PointerTracker} that detects this long pressing.
     * @return {@link MoreKeysPanel} that is being shown. null if there is no need to show more keys
     *     keyboard.
     */
    MoreKeysPanel showMoreKeysKeyboard(Key key, PointerTracker tracker);

    /**
     * Start a while-typing-animation.
     * @param fadeInOrOut {@link #FADE_IN} starts while-typing-fade-in animation.
     * {@link #FADE_OUT} starts while-typing-fade-out animation.
     */
    void startWhileTypingAnimation(int fadeInOrOut);
    int FADE_IN = 0;
    int FADE_OUT = 1;

    /**
     * Called for every point of an armed glide (P7-5): the view appends it to the fading
     * trail it draws under the fingertip. Coordinates are view-local.
     * @param x the x-coordinate of the finger.
     * @param y the y-coordinate of the finger.
     * @param eventTime the event timestamp in milliseconds.
     */
    void onGlideTrailPoint(float x, float y, long eventTime);

    /**
     * Called when the glide ends or is cancelled (P7-5): the trail is cleared. Also called on
     * touches that never armed a glide — the view treats an empty trail as a no-op.
     */
    void onGlideTrailEnd();
}

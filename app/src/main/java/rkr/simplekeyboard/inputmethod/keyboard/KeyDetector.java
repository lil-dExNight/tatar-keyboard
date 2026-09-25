/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2020 wittmane
 * Copyright (C) 2020 Raimondas Rimkus
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

package rkr.simplekeyboard.inputmethod.keyboard;

import java.util.List;

/**
 * This class handles key detection.
 */
public class KeyDetector {
    private final int mKeyHysteresisDistanceSquared;
    private final int mKeyHysteresisDistanceForSlidingModifierSquared;
    private final int mSlidingModifierSlopSquared;

    private Keyboard mKeyboard;
    private int mCorrectionX;
    private int mCorrectionY;

    public KeyDetector() {
        this(0.0f /* keyHysteresisDistance */, 0.0f /* keyHysteresisDistanceForSlidingModifier */,
                0.0f /* slidingModifierSlop */);
    }

    /**
     * Key detection object constructor with key hysteresis distances.
     *
     * @param keyHysteresisDistance if the pointer movement distance is smaller than this, the
     * movement will not be handled as meaningful movement. The unit is pixel.
     * @param keyHysteresisDistanceForSlidingModifier the same parameter for sliding input that
     * starts from a modifier key such as shift and symbols key.
     * @param slidingModifierSlop how far a pointer that went down on a modifier key must travel
     * from its touch-down point before it may leave that key at all. The unit is pixel.
     */
    public KeyDetector(final float keyHysteresisDistance,
            final float keyHysteresisDistanceForSlidingModifier,
            final float slidingModifierSlop) {
        mKeyHysteresisDistanceSquared = (int)(keyHysteresisDistance * keyHysteresisDistance);
        mKeyHysteresisDistanceForSlidingModifierSquared = (int)(
                keyHysteresisDistanceForSlidingModifier * keyHysteresisDistanceForSlidingModifier);
        mSlidingModifierSlopSquared = (int)(slidingModifierSlop * slidingModifierSlop);
    }

    public void setKeyboard(final Keyboard keyboard, final float correctionX,
            final float correctionY) {
        if (keyboard == null) {
            throw new NullPointerException();
        }
        mCorrectionX = (int)correctionX;
        mCorrectionY = (int)correctionY;
        mKeyboard = keyboard;
    }

    public int getKeyHysteresisDistanceSquared(final boolean isSlidingFromModifier) {
        return isSlidingFromModifier
                ? mKeyHysteresisDistanceForSlidingModifierSquared : mKeyHysteresisDistanceSquared;
    }

    /**
     * Whether a pointer that went down on a modifier key has travelled far enough from its
     * touch-down point to count as deliberate sliding input rather than the tremor of a tap.
     *
     * <p>{@link #getKeyHysteresisDistanceSquared} is measured from the <em>key edge</em>, so a
     * press that lands near the edge of a modifier key leaves that key after a movement of
     * {@code keyHysteresisDistance} alone (5dp here, less than the platform's own 8dp touch slop),
     * which arms the momentary layout switch that springs back on release. This slop is measured
     * from the <em>touch-down point</em> instead, so it does not depend on where inside the key the
     * press landed. See {@code docs/SYMBOL-KEY-EDGE-FIX.md}.</p>
     *
     * @param downX x-coordinate of the touch-down point
     * @param downY y-coordinate of the touch-down point
     * @param x current x-coordinate of the pointer
     * @param y current y-coordinate of the pointer
     */
    public boolean isBeyondSlidingModifierSlop(final int downX, final int downY, final int x,
            final int y) {
        final int dx = x - downX;
        final int dy = y - downY;
        return dx * dx + dy * dy >= mSlidingModifierSlopSquared;
    }

    public int getTouchX(final int x) {
        return x + mCorrectionX;
    }

    // TODO: Remove vertical correction.
    public int getTouchY(final int y) {
        return y + mCorrectionY;
    }

    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    public boolean alwaysAllowsKeySelectionByDraggingFinger() {
        return false;
    }

    /**
     * Detect the key whose hitbox the touch point is in.
     *
     * @param x The x-coordinate of a touch point
     * @param y The y-coordinate of a touch point
     * @return the key that the touch point hits.
     */
    public Key detectHitKey(final int x, final int y) {
        if (mKeyboard == null) {
            return null;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);

        // Index loop, not for-each: this runs once per gesture sample, and the enhanced for
        // would allocate an Iterator per call. The list is an unmodifiable ArrayList view
        // (ProximityInfo), so indexed access is O(1).
        final List<Key> nearestKeys = mKeyboard.getNearestKeys(touchX, touchY);
        final int nearestCount = nearestKeys.size();
        for (int i = 0; i < nearestCount; i++) {
            final Key key = nearestKeys.get(i);
            if (key.isOnKey(touchX, touchY)) {
                return key;
            }
        }
        return null;
    }
}

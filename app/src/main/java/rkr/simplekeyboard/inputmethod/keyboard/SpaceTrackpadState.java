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

package rkr.simplekeyboard.inputmethod.keyboard;

/**
 * Touch state for horizontal spacebar cursor movement and two-finger selection. The first pointer
 * owns movement; only the first secondary pointer owns the selection anchor. Editor positions and
 * text stay outside this class.
 */
public final class SpaceTrackpadState {
    private static final int NO_POINTER = -1;
    private static final int IDLE = 0;
    private static final int CURSOR = 1;
    private static final int SELECTING = 2;

    private int mState = IDLE;
    private int mStartX;
    private int mSelectionPointerId = NO_POINTER;

    public static boolean canStart(final boolean hasKnownCursor, final boolean passwordField,
            final boolean spaceSwipeEnabled) {
        return hasKnownCursor && !passwordField && spaceSwipeEnabled;
    }

    public boolean onLongPressed(final int x, final boolean eligible) {
        if (mState != IDLE || !eligible) {
            return false;
        }
        mStartX = x;
        mState = CURSOR;
        return true;
    }

    /** Returns whole horizontal steps and retains the remainder for the next move. */
    public int onMove(final int x, final int pixelsPerStep) {
        if (mState == IDLE || pixelsPerStep <= 0) {
            return 0;
        }
        final int steps = (x - mStartX) / pixelsPerStep;
        mStartX += steps * pixelsPerStep;
        return steps;
    }

    /** Returns true when this pointer must be consumed by the trackpad gesture. */
    public boolean onSecondaryPointerDown(final int pointerId) {
        if (mState == IDLE) {
            return false;
        }
        if (mState == CURSOR) {
            mState = SELECTING;
            mSelectionPointerId = pointerId;
        }
        return true;
    }

    /** Returns true when the anchoring pointer leaves and cursor movement resumes. */
    public boolean onSecondaryPointerUp(final int pointerId) {
        if (mState != SELECTING || mSelectionPointerId != pointerId) {
            return false;
        }
        mSelectionPointerId = NO_POINTER;
        mState = CURSOR;
        return true;
    }

    public void finish() {
        mState = IDLE;
        mSelectionPointerId = NO_POINTER;
    }

    public boolean isActive() {
        return mState != IDLE;
    }

    public boolean isSelecting() {
        return mState == SELECTING;
    }
}

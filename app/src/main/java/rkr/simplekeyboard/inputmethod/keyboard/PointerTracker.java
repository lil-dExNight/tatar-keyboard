/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
 * Copyright (C) 2019 Emmanuel
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

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.Log;
import android.view.MotionEvent;

import java.util.ArrayList;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingProxy;
import rkr.simplekeyboard.inputmethod.keyboard.internal.PointerTrackerQueue;
import rkr.simplekeyboard.inputmethod.keyboard.internal.TimerProxy;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.CoordinateUtils;
import rkr.simplekeyboard.inputmethod.latin.glide.GlideGestureDecider;
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;

public final class PointerTracker implements PointerTrackerQueue.Element {
    private static final String TAG = PointerTracker.class.getSimpleName();
    private static final boolean DEBUG_EVENT = false;
    private static final boolean DEBUG_MOVE_EVENT = false;
    private static final boolean DEBUG_LISTENER = false;
    private static boolean DEBUG_MODE = DEBUG_EVENT;

    static final class PointerTrackerParams {
        public final boolean mKeySelectionByDraggingFinger;
        public final int mTouchNoiseThresholdTime;
        public final int mTouchNoiseThresholdDistance;
        public final int mKeyRepeatStartTimeout;
        public final int mKeyRepeatInterval;
        public final int mLongPressShiftLockTimeout;

        public PointerTrackerParams(final TypedArray mainKeyboardViewAttr) {
            mKeySelectionByDraggingFinger = mainKeyboardViewAttr.getBoolean(
                    R.styleable.MainKeyboardView_keySelectionByDraggingFinger, false);
            mTouchNoiseThresholdTime = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_touchNoiseThresholdTime, 0);
            mTouchNoiseThresholdDistance = mainKeyboardViewAttr.getDimensionPixelSize(
                    R.styleable.MainKeyboardView_touchNoiseThresholdDistance, 0);
            mKeyRepeatStartTimeout = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_keyRepeatStartTimeout, 0);
            mKeyRepeatInterval = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_keyRepeatInterval, 0);
            mLongPressShiftLockTimeout = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_longPressShiftLockTimeout, 0);
        }
    }

    // Parameters for pointer handling.
    private static PointerTrackerParams sParams;
    private static int sPointerStep = (int)(10.0 * Resources.getSystem().getDisplayMetrics().density);

    private static final ArrayList<PointerTracker> sTrackers = new ArrayList<>();
    private static final PointerTrackerQueue sPointerTrackerQueue = new PointerTrackerQueue();

    public final int mPointerId;

    private static DrawingProxy sDrawingProxy;
    private static TimerProxy sTimerProxy;
    private static KeyboardActionListener sListener = KeyboardActionListener.EMPTY_LISTENER;

    // The {@link KeyDetector} is set whenever the down event is processed. Also this is updated
    // when new {@link Keyboard} is set by {@link #setKeyDetector(KeyDetector)}.
    private KeyDetector mKeyDetector = new KeyDetector();
    private Keyboard mKeyboard;

    // The position and time at which first down event occurred.
    private int[] mDownCoordinates = CoordinateUtils.newInstance();

    // The current key where this pointer is.
    private Key mCurrentKey = null;
    // The position where the current key was recognized for the first time.
    private int mKeyX;
    private int mKeyY;

    // Last pointer position.
    private int mLastX;
    private int mLastY;
    private int mStartX;
    //private int mStartY;
    private long mStartTime;
    private boolean mCursorMoved = false;

    // true if keyboard layout has been changed.
    private boolean mKeyboardLayoutHasBeenChanged;

    // true if this pointer is no longer triggering any action because it has been canceled.
    private boolean mIsTrackingForActionDisabled;

    // the more keys panel currently being shown. equals null if no panel is active.
    private MoreKeysPanel mMoreKeysPanel;

    private static final int MULTIPLIER_FOR_LONG_PRESS_TIMEOUT_IN_SLIDING_INPUT = 3;
    // true if this pointer is in the dragging finger mode.
    boolean mIsInDraggingFinger;
    // true if this pointer is sliding from a modifier key and in the sliding key input mode,
    // so that further modifier keys should be ignored.
    boolean mIsInSlidingKeyInput;
    // if not a NOT_A_CODE, the key of this code is repeating
    private int mCurrentRepeatingKeyCode = Constants.NOT_A_CODE;

    // true if dragging finger is allowed.
    private boolean mIsAllowedDraggingFinger;

    // true if this pointer went down on a modifier key (shift or the alpha/symbol switch key).
    // Such a pointer may not leave that key until it has travelled the sliding-modifier slop from
    // its touch-down point; see {@link #isMajorEnoughMoveToBeOnNewKey}.
    private boolean mIsDownOnModifierKey;

    // P7-2 glide (docs/GLIDE-PLAN.md): the per-pointer decision machine (rebuilt when the
    // keyboard changes, since its distance threshold is the keyboard's key width) and the path
    // buffer the gesture records into. The buffer is allocated once per tracker; recording
    // happens only between an eligible letter-key down and its up/cancel. Nothing here runs when
    // the glide preference is off: the decider sits in REJECTED and every branch below falls
    // through to the legacy code, byte-identical.
    // The placeholder never arms (an unreachable distance threshold); the real decider is built
    // by setKeyDetectorInner once the keyboard — and with it the key width — is known.
    private GlideGestureDecider mGlideDecider =
            new GlideGestureDecider(Float.MAX_VALUE, 0f, GlideGestureDecider.DEFAULT_MAX_DETECT_TIME_MS);
    private final GlidePath mGlidePath = new GlidePath(GlidePath.MAX_POINTS);

    // TODO: Add PointerTrackerFactory singleton and move some class static methods into it.
    public static void init(final TypedArray mainKeyboardViewAttr, final TimerProxy timerProxy,
            final DrawingProxy drawingProxy) {
        sParams = new PointerTrackerParams(mainKeyboardViewAttr);
        sTimerProxy = timerProxy;
        sDrawingProxy = drawingProxy;
    }

    public static PointerTracker getPointerTracker(final int id) {
        final ArrayList<PointerTracker> trackers = sTrackers;

        // Create pointer trackers until we can get 'id+1'-th tracker, if needed.
        for (int i = trackers.size(); i <= id; i++) {
            final PointerTracker tracker = new PointerTracker(i);
            trackers.add(tracker);
        }

        return trackers.get(id);
    }

    public static boolean isAnyInDraggingFinger() {
        return sPointerTrackerQueue.isAnyInDraggingFinger();
    }

    public static boolean isAnyInCursorMove() {
        return sPointerTrackerQueue.isAnyInCursorMove();
    }

    public static void cancelAllPointerTrackers() {
        sPointerTrackerQueue.cancelAllPointerTrackers();
    }

    public static void setKeyboardActionListener(final KeyboardActionListener listener) {
        sListener = listener;
    }

    public static void setKeyDetector(final KeyDetector keyDetector) {
        final Keyboard keyboard = keyDetector.getKeyboard();
        if (keyboard == null) {
            return;
        }
        final int trackersSize = sTrackers.size();
        for (int i = 0; i < trackersSize; ++i) {
            final PointerTracker tracker = sTrackers.get(i);
            tracker.setKeyDetectorInner(keyDetector);
        }
    }

    public static void setReleasedKeyGraphicsToAllKeys() {
        final int trackersSize = sTrackers.size();
        for (int i = 0; i < trackersSize; ++i) {
            final PointerTracker tracker = sTrackers.get(i);
            tracker.setReleasedKeyGraphics(tracker.getKey(), true /* withAnimation */);
        }
    }

    public static void dismissAllMoreKeysPanels() {
        final int trackersSize = sTrackers.size();
        for (int i = 0; i < trackersSize; ++i) {
            final PointerTracker tracker = sTrackers.get(i);
            tracker.dismissMoreKeysPanel();
        }
    }

    private PointerTracker(final int id) {
        mPointerId = id;
    }

    // Returns true if keyboard has been changed by this callback.
    private boolean callListenerOnPressAndCheckKeyboardLayoutChange(final Key key,
            final int repeatCount) {
        // While gesture input is going on, this method should be a no-operation. But when gesture
        // input has been canceled, <code>sInGesture</code> and <code>mIsDetectingGesture</code>
        // are set to false. To keep this method is a no-operation,
        // <code>mIsTrackingForActionDisabled</code> should also be taken account of.
        final boolean ignoreModifierKey = mIsInDraggingFinger && key.isModifier();
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format("[%d] onPress    : %s%s%s", mPointerId,
                    (key == null ? "none" : Constants.printableCode(key.getCode())),
                    ignoreModifierKey ? " ignoreModifier" : "",
                    repeatCount > 0 ? " repeatCount=" + repeatCount : ""));
        }
        if (ignoreModifierKey) {
            return false;
        }
        sListener.onPressKey(key.getCode(), repeatCount, getActivePointerTrackerCount() == 1);
        final boolean keyboardLayoutHasBeenChanged = mKeyboardLayoutHasBeenChanged;
        mKeyboardLayoutHasBeenChanged = false;
        sTimerProxy.startTypingStateTimer(key);
        return keyboardLayoutHasBeenChanged;
    }

    // Note that we need primaryCode argument because the keyboard may in shifted state and the
    // primaryCode is different from {@link Key#mKeyCode}.
    private void callListenerOnCodeInput(final Key key, final int primaryCode, final int x,
            final int y, final boolean isKeyRepeat) {
        final boolean ignoreModifierKey = mIsInDraggingFinger && key.isModifier();
        final boolean altersCode = key.altCodeWhileTyping() && sTimerProxy.isTypingState();
        final int code = altersCode ? key.getAltCode() : primaryCode;
        if (DEBUG_LISTENER) {
            final String output = code == Constants.CODE_OUTPUT_TEXT
                    ? key.getOutputText() : Constants.printableCode(code);
            Log.d(TAG, String.format("[%d] onCodeInput: %4d %4d %s%s%s", mPointerId, x, y,
                    output, ignoreModifierKey ? " ignoreModifier" : "",
                    altersCode ? " altersCode" : ""));
        }
        if (ignoreModifierKey) {
            return;
        }

        if (code == Constants.CODE_OUTPUT_TEXT) {
            sListener.onTextInput(key.getOutputText());
        } else if (code != Constants.CODE_UNSPECIFIED) {
            sListener.onCodeInput(code,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isKeyRepeat);
        }
    }

    // Note that we need primaryCode argument because the keyboard may be in shifted state and the
    // primaryCode is different from {@link Key#mKeyCode}.
    private void callListenerOnRelease(final Key key, final int primaryCode,
            final boolean withSliding) {
        // See the comment at {@link #callListenerOnPressAndCheckKeyboardLayoutChange(Key}}.
        final boolean ignoreModifierKey = mIsInDraggingFinger && key.isModifier();
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format("[%d] onRelease  : %s%s%s", mPointerId,
                    Constants.printableCode(primaryCode),
                    withSliding ? " sliding" : "", ignoreModifierKey ? " ignoreModifier" : ""));
        }
        if (ignoreModifierKey) {
            return;
        }
        sListener.onReleaseKey(primaryCode, withSliding);
    }

    private void callListenerOnFinishSlidingInput() {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format("[%d] onFinishSlidingInput", mPointerId));
        }
        sListener.onFinishSlidingInput();
    }

    private void setKeyDetectorInner(final KeyDetector keyDetector) {
        final Keyboard keyboard = keyDetector.getKeyboard();
        if (keyboard == null) {
            return;
        }
        if (keyDetector == mKeyDetector && keyboard == mKeyboard) {
            return;
        }
        mKeyDetector = keyDetector;
        mKeyboard = keyboard;
        // The glide decider's distance threshold is the keyboard's key width; the velocity
        // threshold is the reference detector's 0.10 dp/ms, converted to pixels here.
        mGlideDecider = new GlideGestureDecider(
                keyboard.mMostCommonKeyWidth,
                GlideGestureDecider.VELOCITY_THRESHOLD_DP_PER_MS
                        * Resources.getSystem().getDisplayMetrics().density,
                GlideGestureDecider.DEFAULT_MAX_DETECT_TIME_MS);
        // Mark that keyboard layout has been changed.
        mKeyboardLayoutHasBeenChanged = true;
        // Keep {@link #mCurrentKey} that comes from previous keyboard. The key preview of
        // {@link #mCurrentKey} will be dismissed by {@setReleasedKeyGraphics(Key)} via
        // {@link onMoveEventInternal(int,int,long)} or {@link #onUpEventInternal(int,int,long)}.
    }

    @Override
    public boolean isInDraggingFinger() {
        return mIsInDraggingFinger;
    }

    @Override
    public boolean isInCursorMove() {
        return mCursorMoved;
    }

    public Key getKey() {
        return mCurrentKey;
    }

    @Override
    public boolean isModifier() {
        return mCurrentKey != null && mCurrentKey.isModifier();
    }

    public Key getKeyOn(final int x, final int y) {
        return mKeyDetector.detectHitKey(x, y);
    }

    private void setReleasedKeyGraphics(final Key key, final boolean withAnimation) {
        if (key == null) {
            return;
        }

        sDrawingProxy.onKeyReleased(key, withAnimation);

        if (key.isShift()) {
            for (final Key shiftKey : mKeyboard.mShiftKeys) {
                if (shiftKey != key) {
                    sDrawingProxy.onKeyReleased(shiftKey, false /* withAnimation */);
                }
            }
        }

        if (key.altCodeWhileTyping()) {
            final int altCode = key.getAltCode();
            final Key altKey = mKeyboard.getKey(altCode);
            if (altKey != null) {
                sDrawingProxy.onKeyReleased(altKey, false /* withAnimation */);
            }
            for (final Key k : mKeyboard.mAltCodeKeysWhileTyping) {
                if (k != key && k.getAltCode() == altCode) {
                    sDrawingProxy.onKeyReleased(k, false /* withAnimation */);
                }
            }
        }
    }

    private void setPressedKeyGraphics(final Key key) {
        if (key == null) {
            return;
        }

        // Even if the key is disabled, it should respond if it is in the altCodeWhileTyping state.
        final boolean altersCode = key.altCodeWhileTyping() && sTimerProxy.isTypingState();

        sDrawingProxy.onKeyPressed(key, true);

        if (key.isShift()) {
            for (final Key shiftKey : mKeyboard.mShiftKeys) {
                if (shiftKey != key) {
                    sDrawingProxy.onKeyPressed(shiftKey, false /* withPreview */);
                }
            }
        }

        if (altersCode) {
            final int altCode = key.getAltCode();
            final Key altKey = mKeyboard.getKey(altCode);
            if (altKey != null) {
                sDrawingProxy.onKeyPressed(altKey, false /* withPreview */);
            }
            for (final Key k : mKeyboard.mAltCodeKeysWhileTyping) {
                if (k != key && k.getAltCode() == altCode) {
                    sDrawingProxy.onKeyPressed(k, false /* withPreview */);
                }
            }
        }
    }

    public void getLastCoordinates(final int[] outCoords) {
        CoordinateUtils.set(outCoords, mLastX, mLastY);
    }

    private Key onDownKey(final int x, final int y) {
        CoordinateUtils.set(mDownCoordinates, x, y);
        return onMoveToNewKey(onMoveKeyInternal(x, y), x, y);
    }

    private static int getDistance(final int x1, final int y1, final int x2, final int y2) {
        return (int)Math.hypot(x1 - x2, y1 - y2);
    }

    private Key onMoveKeyInternal(final int x, final int y) {
        mLastX = x;
        mLastY = y;
        return mKeyDetector.detectHitKey(x, y);
    }

    private Key onMoveKey(final int x, final int y) {
        return onMoveKeyInternal(x, y);
    }

    private Key onMoveToNewKey(final Key newKey, final int x, final int y) {
        mCurrentKey = newKey;
        mKeyX = x;
        mKeyY = y;
        return newKey;
    }

    /* package */ static int getActivePointerTrackerCount() {
        return sPointerTrackerQueue.size();
    }

    public void processMotionEvent(final MotionEvent me, final KeyDetector keyDetector) {
        final int action = me.getActionMasked();
        final long eventTime = me.getEventTime();
        if (action == MotionEvent.ACTION_MOVE) {
            // When this pointer is the only active pointer and is showing a more keys panel,
            // we should ignore other pointers' motion event.
            final boolean shouldIgnoreOtherPointers =
                    isShowingMoreKeysPanel() && getActivePointerTrackerCount() == 1;
            final int pointerCount = me.getPointerCount();
            for (int index = 0; index < pointerCount; index++) {
                final int id = me.getPointerId(index);
                if (shouldIgnoreOtherPointers && id != mPointerId) {
                    continue;
                }
                final int x = (int)me.getX(index);
                final int y = (int)me.getY(index);
                final PointerTracker tracker = getPointerTracker(id);
                // P7-2: a tracked/armed glide consumes the historical batch too — the stock
                // flow drops it, which would starve the path of half its samples at typical
                // glide speeds. Never feed history to a more-keys panel.
                if (tracker.isGlideGestureActive() && !tracker.isShowingMoreKeysPanel()) {
                    final int historySize = me.getHistorySize();
                    for (int h = 0; h < historySize; h++) {
                        tracker.onMoveEvent(
                                (int)me.getHistoricalX(index, h), (int)me.getHistoricalY(index, h),
                                me.getHistoricalEventTime(h));
                    }
                }
                tracker.onMoveEvent(x, y, eventTime);
            }
            return;
        }
        final int index = me.getActionIndex();
        final int x = (int)me.getX(index);
        final int y = (int)me.getY(index);
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
            onDownEvent(x, y, eventTime, keyDetector);
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
            onUpEvent(x, y, eventTime);
            break;
        case MotionEvent.ACTION_CANCEL:
            onCancelEvent(x, y, eventTime);
            break;
        }
    }

    private void onDownEvent(final int x, final int y, final long eventTime,
            final KeyDetector keyDetector) {
        setKeyDetectorInner(keyDetector);
        if (DEBUG_EVENT) {
            printTouchEvent("onDownEvent:", x, y, eventTime);
        }
        // Naive up-to-down noise filter.
        final long deltaT = eventTime;
        if (deltaT < sParams.mTouchNoiseThresholdTime) {
            final int distance = getDistance(x, y, mLastX, mLastY);
            if (distance < sParams.mTouchNoiseThresholdDistance) {
                if (DEBUG_MODE)
                    Log.w(TAG, String.format("[%d] onDownEvent:"
                            + " ignore potential noise: time=%d distance=%d",
                            mPointerId, deltaT, distance));
                cancelTrackingForAction();
                return;
            }
        }

        final Key key = getKeyOn(x, y);
        if (key != null && key.isModifier()) {
            // Before processing a down event of modifier key, all pointers already being
            // tracked should be released.
            sPointerTrackerQueue.releaseAllPointers(eventTime);
        }
        // P7-2 multi-touch rule: a second finger down cancels any armed glide (fail-closed —
        // it will never be committed). A still-undecided (TRACKING) gesture is left alone:
        // two-finger chording is today's behavior and must keep working.
        cancelArmedGlideTrackersExcept(mPointerId);
        sPointerTrackerQueue.add(this);
        onDownEventInternal(x, y, eventTime);
    }

    /** P7-2: cancels the armed glide of every tracker but [pointerId] (fail-closed). */
    private static void cancelArmedGlideTrackersExcept(final int pointerId) {
        final int trackersSize = sTrackers.size();
        for (int i = 0; i < trackersSize; ++i) {
            final PointerTracker tracker = sTrackers.get(i);
            if (tracker.mPointerId != pointerId && tracker.mGlideDecider.isArmed()) {
                tracker.cancelArmedGlide();
            }
        }
    }

    private void cancelArmedGlide() {
        mGlideDecider.cancelGlide();
        mGlidePath.clear();
        setReleasedKeyGraphics(mCurrentKey, true /* withAnimation */);
        cancelTrackingForAction();
    }

    /* package */ boolean isShowingMoreKeysPanel() {
        return (mMoreKeysPanel != null);
    }

    private void dismissMoreKeysPanel() {
        if (isShowingMoreKeysPanel()) {
            mMoreKeysPanel.dismissMoreKeysPanel();
            mMoreKeysPanel = null;
        }
    }

    private void onDownEventInternal(final int x, final int y, final long eventTime) {
        Key key = onDownKey(x, y);
        // Key selection by dragging finger is allowed when 1) key selection by dragging finger is
        // enabled by configuration, 2) this pointer starts dragging from modifier key, or 3) this
        // pointer's KeyDetector always allows key selection by dragging finger, such as
        // {@link MoreKeysKeyboard}.
        mIsDownOnModifierKey = key != null && key.isModifier();
        mIsAllowedDraggingFinger = sParams.mKeySelectionByDraggingFinger
                || mIsDownOnModifierKey
                || mKeyDetector.alwaysAllowsKeySelectionByDraggingFinger();
        mKeyboardLayoutHasBeenChanged = false;
        mIsTrackingForActionDisabled = false;
        resetKeySelectionByDraggingFinger();
        if (key != null) {
            // This onPress call may have changed keyboard layout. Those cases are detected at
            // {@link #setKeyboard}. In those cases, we should update key according to the new
            // keyboard layout.
            final Keyboard keyboardOnPress = mKeyboard;
            if (callListenerOnPressAndCheckKeyboardLayoutChange(key, 0 /* repeatCount */)) {
                // The keyboard view is bottom aligned, so a keyboard of a different height moves
                // the origin of the touch coordinates. The Tatar alphabet keyboard carries an
                // extra row for ә ө ү җ ң һ and is one row taller than the symbols keyboard
                // (728px against 667px on a 440dpi phone), so the instant ?123 is pressed every
                // touch coordinate that follows is shifted by 61px. Re-detect the key -- and
                // record the touch-down point -- in the new keyboard's space, otherwise the move
                // events that follow are measured against a point a whole row away. Upstream
                // AOSP never meets this: there both keyboards have four rows.
                final int verticalShift = keyboardOnPress == null || mKeyboard == null ? 0
                        : mKeyboard.mOccupiedHeight - keyboardOnPress.mOccupiedHeight;
                key = onDownKey(x, y + verticalShift);
            }

            startRepeatKey(key);
            startLongPressTimer(key);
            setPressedKeyGraphics(key);
            mStartX = x;
            //mStartY = y;
            mStartTime = System.currentTimeMillis();
        }
        // P7-2: start the glide decision for this touch. Eligible means the preference is on
        // and the down key is a letter key (space/delete/shift/enter and the digit row never
        // start a glide — their swipe/long-press behaviors keep priority). The down point opens
        // the path. With the preference off the decider sits in REJECTED and every glide branch
        // in this class is dead — the touch path is byte-identical to the pre-glide code.
        final boolean glideEligible = key != null
                && Settings.getInstance().getCurrent().mGlideTypingEnabled
                && Character.isLetter(key.getCode());
        mGlideDecider.onDown(x, y, eventTime, glideEligible);
        mGlidePath.clear();
        if (glideEligible) {
            // The path buffer stores float timestamps (GlidePath.ts): gesture DELTAS are what the
            // decoder reads, and those are milliseconds apart, so the float's 24-bit mantissa is
            // exact where it matters. The cast is explicit — the implicit long->float narrowing
            // here is what error-prone's LongFloatConversion flags (2026-09-24 audit, F12).
            mGlidePath.addPoint(x, y, (float) eventTime);
        }
    }

    /** The glide decider is live for this pointer (undecided or armed) — P7-2. */
    /* package */ boolean isGlideGestureActive() {
        return mGlideDecider.isTracking() || mGlideDecider.isArmed();
    }

    /**
     * The glide takes over the pointer (P7-2): pending long-press/repeat timers are cancelled
     * and the preview of the key the legacy drag logic last entered is dismissed. From here on,
     * MOVE events feed the path only.
     */
    private void armGlide() {
        sTimerProxy.cancelKeyTimersOf(this);
        setReleasedKeyGraphics(mCurrentKey, true /* withAnimation */);
    }

    private void startKeySelectionByDraggingFinger(final Key key) {
        if (!mIsInDraggingFinger) {
            mIsInSlidingKeyInput = key.isModifier();
        }
        mIsInDraggingFinger = true;
    }

    private void resetKeySelectionByDraggingFinger() {
        mIsInDraggingFinger = false;
        mIsInSlidingKeyInput = false;
    }

    private void onMoveEvent(final int x, final int y, final long eventTime) {
        if (DEBUG_MOVE_EVENT) {
            printTouchEvent("onMoveEvent:", x, y, eventTime);
        }
        if (mIsTrackingForActionDisabled) {
            return;
        }

        if (isShowingMoreKeysPanel()) {
            final int translatedX = mMoreKeysPanel.translateX(x);
            final int translatedY = mMoreKeysPanel.translateY(y);
            mMoreKeysPanel.onMoveEvent(translatedX, translatedY, mPointerId);
            onMoveKey(x, y);
            return;
        }
        onMoveEventInternal(x, y, eventTime);
    }

    private void processDraggingFingerInToNewKey(final Key newKey, final int x, final int y) {
        // This onPress call may have changed keyboard layout. Those cases are detected
        // at {@link #setKeyboard}. In those cases, we should update key according
        // to the new keyboard layout.
        Key key = newKey;
        if (callListenerOnPressAndCheckKeyboardLayoutChange(key, 0 /* repeatCount */)) {
            key = onMoveKey(x, y);
        }
        onMoveToNewKey(key, x, y);
        if (mIsTrackingForActionDisabled) {
            return;
        }
        startLongPressTimer(key);
        setPressedKeyGraphics(key);
    }

    private void processDraggingFingerOutFromOldKey(final Key oldKey) {
        setReleasedKeyGraphics(oldKey, true /* withAnimation */);
        callListenerOnRelease(oldKey, oldKey.getCode(), true /* withSliding */);
        startKeySelectionByDraggingFinger(oldKey);
        sTimerProxy.cancelKeyTimersOf(this);
    }

    private void dragFingerFromOldKeyToNewKey(final Key key, final int x, final int y,
            final long eventTime, final Key oldKey) {
        // The pointer has been slid in to the new key from the previous key, we must call
        // onRelease() first to notify that the previous key has been released, then call
        // onPress() to notify that the new key is being pressed.
        processDraggingFingerOutFromOldKey(oldKey);
        startRepeatKey(key);
        if (mIsAllowedDraggingFinger) {
            processDraggingFingerInToNewKey(key, x, y);
        }
        // HACK: If there are currently multiple touches, register the key even if the finger
        // slides off the key. This defends against noise from some touch panels when there are
        // close multiple touches.
        // Caveat: When in chording input mode with a modifier key, we don't use this hack.
        else if (getActivePointerTrackerCount() > 1
                && !sPointerTrackerQueue.hasModifierKeyOlderThan(this)) {
            if (DEBUG_MODE) {
                Log.w(TAG, String.format("[%d] onMoveEvent:"
                        + " detected sliding finger while multi touching", mPointerId));
            }
            onUpEvent(x, y, eventTime);
            cancelTrackingForAction();
            setReleasedKeyGraphics(oldKey, true /* withAnimation */);
        } else {
            cancelTrackingForAction();
            setReleasedKeyGraphics(oldKey, true /* withAnimation */);
        }
    }

    private void dragFingerOutFromOldKey(final Key oldKey, final int x, final int y) {
        // The pointer has been slid out from the previous key, we must call onRelease() to
        // notify that the previous key has been released.
        processDraggingFingerOutFromOldKey(oldKey);
        if (mIsAllowedDraggingFinger) {
            onMoveToNewKey(null, x, y);
        } else {
            cancelTrackingForAction();
        }
    }

    private void onMoveEventInternal(final int x, final int y, final long eventTime) {
        final Key oldKey = mCurrentKey;

        // P7-2 glide branches, ahead of every legacy MOVE branch on purpose: an armed glide
        // feeds the path only (the space/delete swipe branches must not hijack it mid-path),
        // and an undecided gesture feeds the decider first — a gesture that arms on this very
        // point is a glide, not a cursor swipe. While undecided the legacy behavior below
        // proceeds unchanged (today's sliding key input keeps its previews and haptics until
        // the decider commits to a glide); a cursor swipe that already started (mCursorMoved)
        // can no longer become a glide.
        if (mGlideDecider.isArmed()) {
            mGlidePath.addPoint(x, y, (float) eventTime); // explicit narrowing — see onDownEvent
            return;
        }
        if (mGlideDecider.isTracking() && !mCursorMoved) {
            mGlidePath.addPoint(x, y, (float) eventTime);
            if (mGlideDecider.onMove(x, y, eventTime) == GlideGestureDecider.State.ARMED) {
                armGlide();
                return;
            }
        }

        if (oldKey != null && oldKey.getCode() == Constants.CODE_SPACE && Settings.getInstance().getCurrent().mSpaceSwipeEnabled) {
            //Pointer slider
            int steps = (x - mStartX) / sPointerStep;
            final int swipeIgnoreTime = Settings.getInstance().getCurrent().mKeyLongpressTimeout / MULTIPLIER_FOR_LONG_PRESS_TIMEOUT_IN_SLIDING_INPUT;
            if (steps != 0 && mStartTime + swipeIgnoreTime < System.currentTimeMillis()) {
                mCursorMoved = true;
                mStartX += steps * sPointerStep;
                sListener.onMoveCursorPointer(steps);
            }
            return;
        }

        if (oldKey != null && oldKey.getCode() == Constants.CODE_DELETE && Settings.getInstance().getCurrent().mDeleteSwipeEnabled) {
            //Delete slider
            int steps = (x - mStartX) / sPointerStep;
            if (steps != 0) {
                sTimerProxy.cancelKeyTimersOf(this);
                mCursorMoved = true;
                mStartX += steps * sPointerStep;
                sListener.onMoveDeletePointer(steps);
            }
            return;
        }

        final Key newKey = onMoveKey(x, y);
        if (newKey != null) {
            if (oldKey != null && isMajorEnoughMoveToBeOnNewKey(x, y, newKey)) {
                dragFingerFromOldKeyToNewKey(newKey, x, y, eventTime, oldKey);
            } else if (oldKey == null) {
                // The pointer has been slid in to the new key, but the finger was not on any keys.
                // In this case, we must call onPress() to notify that the new key is being pressed.
                processDraggingFingerInToNewKey(newKey, x, y);
            }
        } else { // newKey == null
            if (oldKey != null && isMajorEnoughMoveToBeOnNewKey(x, y, newKey)) {
                dragFingerOutFromOldKey(oldKey, x, y);
            }
        }
    }

    private void onUpEvent(final int x, final int y, final long eventTime) {
        if (DEBUG_EVENT) {
            printTouchEvent("onUpEvent  :", x, y, eventTime);
        }

        if (mCurrentKey != null && mCurrentKey.isModifier()) {
            // Before processing an up event of modifier key, all pointers already being
            // tracked should be released.
            sPointerTrackerQueue.releaseAllPointersExcept(this, eventTime);
        } else {
            sPointerTrackerQueue.releaseAllPointersOlderThan(this, eventTime);
        }
        onUpEventInternal(x, y);
        sPointerTrackerQueue.remove(this);
    }

    // Let this pointer tracker know that one of newer-than-this pointer trackers got an up event.
    // This pointer tracker needs to keep the key top graphics "pressed", but needs to get a
    // "virtual" up event.
    @Override
    public void onPhantomUpEvent(final long eventTime) {
        if (DEBUG_EVENT) {
            printTouchEvent("onPhntEvent:", mLastX, mLastY, eventTime);
        }
        // P7-2: a phantom up cancels a glide instead of delivering it (fail-closed).
        mGlideDecider.cancelGlide();
        onUpEventInternal(mLastX, mLastY);
        cancelTrackingForAction();
    }

    private void onUpEventInternal(final int x, final int y) {
        sTimerProxy.cancelKeyTimersOf(this);
        // P7-2: snapshot the glide state before the legacy reset below (the decider itself is
        // reset here so no path state survives into the next touch).
        final boolean armedGlide = mGlideDecider.isArmed();
        mGlideDecider.onUpOrCancel();
        final boolean isInDraggingFinger = mIsInDraggingFinger;
        final boolean isInSlidingKeyInput = mIsInSlidingKeyInput;
        resetKeySelectionByDraggingFinger();
        final Key currentKey = mCurrentKey;
        mCurrentKey = null;
        final int currentRepeatingKeyCode = mCurrentRepeatingKeyCode;
        mCurrentRepeatingKeyCode = Constants.NOT_A_CODE;
        // Release the last pressed key.
        setReleasedKeyGraphics(currentKey, true /* withAnimation */);

        // P7-2: an armed glide delivers its path and never commits a key. A cancelled glide
        // (multi-touch, phantom up, cancelTrackingForAction) delivers nothing — fail-closed.
        if (armedGlide) {
            if (!mIsTrackingForActionDisabled) {
                sListener.onGlideInput(mGlidePath);
            }
            mGlidePath.clear();
            return;
        }

        if (mCursorMoved && currentKey.getCode() == Constants.CODE_DELETE) {
            sListener.onUpWithDeletePointerActive();
        }
        if (mCursorMoved && currentKey.getCode() == Constants.CODE_SPACE) {
            sListener.onUpWithSpacePointerActive();
        }

        if (isShowingMoreKeysPanel()) {
            if (mMoreKeysPanel.shouldKeepPanelOnUpEvent()) {
                // Touch exploration (TalkBack) is active: leave the panel on
                // screen so it can be explored through its accessibility
                // nodes, and just detach it from this tracker. The panel is
                // dismissed later by its accessibility delegate (on commit)
                // or by a new touch on the main keyboard.
                mMoreKeysPanel = null;
                return;
            }
            if (!mIsTrackingForActionDisabled) {
                final int translatedX = mMoreKeysPanel.translateX(x);
                final int translatedY = mMoreKeysPanel.translateY(y);
                mMoreKeysPanel.onUpEvent(translatedX, translatedY, mPointerId);
            }
            dismissMoreKeysPanel();
            return;
        }

        if (mCursorMoved) {
            mCursorMoved = false;
            return;
        }
        if (mIsTrackingForActionDisabled) {
            return;
        }
        if (currentKey != null && currentKey.isRepeatable()
                && (currentKey.getCode() == currentRepeatingKeyCode) && !isInDraggingFinger) {
            return;
        }
        detectAndSendKey(currentKey, mKeyX, mKeyY);
        if (isInSlidingKeyInput) {
            callListenerOnFinishSlidingInput();
        }
    }

    @Override
    public void cancelTrackingForAction() {
        if (isShowingMoreKeysPanel()) {
            return;
        }
        mIsTrackingForActionDisabled = true;
    }

    public void onLongPressed() {
        sTimerProxy.cancelLongPressTimersOf(this);
        // P7-2: a long-press timer that was already in flight when the glide armed is dead.
        if (mGlideDecider.isArmed()) {
            return;
        }
        if (isShowingMoreKeysPanel()) {
            return;
        }
        if (mCursorMoved) {
            return;
        }
        final Key key = getKey();
        if (key == null) {
            return;
        }
        if (key.hasNoPanelAutoMoreKey()) {
            cancelKeyTracking();
            final int moreKeyCode = key.getMoreKeys()[0].mCode;
            sListener.onPressKey(moreKeyCode, 0 /* repeatCont */, true /* isSinglePointer */);
            sListener.onCodeInput(moreKeyCode, Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE, false /* isKeyRepeat */);
            sListener.onReleaseKey(moreKeyCode, false /* withSliding */);
            return;
        }
        final int code = key.getCode();
        if (code == Constants.CODE_SPACE || code == Constants.CODE_LANGUAGE_SWITCH) {
            // Long pressing the space key invokes IME switcher dialog.
            if (sListener.onCustomRequest(Constants.CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER)) {
                cancelKeyTracking();
                sListener.onReleaseKey(code, false /* withSliding */);
                return;
            }
        }

        setReleasedKeyGraphics(key, false /* withAnimation */);
        final MoreKeysPanel moreKeysPanel = sDrawingProxy.showMoreKeysKeyboard(key, this);
        if (moreKeysPanel == null) {
            return;
        }
        final int translatedX = moreKeysPanel.translateX(mLastX);
        final int translatedY = moreKeysPanel.translateY(mLastY);
        moreKeysPanel.onDownEvent(translatedX, translatedY, mPointerId);
        mMoreKeysPanel = moreKeysPanel;
    }

    private void cancelKeyTracking() {
        resetKeySelectionByDraggingFinger();
        cancelTrackingForAction();
        setReleasedKeyGraphics(mCurrentKey, true /* withAnimation */);
        sPointerTrackerQueue.remove(this);
    }

    private void onCancelEvent(final int x, final int y, final long eventTime) {
        if (DEBUG_EVENT) {
            printTouchEvent("onCancelEvt:", x, y, eventTime);
        }

        cancelAllPointerTrackers();
        sPointerTrackerQueue.releaseAllPointers(eventTime);
        onCancelEventInternal();
    }

    private void onCancelEventInternal() {
        sTimerProxy.cancelKeyTimersOf(this);
        setReleasedKeyGraphics(mCurrentKey, true /* withAnimation */);
        resetKeySelectionByDraggingFinger();
        dismissMoreKeysPanel();
        // P7-2: a cancelled touch never delivers a glide.
        mGlideDecider.onUpOrCancel();
        mGlidePath.clear();
    }

    private boolean isMajorEnoughMoveToBeOnNewKey(final int x, final int y, final Key newKey) {
        final Key curKey = mCurrentKey;
        if (newKey == curKey) {
            return false;
        }
        if (curKey == null /* && newKey != null */) {
            return true;
        }
        // A press that went down on a modifier key must travel a real distance from its
        // touch-down point before it is allowed to leave that key. The hysteresis below is
        // measured from the key edge, so without this gate a press landing near the edge of
        // ?123 or shift leaves the key after 5dp of movement -- less than the platform's own
        // 8dp touch slop -- which arms the momentary layout switch and springs it back on
        // release. Measuring from the touch-down point instead makes the edge of the key as
        // reliable as its centre. See docs/SYMBOL-KEY-EDGE-FIX.md.
        if (mIsDownOnModifierKey && !mIsInDraggingFinger
                && !mKeyDetector.isBeyondSlidingModifierSlop(CoordinateUtils.x(mDownCoordinates),
                        CoordinateUtils.y(mDownCoordinates), x, y)) {
            return false;
        }
        // Here curKey points to the different key from newKey.
        final int keyHysteresisDistanceSquared = mKeyDetector.getKeyHysteresisDistanceSquared(
                mIsInSlidingKeyInput);
        final int distanceFromKeyEdgeSquared = curKey.squaredDistanceToHitboxEdge(x, y);
        if (distanceFromKeyEdgeSquared >= keyHysteresisDistanceSquared) {
            if (DEBUG_MODE) {
                final float distanceToEdgeRatio = (float)Math.sqrt(distanceFromKeyEdgeSquared)
                        / (mKeyboard.mMostCommonKeyWidth + mKeyboard.mHorizontalGap);
                Log.d(TAG, String.format("[%d] isMajorEnoughMoveToBeOnNewKey:"
                        +" %.2f key width from key edge", mPointerId, distanceToEdgeRatio));
            }
            return true;
        }
        return false;
    }

    private void startLongPressTimer(final Key key) {
        // Note that we need to cancel all active long press shift key timers if any whenever we
        // start a new long press timer for both non-shift and shift keys.
        sTimerProxy.cancelLongPressShiftKeyTimer();
        if (key == null) return;
        if (!key.isLongPressEnabled()) return;
        // Caveat: Please note that isLongPressEnabled() can be true even if the current key
        // doesn't have its more keys. (e.g. spacebar, globe key) If we are in the dragging finger
        // mode, we will disable long press timer of such key.
        // We always need to start the long press timer if the key has its more keys regardless of
        // whether or not we are in the dragging finger mode.
        if (mIsInDraggingFinger && key.getMoreKeys() == null) return;

        final int delay = getLongPressTimeout(key.getCode());
        if (delay <= 0) return;
        sTimerProxy.startLongPressTimerOf(this, delay);
    }

    private int getLongPressTimeout(final int code) {
        if (code == Constants.CODE_SHIFT) {
            return sParams.mLongPressShiftLockTimeout;
        }
        final int longpressTimeout = Settings.getInstance().getCurrent().mKeyLongpressTimeout;
        if (mIsInSlidingKeyInput) {
            // We use longer timeout for sliding finger input started from the modifier key.
            return longpressTimeout * MULTIPLIER_FOR_LONG_PRESS_TIMEOUT_IN_SLIDING_INPUT;
        }
        if (code == Constants.CODE_SPACE) {
            // Cursor can be moved in space
            return longpressTimeout * MULTIPLIER_FOR_LONG_PRESS_TIMEOUT_IN_SLIDING_INPUT;
        }
        return longpressTimeout;
    }

    private void detectAndSendKey(final Key key, final int x, final int y) {
        if (key == null) return;

        final int code = key.getCode();
        callListenerOnCodeInput(key, code, x, y, false /* isKeyRepeat */);
        callListenerOnRelease(key, code, false /* withSliding */);
    }

    private void startRepeatKey(final Key key) {
        if (key == null) return;
        if (!key.isRepeatable()) return;
        // Don't start key repeat when we are in the dragging finger mode.
        if (mIsInDraggingFinger) return;
        final int startRepeatCount = 1;
        startKeyRepeatTimer(startRepeatCount);
    }

    public void onKeyRepeat(final int code, final int repeatCount) {
        // P7-2: a key-repeat timer that was already in flight when the glide armed is dead.
        if (mGlideDecider.isArmed()) {
            return;
        }
        final Key key = getKey();
        if (key == null || key.getCode() != code) {
            mCurrentRepeatingKeyCode = Constants.NOT_A_CODE;
            return;
        }
        mCurrentRepeatingKeyCode = code;
        final int nextRepeatCount = repeatCount + 1;
        startKeyRepeatTimer(nextRepeatCount);
        callListenerOnPressAndCheckKeyboardLayoutChange(key, repeatCount);
        callListenerOnCodeInput(key, code, mKeyX, mKeyY, true /* isKeyRepeat */);
    }

    private void startKeyRepeatTimer(final int repeatCount) {
        final int delay =
                (repeatCount == 1) ? sParams.mKeyRepeatStartTimeout : sParams.mKeyRepeatInterval;
        sTimerProxy.startKeyRepeatTimerOf(this, repeatCount, delay);
    }

    private void printTouchEvent(final String title, final int x, final int y,
            final long eventTime) {
        final Key key = mKeyDetector.detectHitKey(x, y);
        final String code = (key == null ? "none" : Constants.printableCode(key.getCode()));
        Log.d(TAG, String.format("[%d]%s%s %4d %4d %5d %s", mPointerId,
                (mIsTrackingForActionDisabled ? "-" : " "), title, x, y, eventTime, code));
    }
}

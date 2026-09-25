/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
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

import android.animation.AnimatorInflater;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;


import java.util.WeakHashMap;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.accessibility.KeyboardAccessibilityDelegate;
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingPreviewPlacerView;
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingProxy;
import rkr.simplekeyboard.inputmethod.keyboard.internal.GlideTrail;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyDrawParams;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyPreviewChoreographer;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyPreviewDrawParams;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyPreviewView;
import rkr.simplekeyboard.inputmethod.keyboard.internal.MoreKeySpec;
import rkr.simplekeyboard.inputmethod.keyboard.internal.NonDistinctMultitouchHelper;
import rkr.simplekeyboard.inputmethod.keyboard.internal.TimerHandler;
import rkr.simplekeyboard.inputmethod.latin.Subtype;
import rkr.simplekeyboard.inputmethod.latin.RichInputMethodManager;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.CoordinateUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LanguageOnSpacebarUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LocaleResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.TypefaceUtils;

/**
 * A view that is responsible for detecting key presses and touch movements.
 */
public final class MainKeyboardView extends KeyboardView implements MoreKeysPanel.Controller, DrawingProxy {
    private static final String TAG = MainKeyboardView.class.getSimpleName();

    /** Listener for {@link KeyboardActionListener}. */
    private KeyboardActionListener mKeyboardActionListener;

    /* Space key and its icon and background. */
    private Key mSpaceKey;
    // Stuff to draw language name on spacebar.
    private final int mLanguageOnSpacebarFinalAlpha;
    private int mLanguageOnSpacebarFormatType;
    /** Cached spacebar language text and its scale; recomputed only when the subtype or
     * keyboard changes, keeping string allocation out of the draw path. */
    private String mLanguageOnSpacebarText;
    private float mLanguageOnSpacebarTextScaleX = 1.0f;
    private final float mLanguageOnSpacebarTextRatio;
    private float mLanguageOnSpacebarTextSize;
    private final int mLanguageOnSpacebarTextColor;
    // The minimum x-scale to fit the language name on spacebar.
    private static final float MINIMUM_XSCALE_OF_LANGUAGE_NAME = 0.8f;

    // Stuff to draw altCodeWhileTyping keys.
    private final ObjectAnimator mAltCodeKeyWhileTypingFadeoutAnimator;
    private final ObjectAnimator mAltCodeKeyWhileTypingFadeinAnimator;
    private int mAltCodeKeyWhileTypingAnimAlpha = Constants.Color.ALPHA_OPAQUE;

    // Drawing preview placer view
    private final DrawingPreviewPlacerView mDrawingPreviewPlacerView;
    private final int[] mOriginCoords = CoordinateUtils.newInstance();

    // Key preview
    private final KeyPreviewDrawParams mKeyPreviewDrawParams;
    private final KeyPreviewChoreographer mKeyPreviewChoreographer;

    // More keys keyboard
    private final Paint mBackgroundDimAlphaPaint = new Paint();
    private final View mMoreKeysKeyboardContainer;
    private final WeakHashMap<Key, Keyboard> mMoreKeysKeyboardCache = new WeakHashMap<>();
    private final boolean mConfigShowMoreKeysKeyboardAtTouchedPoint;
    // More keys panel (used by both more keys keyboard and more suggestions view)
    // TODO: Consider extending to support multiple more keys panels
    private MoreKeysPanel mMoreKeysPanel;

    // The glide trail (P7-5): the fading polyline drawn under the fingertip of an armed glide.
    // Fed through {@link DrawingProxy#onGlideTrailPoint}; preallocated, zero-allocation draw.
    private final GlideTrail mGlideTrail = new GlideTrail();
    private final Paint mGlideTrailPaint = new Paint();

    private final KeyDetector mKeyDetector;
    private final NonDistinctMultitouchHelper mNonDistinctMultitouchHelper;

    private final TimerHandler mTimerHandler;
    private final int mLanguageOnSpacebarHorizontalMargin;
    private final KeyboardAccessibilityDelegate mAccessibilityDelegate;

    public MainKeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.mainKeyboardViewStyle);
    }

    public MainKeyboardView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);

        final DrawingPreviewPlacerView drawingPreviewPlacerView =
                new DrawingPreviewPlacerView(context, attrs);

        final TypedArray mainKeyboardViewAttr = context.obtainStyledAttributes(
                attrs, R.styleable.MainKeyboardView, defStyle, R.style.MainKeyboardView);
        final int ignoreAltCodeKeyTimeout = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_ignoreAltCodeKeyTimeout, 0);
        mTimerHandler = new TimerHandler(this, ignoreAltCodeKeyTimeout);

        final float keyHysteresisDistance = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_keyHysteresisDistance, 0.0f);
        final float keyHysteresisDistanceForSlidingModifier = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_keyHysteresisDistanceForSlidingModifier, 0.0f);
        final float slidingModifierSlop = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_slidingModifierSlop, 0.0f);
        mKeyDetector = new KeyDetector(keyHysteresisDistance,
                keyHysteresisDistanceForSlidingModifier, slidingModifierSlop);

        PointerTracker.init(mainKeyboardViewAttr, mTimerHandler, this /* DrawingProxy */);

        final boolean hasDistinctMultitouch = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT);
        mNonDistinctMultitouchHelper = hasDistinctMultitouch ? null
                : new NonDistinctMultitouchHelper();

        final int backgroundDimAlpha = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_backgroundDimAlpha, 0);
        mBackgroundDimAlphaPaint.setColor(Color.BLACK);
        mBackgroundDimAlphaPaint.setAlpha(backgroundDimAlpha);
        final int glideTrailColor = mainKeyboardViewAttr.getColor(
                R.styleable.MainKeyboardView_glideTrailColor, Color.TRANSPARENT);
        mGlideTrailPaint.setColor(glideTrailColor);
        mGlideTrailPaint.setStyle(Paint.Style.STROKE);
        mGlideTrailPaint.setStrokeWidth(getResources().getDimension(
                R.dimen.config_glide_trail_stroke_width));
        mGlideTrailPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlideTrailPaint.setStrokeJoin(Paint.Join.ROUND);
        mGlideTrailPaint.setAntiAlias(true);
        mLanguageOnSpacebarTextRatio = mainKeyboardViewAttr.getFraction(
                R.styleable.MainKeyboardView_languageOnSpacebarTextRatio, 1, 1, 1.0f);
        mLanguageOnSpacebarTextColor = mainKeyboardViewAttr.getColor(
                R.styleable.MainKeyboardView_languageOnSpacebarTextColor, 0);
        mLanguageOnSpacebarFinalAlpha = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_languageOnSpacebarFinalAlpha,
                Constants.Color.ALPHA_OPAQUE);
        final int altCodeKeyWhileTypingFadeoutAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeoutAnimator, 0);
        final int altCodeKeyWhileTypingFadeinAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeinAnimator, 0);

        mKeyPreviewDrawParams = new KeyPreviewDrawParams(mainKeyboardViewAttr);
        mKeyPreviewChoreographer = new KeyPreviewChoreographer(mKeyPreviewDrawParams);

        final int moreKeysKeyboardLayoutId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_moreKeysKeyboardLayout, 0);
        mConfigShowMoreKeysKeyboardAtTouchedPoint = mainKeyboardViewAttr.getBoolean(
                R.styleable.MainKeyboardView_showMoreKeysKeyboardAtTouchedPoint, false);

        mainKeyboardViewAttr.recycle();

        mDrawingPreviewPlacerView = drawingPreviewPlacerView;

        final LayoutInflater inflater = LayoutInflater.from(getContext());
        mMoreKeysKeyboardContainer = inflater.inflate(moreKeysKeyboardLayoutId, null);
        mAltCodeKeyWhileTypingFadeoutAnimator = loadObjectAnimator(
                altCodeKeyWhileTypingFadeoutAnimatorResId, this);
        mAltCodeKeyWhileTypingFadeinAnimator = loadObjectAnimator(
                altCodeKeyWhileTypingFadeinAnimatorResId, this);

        mKeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER;

        mLanguageOnSpacebarHorizontalMargin = (int)getResources().getDimension(
                R.dimen.config_language_on_spacebar_horizontal_margin);

        mAccessibilityDelegate = new KeyboardAccessibilityDelegate(this, mKeyDetector);
        setAccessibilityDelegate(mAccessibilityDelegate);
    }

    @Override
    public boolean dispatchHoverEvent(final MotionEvent event) {
        return mAccessibilityDelegate.dispatchHoverEvent(event)
                || super.dispatchHoverEvent(event);
    }

    /**
     * Announces the input language after a user-initiated subtype switch (globe key or the
     * subtype picker). Routed through the accessibility delegate so all keyboard TalkBack
     * announcements live in one place and share the same accessibility-enabled gate.
     */
    public void announceLanguageForAccessibility(final String languageName) {
        mAccessibilityDelegate.announceLanguage(languageName);
    }

    private ObjectAnimator loadObjectAnimator(final int resId, final Object target) {
        if (resId == 0) {
            // TODO: Stop returning null.
            return null;
        }
        final ObjectAnimator animator = (ObjectAnimator)AnimatorInflater.loadAnimator(
                getContext(), resId);
        if (animator != null) {
            animator.setTarget(target);
        }
        return animator;
    }

    private static void cancelAndStartAnimators(final ObjectAnimator animatorToCancel,
            final ObjectAnimator animatorToStart) {
        if (animatorToCancel == null || animatorToStart == null) {
            // TODO: Stop using null as a no-operation animator.
            return;
        }
        float startFraction = 0.0f;
        if (animatorToCancel.isStarted()) {
            animatorToCancel.cancel();
            startFraction = 1.0f - animatorToCancel.getAnimatedFraction();
        }
        final long startTime = (long)(animatorToStart.getDuration() * startFraction);
        animatorToStart.start();
        animatorToStart.setCurrentPlayTime(startTime);
    }

    // Implements {@link DrawingProxy#startWhileTypingAnimation(int)}.
    /**
     * Called when a while-typing-animation should be started.
     * @param fadeInOrOut {@link DrawingProxy#FADE_IN} starts while-typing-fade-in animation.
     * {@link DrawingProxy#FADE_OUT} starts while-typing-fade-out animation.
     */
    @Override
    public void startWhileTypingAnimation(final int fadeInOrOut) {
        switch (fadeInOrOut) {
        case DrawingProxy.FADE_IN:
            cancelAndStartAnimators(
                    mAltCodeKeyWhileTypingFadeoutAnimator, mAltCodeKeyWhileTypingFadeinAnimator);
            break;
        case DrawingProxy.FADE_OUT:
            cancelAndStartAnimators(
                    mAltCodeKeyWhileTypingFadeinAnimator, mAltCodeKeyWhileTypingFadeoutAnimator);
            break;
        }
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
        PointerTracker.setKeyboardActionListener(listener);
    }

    // TODO: We should reconsider which coordinate system should be used to represent keyboard
    // event.
    public int getKeyX(final int x) {
        return Constants.isValidCoordinate(x) ? mKeyDetector.getTouchX(x) : x;
    }

    // TODO: We should reconsider which coordinate system should be used to represent keyboard
    // event.
    public int getKeyY(final int y) {
        return Constants.isValidCoordinate(y) ? mKeyDetector.getTouchY(y) : y;
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see Keyboard
     * @see #getKeyboard()
     * @param keyboard the keyboard to display in this view
     */
    @Override
    public void setKeyboard(final Keyboard keyboard) {
        // Remove any pending messages, except dismissing preview and key repeat.
        mTimerHandler.cancelLongPressTimers();
        final Keyboard lastKeyboard = getKeyboard();
        super.setKeyboard(keyboard);
        mKeyDetector.setKeyboard(
                keyboard, -getPaddingLeft(), -getPaddingTop() + getVerticalCorrection());
        PointerTracker.setKeyDetector(mKeyDetector);
        mMoreKeysKeyboardCache.clear();

        mSpaceKey = keyboard.getKey(Constants.CODE_SPACE);
        final int keyHeight = keyboard.mMostCommonKeyHeight;
        mLanguageOnSpacebarTextSize = keyHeight * mLanguageOnSpacebarTextRatio;
        mLanguageOnSpacebarText = null;
        mAccessibilityDelegate.invalidateRoot();
        // Spoken shift-mode change (no-op unless accessibility is enabled).
        mAccessibilityDelegate.onKeyboardChanged(lastKeyboard, keyboard);
    }

    /**
     * Enables or disables the key preview popup. This is a popup that shows a magnified
     * version of the depressed key. By default the preview is enabled.
     * @param previewEnabled whether or not to enable the key feedback preview
     * @param delay the delay after which the preview is dismissed
     */
    public void setKeyPreviewPopupEnabled(final boolean previewEnabled, final int delay) {
        mKeyPreviewDrawParams.setPopupEnabled(previewEnabled, delay);
    }

    private void locatePreviewPlacerView() {
        getLocationInWindow(mOriginCoords);
        mDrawingPreviewPlacerView.setKeyboardViewGeometry(mOriginCoords);
    }

    private void installPreviewPlacerView() {
        final View rootView = getRootView();
        if (rootView == null) {
            Log.w(TAG, "Cannot find root view");
            return;
        }
        final ViewGroup windowContentView = rootView.findViewById(android.R.id.content);
        // Note: It'd be very weird if we get null by android.R.id.content.
        if (windowContentView == null) {
            Log.w(TAG, "Cannot find android.R.id.content view to add DrawingPreviewPlacerView");
            return;
        }
        windowContentView.addView(mDrawingPreviewPlacerView);
    }

    // Implements {@link DrawingProxy#onKeyPressed(Key,boolean)}.
    @Override
    public void onKeyPressed(final Key key, final boolean withPreview) {
        final boolean showPreview = withPreview && !key.noKeyPreview();
        // M2 (docs/APPLE-UX-2026-09-25.md): on iOS a LETTER key answers a tap with the balloon
        // ONLY — it does not darken. Functional keys (shift, delete, 123, enter, spacebar) DO
        // change fill on iOS and keep the pressed state here. The fill also stays whenever the
        // balloon cannot appear — the popup switched off in settings, or a key that has no
        // preview at all — because feedback-less keys would be worse than non-iOS ones. The
        // glide highlight is unaffected: it arrives with withPreview == false, so it still
        // presses the key (P7-5, PointerTracker.updateGlideFeedback).
        final boolean balloonCarriesTheFeedback =
                showPreview && key.isNormalBackground() && mKeyPreviewDrawParams.isPopupEnabled();
        if (!balloonCarriesTheFeedback) {
            key.onPressed();
            invalidateKey(key);
        }
        if (showPreview) {
            showKeyPreview(key);
        }
    }

    private void showKeyPreview(final Key key) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }
        final KeyPreviewDrawParams previewParams = mKeyPreviewDrawParams;
        if (!previewParams.isPopupEnabled()) {
            previewParams.setVisibleOffset(-Math.round(keyboard.mVerticalGap));
            return;
        }

        locatePreviewPlacerView();
        getLocationInWindow(mOriginCoords);
        final int backgroundColor = mTheme.mCustomColorSupport ? mCustomColor : Color.TRANSPARENT;
        mKeyPreviewChoreographer.placeAndShowKeyPreview(key, keyboard.mIconsSet, getKeyDrawParams(),
                mOriginCoords, mDrawingPreviewPlacerView, isHardwareAccelerated(), backgroundColor);
    }

    private void dismissKeyPreviewWithoutDelay(final Key key) {
        mKeyPreviewChoreographer.dismissKeyPreview(key, false /* withAnimation */);
        invalidateKey(key);
    }

    // Implements {@link DrawingProxy#onKeyReleased(Key,boolean)}.
    @Override
    public void onKeyReleased(final Key key, final boolean withAnimation) {
        key.onReleased();
        invalidateKey(key);
        if (!key.noKeyPreview()) {
            if (withAnimation) {
                dismissKeyPreview(key);
            } else {
                dismissKeyPreviewWithoutDelay(key);
            }
        }
    }

    private void dismissKeyPreview(final Key key) {
        if (isHardwareAccelerated()) {
            mKeyPreviewChoreographer.dismissKeyPreview(key, true /* withAnimation */);
            return;
        }
        // TODO: Implement preference option to control key preview method and duration.
        mTimerHandler.postDismissKeyPreview(key, mKeyPreviewDrawParams.getLingerTimeout());
    }

    // Implements {@link DrawingProxy#onGlideTrailPoint(float,float,long)}.
    @Override
    public void onGlideTrailPoint(final float x, final float y, final long eventTime) {
        // Explicit narrowing: gesture deltas are milliseconds apart, so the float's 24-bit
        // mantissa is exact where the trail's fade math reads it (same argument as GlidePath).
        mGlideTrail.addPoint(x, y, (float) eventTime);
        invalidate();
    }

    // Implements {@link DrawingProxy#onGlideTrailEnd()}.
    @Override
    public void onGlideTrailEnd() {
        // A no-op for touches that never armed a glide: no per-keystroke invalidate.
        if (mGlideTrail.isEmpty()) {
            return;
        }
        // P7-7: the lift fades the trail out over GlideTrail.FADE_OUT_MS instead of erasing it
        // instantly; the fade drives its own bounded re-invalidation from the draw pass.
        mGlideTrail.startFadeOut(SystemClock.uptimeMillis());
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        installPreviewPlacerView();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // A closing keyboard ends every fade: no stale trail at the next show, no scheduled
        // repaints of a detached view (a pending postInvalidateDelayed on a detached view is a
        // no-op, so there is nothing to cancel — the ring itself is what must not survive).
        mGlideTrail.clear();
        mDrawingPreviewPlacerView.removeAllViews();
    }

    // Implements {@link DrawingProxy@showMoreKeysKeyboard(Key,PointerTracker)}.
    //@Override
    public MoreKeysPanel showMoreKeysKeyboard(final Key key,
            final PointerTracker tracker) {
        final MoreKeySpec[] moreKeys = key.getMoreKeys();
        if (moreKeys == null) {
            return null;
        }
        Keyboard moreKeysKeyboard = mMoreKeysKeyboardCache.get(key);
        if (moreKeysKeyboard == null) {
            // {@link KeyPreviewDrawParams#mPreviewVisibleWidth} should have been set at
            // {@link KeyPreviewChoreographer#placeKeyPreview(Key,TextView,KeyboardIconsSet,KeyDrawParams,int,int[]},
            // though there may be some chances that the value is zero. <code>width == 0</code>
            // will cause zero-division error at
            // {@link MoreKeysKeyboardParams#setParameters(int,int,int,int,int,int,boolean,int)}.
            final boolean isSingleMoreKeyWithPreview = mKeyPreviewDrawParams.isPopupEnabled()
                    && !key.noKeyPreview() && moreKeys.length == 1
                    && mKeyPreviewDrawParams.getVisibleWidth() > 0;
            final MoreKeysKeyboard.Builder builder = new MoreKeysKeyboard.Builder(
                    getContext(), key, getKeyboard(), isSingleMoreKeyWithPreview,
                    mKeyPreviewDrawParams.getVisibleWidth(),
                    mKeyPreviewDrawParams.getVisibleHeight(), newLabelPaint(key));
            moreKeysKeyboard = builder.build();
            mMoreKeysKeyboardCache.put(key, moreKeysKeyboard);
        }

        final MoreKeysKeyboardView moreKeysKeyboardView =
                mMoreKeysKeyboardContainer.findViewById(R.id.more_keys_keyboard_view);
        moreKeysKeyboardView.setKeyboard(moreKeysKeyboard);
        mMoreKeysKeyboardContainer.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        final int[] lastCoords = CoordinateUtils.newInstance();
        tracker.getLastCoordinates(lastCoords);
        final boolean keyPreviewEnabled = mKeyPreviewDrawParams.isPopupEnabled()
                && !key.noKeyPreview();
        // The more keys keyboard is usually horizontally aligned with the center of the parent key.
        // If showMoreKeysKeyboardAtTouchedPoint is true and the key preview is disabled, the more
        // keys keyboard is placed at the touch point of the parent key.
        final int pointX = (mConfigShowMoreKeysKeyboardAtTouchedPoint && !keyPreviewEnabled)
                ? CoordinateUtils.x(lastCoords)
                : key.getX() + key.getWidth() / 2;
        // The more keys keyboard is usually vertically aligned with the top edge of the parent key
        // (plus vertical gap). If the key preview is enabled, the more keys keyboard is vertically
        // aligned with the bottom edge of the visible part of the key preview.
        // {@code mPreviewVisibleOffset} has been set appropriately in
        // {@link KeyboardView#showKeyPreview(PointerTracker)}.
        final int pointY = key.getY() + mKeyPreviewDrawParams.getVisibleOffset()
                + Math.round(moreKeysKeyboard.mBottomPadding);
        moreKeysKeyboardView.showMoreKeysPanel(this, this, pointX, pointY, mKeyboardActionListener);
        return moreKeysKeyboardView;
    }

    public boolean isInDraggingFinger() {
        if (isShowingMoreKeysPanel()) {
            return true;
        }
        return PointerTracker.isAnyInDraggingFinger();
    }

    public boolean isInCursorMove() {
        return PointerTracker.isAnyInCursorMove();
    }

    @Override
    public void onShowMoreKeysPanel(final MoreKeysPanel panel) {
        locatePreviewPlacerView();
        // Dismiss another {@link MoreKeysPanel} that may be being showed.
        onDismissMoreKeysPanel();
        // Dismiss all key previews that may be being showed.
        PointerTracker.setReleasedKeyGraphicsToAllKeys();
        // Dismiss sliding key input preview that may be being showed.
        panel.showInParent(mDrawingPreviewPlacerView);
        mMoreKeysPanel = panel;
    }

    public boolean isShowingMoreKeysPanel() {
        return mMoreKeysPanel != null && mMoreKeysPanel.isShowingInParent();
    }

    @Override
    public void onCancelMoreKeysPanel() {
        PointerTracker.dismissAllMoreKeysPanels();
    }

    @Override
    public void onDismissMoreKeysPanel() {
        if (isShowingMoreKeysPanel()) {
            mMoreKeysPanel.removeFromParent();
            mMoreKeysPanel = null;
        }
    }

    public void startDoubleTapShiftKeyTimer() {
        mTimerHandler.startDoubleTapShiftKeyTimer();
    }

    public void cancelDoubleTapShiftKeyTimer() {
        mTimerHandler.cancelDoubleTapShiftKeyTimer();
    }

    public boolean isInDoubleTapShiftKeyTimeout() {
        return mTimerHandler.isInDoubleTapShiftKeyTimeout();
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        if (getKeyboard() == null) {
            return false;
        }
        if (mNonDistinctMultitouchHelper != null) {
            if (event.getPointerCount() > 1 && mTimerHandler.isInKeyRepeat()) {
                // Key repeating timer will be canceled if 2 or more keys are in action.
                mTimerHandler.cancelKeyRepeatTimers();
            }
            // Non distinct multitouch screen support
            mNonDistinctMultitouchHelper.processMotionEvent(event, mKeyDetector);
            return true;
        }
        return processMotionEvent(event);
    }

    public boolean processMotionEvent(final MotionEvent event) {
        final int index = event.getActionIndex();
        final int id = event.getPointerId(index);
        final PointerTracker tracker = PointerTracker.getPointerTracker(id);
        // A panel kept on screen for accessibility touch exploration has no owning tracker
        // (see PointerTracker#onUpEventInternal). A new touch on the keyboard dismisses it,
        // like tapping outside a popup; the touch itself then proceeds normally. This state
        // is unreachable without accessibility: a panel otherwise always has a live tracker.
        if (isShowingMoreKeysPanel() && !tracker.isShowingMoreKeysPanel()
                && PointerTracker.getActivePointerTrackerCount() == 0
                && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mMoreKeysPanel.dismissMoreKeysPanel();
        }
        // When a more keys panel is showing, we should ignore other fingers' single touch events
        // other than the finger that is showing the more keys panel.
        if (isShowingMoreKeysPanel() && !tracker.isShowingMoreKeysPanel()
                && PointerTracker.getActivePointerTrackerCount() == 1) {
            return true;
        }
        tracker.processMotionEvent(event, mKeyDetector);
        return true;
    }

    public void cancelAllOngoingEvents() {
        mTimerHandler.cancelAllMessages();
        PointerTracker.setReleasedKeyGraphicsToAllKeys();
        PointerTracker.dismissAllMoreKeysPanels();
        // A panel kept on screen for accessibility touch exploration has no
        // owning tracker (see PointerTracker#onUpEventInternal), so the
        // per-tracker dismissal above misses it — e.g. when the input field
        // changes while the panel is up. Dismiss it directly.
        onDismissMoreKeysPanel();
        PointerTracker.cancelAllPointerTrackers();
    }

    public void closing() {
        cancelAllOngoingEvents();
        mMoreKeysKeyboardCache.clear();
    }

    public void onHideWindow() {
        onDismissMoreKeysPanel();
    }

    public void startDisplayLanguageOnSpacebar(final boolean subtypeChanged,
            final int languageOnSpacebarFormatType) {
        if (subtypeChanged) {
            KeyPreviewView.clearTextCache();
        }
        mLanguageOnSpacebarFormatType = languageOnSpacebarFormatType;
        mLanguageOnSpacebarText = null;
        invalidateKey(mSpaceKey);
    }

    /**
     * The trail rides on top of the keys (and on top of the software offscreen blit), so it
     * is never baked into {@link KeyboardView}'s buffer and needs no invalidation logic of its
     * own beyond the feed's {@link #invalidate()} calls.
     */
    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        drawGlideTrail(canvas);
    }

    private void drawGlideTrail(final Canvas canvas) {
        // The wall clock enters here and only here: the fade-out is a wall-time animation, while
        // the tail's own fade math stays sample-relative (see GlideTrail's class doc).
        final float nowMs = (float) SystemClock.uptimeMillis();
        if (mGlideTrail.isFadeDone(nowMs)) {
            // The fade ran out: drop the ring and let this frame paint without it.
            mGlideTrail.clear();
            return;
        }
        final int first = mGlideTrail.firstVisible();
        final int size = mGlideTrail.getSize();
        if (size - first < 2) {
            return;
        }
        final Paint paint = mGlideTrailPaint;
        final float fadeFactor = mGlideTrail.fadeFactor(nowMs);
        // Per-segment drawLine with a preallocated paint: no allocation on the draw path.
        for (int i = first; i < size - 1; i++) {
            paint.setAlpha((int)(mGlideTrail.alphaAt(i + 1) * fadeFactor));
            canvas.drawLine(mGlideTrail.xAt(i), mGlideTrail.yAt(i),
                    mGlideTrail.xAt(i + 1), mGlideTrail.yAt(i + 1), paint);
        }
        if (mGlideTrail.getFadingOut()) {
            // Bounded: the schedule stops the frame after GlideTrail.FADE_OUT_MS (isFadeDone
            // above), on a new gesture (addPoint resets the fade), and on window detach.
            postInvalidateDelayed(FADE_FRAME_MS);
        }
    }

    /**
     * The fade's frame cadence. 33 ms (≈30 fps) instead of one animation frame: the fade itself
     * is wall-clock anchored (GlideTrail.fadeFactor), so the cadence changes only how often the
     * strip is redrawn during the 250 ms fade-out, never the fade's duration or shape — and the
     * coarser grid halves the redraw count of a purely cosmetic animation.
     */
    private static final long FADE_FRAME_MS = 33L;

    @Override
    protected void onDrawKeyTopVisuals(final Key key, final Canvas canvas, final Paint paint,
            final KeyDrawParams params) {
        if (key.altCodeWhileTyping()) {
            params.mAnimAlpha = mAltCodeKeyWhileTypingAnimAlpha;
        }
        super.onDrawKeyTopVisuals(key, canvas, paint, params);
        final int code = key.getCode();
        if (code == Constants.CODE_SPACE) {
            // If more than one language is enabled in current input method
            final RichInputMethodManager imm = RichInputMethodManager.getInstance();
            if (imm.hasMultipleEnabledSubtypes()) {
                drawLanguageOnSpacebar(key, canvas, paint);
            }
        }
    }

    private boolean fitsTextIntoWidth(final int width, final String text, final Paint paint) {
        final int maxTextWidth = width - mLanguageOnSpacebarHorizontalMargin * 2;
        paint.setTextScaleX(1.0f);
        final float textWidth = TypefaceUtils.getStringWidth(text, paint);
        if (textWidth < width) {
            return true;
        }

        final float scaleX = maxTextWidth / textWidth;
        if (scaleX < MINIMUM_XSCALE_OF_LANGUAGE_NAME) {
            return false;
        }

        paint.setTextScaleX(scaleX);
        return TypefaceUtils.getStringWidth(text, paint) < maxTextWidth;
    }

    // Compute and cache the language name for the spacebar. Called only when the cache is
    // empty (subtype or keyboard changed) — never on the steady-state draw path.
    private void cacheLanguageOnSpacebarText(final Paint paint,
                                             final Subtype subtype, final int width) {
        // Choose appropriate language name to fit into the width.
        if (mLanguageOnSpacebarFormatType == LanguageOnSpacebarUtils.FORMAT_TYPE_FULL_LOCALE) {
            final String fullText =
                    LocaleResourceUtils.getLocaleDisplayNameInLocale(subtype.getLocale());
            if (fitsTextIntoWidth(width, fullText, paint)) {
                mLanguageOnSpacebarText = fullText;
                mLanguageOnSpacebarTextScaleX = paint.getTextScaleX();
                return;
            }
        }

        final String middleText =
                LocaleResourceUtils.getLanguageDisplayNameInLocale(subtype.getLocale());
        if (fitsTextIntoWidth(width, middleText, paint)) {
            mLanguageOnSpacebarText = middleText;
            mLanguageOnSpacebarTextScaleX = paint.getTextScaleX();
            return;
        }

        mLanguageOnSpacebarText = "";
        mLanguageOnSpacebarTextScaleX = 1.0f;
    }

    private void drawLanguageOnSpacebar(final Key key, final Canvas canvas, final Paint paint) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }
        final int width = key.getWidth();
        final int height = key.getHeight();
        paint.setTextAlign(Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(mLanguageOnSpacebarTextSize);
        if (mLanguageOnSpacebarText == null) {
            cacheLanguageOnSpacebarText(paint, keyboard.mId.mSubtype, width);
        }
        paint.setTextScaleX(mLanguageOnSpacebarTextScaleX);
        final String language = mLanguageOnSpacebarText;
        // Draw language text with shadow
        final float descent = paint.descent();
        final float textHeight = -paint.ascent() + descent;
        final float baseline = height / 2f + textHeight / 2;
        paint.setColor(mLanguageOnSpacebarTextColor);
        paint.setAlpha(mLanguageOnSpacebarFinalAlpha);
        canvas.drawText(language, width / 2f, baseline - descent, paint);
        paint.clearShadowLayer();
        paint.setTextScaleX(1.0f);
    }
}

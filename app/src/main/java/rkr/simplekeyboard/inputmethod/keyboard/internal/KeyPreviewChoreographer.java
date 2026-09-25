/*
 * Copyright (C) 2014 The Android Open Source Project
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

package rkr.simplekeyboard.inputmethod.keyboard.internal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.Key;
import rkr.simplekeyboard.inputmethod.latin.common.CoordinateUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewLayoutUtils;

/**
 * This class controls pop up key previews. This class decides:
 * - what kind of key previews should be shown.
 * - where key previews should be placed.
 * - how key previews should be shown and dismissed.
 */
public final class KeyPreviewChoreographer {
    // Free {@link KeyPreviewView} pool that can be used for key preview.
    private final ArrayDeque<KeyPreviewView> mFreeKeyPreviewViews = new ArrayDeque<>();
    // Map from {@link Key} to {@link KeyPreviewView} that is currently being displayed as key
    // preview.
    private final HashMap<Key,KeyPreviewView> mShowingKeyPreviewViews = new HashMap<>();
    // Dismiss animators cached per preview view: an animator is bound to its target, and preview
    // views outlive individual key presses (they are pooled), so the animator is built once per
    // view and restarted afterwards — zero allocations in the per-press path.
    private final HashMap<KeyPreviewView,KeyPreviewAnimators> mDismissAnimatorsCache =
            new HashMap<>();

    private final KeyPreviewDrawParams mParams;

    public KeyPreviewChoreographer(final KeyPreviewDrawParams params) {
        mParams = params;
    }

    public KeyPreviewView getKeyPreviewView(final Key key, final ViewGroup placerView) {
        KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.remove(key);
        if (keyPreviewView != null) {
            keyPreviewView.setScaleX(1);
            keyPreviewView.setScaleY(1);
            return keyPreviewView;
        }
        keyPreviewView = mFreeKeyPreviewViews.poll();
        if (keyPreviewView != null) {
            keyPreviewView.setScaleX(1);
            keyPreviewView.setScaleY(1);
            return keyPreviewView;
        }
        final Context context = placerView.getContext();
        keyPreviewView = new KeyPreviewView(context, null /* attrs */);
        // S1 (docs/APPLE-UX-2026-09-25.md): the Tatar theme's rectangular preview background is
        // replaced by the path-drawn droplet. Once per pooled view (a handful of views for the
        // lifetime of the keyboard), never per frame.
        if (mParams.mPreviewBackgroundResId == R.drawable.ios_key_preview_background) {
            keyPreviewView.setBackground(new KeyPreviewBalloonDrawable(context));
        } else {
            keyPreviewView.setBackgroundResource(mParams.mPreviewBackgroundResId);
        }
        placerView.addView(keyPreviewView, ViewLayoutUtils.newLayoutParam(placerView, 0, 0));
        return keyPreviewView;
    }

    public void dismissKeyPreview(final Key key, final boolean withAnimation) {
        if (key == null) {
            return;
        }
        final KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.get(key);
        if (keyPreviewView == null) {
            return;
        }
        final Object tag = keyPreviewView.getTag();
        if (withAnimation) {
            if (tag instanceof KeyPreviewAnimators) {
                final KeyPreviewAnimators animators = (KeyPreviewAnimators)tag;
                animators.startDismiss();
                return;
            }
        }
        // Dismiss preview without animation.
        mShowingKeyPreviewViews.remove(key);
        if (tag instanceof Animator) {
            ((Animator)tag).cancel();
        }
        keyPreviewView.setTag(null);
        keyPreviewView.setVisibility(View.INVISIBLE);
        mFreeKeyPreviewViews.add(keyPreviewView);
    }

    public void placeAndShowKeyPreview(final Key key, final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams, final int[] keyboardOrigin,
            final ViewGroup placerView, final boolean withAnimation,
            final int backgroundColor) {
        final KeyPreviewView keyPreviewView = getKeyPreviewView(key, placerView);
        placeKeyPreview(key, keyPreviewView, iconsSet, drawParams, keyboardOrigin, backgroundColor);
        showKeyPreview(key, keyPreviewView, withAnimation);
    }

    private void placeKeyPreview(final Key key, final KeyPreviewView keyPreviewView,
            final KeyboardIconsSet iconsSet, final KeyDrawParams drawParams,
            final int[] originCoords, final int backgroundColor) {
        keyPreviewView.setPreviewVisual(key, iconsSet, drawParams, backgroundColor);
        keyPreviewView.measure(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mParams.setGeometry(keyPreviewView);
        final int previewWidth = Math.max(keyPreviewView.getMeasuredWidth(), mParams.mMinPreviewWidth);
        final int previewHeight = mParams.mPreviewHeight;
        final int keyWidth = key.getWidth();
        // The key preview is horizontally aligned with the center of the visible part of the
        // parent key. If it doesn't fit in this {@link KeyboardView}, it is moved inward to fit and
        // the left/right background is used if such background is specified.
        int previewX = key.getX() - (previewWidth - keyWidth) / 2
                + CoordinateUtils.x(originCoords);
        // The key preview is placed vertically above the top edge of the parent key with an
        // arbitrary offset.
        final int previewY = key.getY() - previewHeight + mParams.mPreviewOffset
                + CoordinateUtils.y(originCoords);

        ViewLayoutUtils.placeViewAt(
                keyPreviewView, previewX, previewY, previewWidth, previewHeight);
        //keyPreviewView.setPivotX(previewWidth / 2.0f);
        //keyPreviewView.setPivotY(previewHeight);
    }

    void showKeyPreview(final Key key, final KeyPreviewView keyPreviewView,
            final boolean withAnimation) {
        if (!withAnimation) {
            keyPreviewView.setVisibility(View.VISIBLE);
            mShowingKeyPreviewViews.put(key, keyPreviewView);
            return;
        }

        // Show preview with animation.
        final KeyPreviewAnimators animators = getDismissAnimators(keyPreviewView);
        keyPreviewView.setTag(animators);
        showKeyPreview(key, keyPreviewView, false /* withAnimation */);
    }

    private KeyPreviewAnimators getDismissAnimators(final KeyPreviewView keyPreviewView) {
        KeyPreviewAnimators animators = mDismissAnimatorsCache.get(keyPreviewView);
        if (animators == null) {
            animators = new KeyPreviewAnimators(createDismissAnimator(keyPreviewView));
            mDismissAnimatorsCache.put(keyPreviewView, animators);
        }
        return animators;
    }

    private Animator createDismissAnimator(final KeyPreviewView keyPreviewView) {
        final Animator dismissAnimator = mParams.createDismissAnimator(keyPreviewView);
        dismissAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final Animator animator) {
                dismissKeyPreviewView(keyPreviewView);
            }
        });
        return dismissAnimator;
    }

    /**
     * The animation-end half of {@link #dismissKeyPreview(Key,boolean)}, keyed by the view rather
     * than by the key: the cached animator's listener is created once per view, so it cannot
     * capture the key that was current at creation time.
     */
    private void dismissKeyPreviewView(final KeyPreviewView keyPreviewView) {
        Key showingKey = null;
        for (final Map.Entry<Key,KeyPreviewView> entry : mShowingKeyPreviewViews.entrySet()) {
            if (entry.getValue() == keyPreviewView) {
                showingKey = entry.getKey();
                break;
            }
        }
        if (showingKey == null) {
            return;
        }
        mShowingKeyPreviewViews.remove(showingKey);
        keyPreviewView.setTag(null);
        keyPreviewView.setVisibility(View.INVISIBLE);
        mFreeKeyPreviewViews.add(keyPreviewView);
    }

    private static class KeyPreviewAnimators extends AnimatorListenerAdapter {
        private final Animator mDismissAnimator;

        private KeyPreviewAnimators(final Animator dismissAnimator) {
            mDismissAnimator = dismissAnimator;
        }

        private void startDismiss() {
            mDismissAnimator.start();
        }
    }
}

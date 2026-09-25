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

package rkr.simplekeyboard.inputmethod.keyboard.internal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import rkr.simplekeyboard.inputmethod.R;

/**
 * S1 of {@code docs/APPLE-UX-2026-09-25.md}: the key preview balloon as an iOS DROPLET instead
 * of a rectangle.
 *
 * <p>What it replaces and why. The previous background was {@code ios_key_preview_background},
 * a layer-list of two rounded rectangles. A shape in a layer-list fills the WHOLE bounds, and
 * the bounds of a key preview are {@code keyPreviewHeight} = 122dp tall, while the framework
 * contract (see {@link KeyPreviewDrawParams#setGeometry}) says the background's bottom PADDING
 * marks the part that must stay invisible — the 60dp that hangs over the parent key. The old
 * drawable ignored that contract and painted a 122dp white bar across two key rows; verified on
 * the emulator before this change.</p>
 *
 * <p>The geometry here honours the contract: a rounded body fills the visible height
 * ({@code height − bottomPadding}), a short neck of {@link #NECK_HEIGHT_DP} flows out of the
 * body's bottom edge down to the parent key's top edge, and everything below is transparent.
 * The neck is centred because the preview itself is centred on the key — {@code
 * KeyPreviewChoreographer.placeKeyPreview} computes {@code key.getX() − (previewWidth −
 * keyWidth) / 2} and never clamps at the screen edges, so the key's centre and the balloon's
 * centre coincide for every key including the outermost ones. (iOS mirrors the neck at the edges
 * because iOS clamps the balloon inside the screen; this keyboard does not.)</p>
 *
 * <p>Allocation discipline: the path is rebuilt only in {@link #onBoundsChange} — once per
 * preview size — and {@link #draw} touches nothing but the cached path and two paints.</p>
 */
public final class KeyPreviewBalloonDrawable extends Drawable {
    /** Intrinsic width of the balloon, as in the drawable it replaces. */
    private static final float WIDTH_DP = 45f;
    /** The invisible part that hangs over the parent key; was the old drawable's padding. */
    private static final float BOTTOM_PADDING_DP = 60f;
    /** Corner radius of the body: iOS balloons are rounder than the 5dp keys. */
    private static final float BODY_RADIUS_DP = 10f;
    /** Radius where the body's bottom edge turns into the neck. */
    private static final float BODY_BOTTOM_RADIUS_DP = 6f;
    /** Height of the neck, i.e. the distance from the body to the parent key's top edge. */
    private static final float NECK_HEIGHT_DP = 5f;
    /** Width of the neck: narrower than the body, about a key's width. */
    private static final float NECK_WIDTH_DP = 26f;
    /** Horizontal run of the curve that pulls the body's bottom edge into the neck. */
    private static final float NECK_TAPER_DP = 4f;
    /** The hard 1dp bottom shadow of the whole drawable family. */
    private static final float SHADOW_DP = 1f;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private final float mWidthPx;
    private final float mBottomPaddingPx;
    private final float mBodyRadiusPx;
    private final float mBodyBottomRadiusPx;
    private final float mNeckHeightPx;
    private final float mNeckWidthPx;
    private final float mNeckTaperPx;
    private final float mShadowPx;

    public KeyPreviewBalloonDrawable(final Context context) {
        final float density = context.getResources().getDisplayMetrics().density;
        mWidthPx = WIDTH_DP * density;
        mBottomPaddingPx = BOTTOM_PADDING_DP * density;
        mBodyRadiusPx = BODY_RADIUS_DP * density;
        mBodyBottomRadiusPx = BODY_BOTTOM_RADIUS_DP * density;
        mNeckHeightPx = NECK_HEIGHT_DP * density;
        mNeckWidthPx = NECK_WIDTH_DP * density;
        mNeckTaperPx = NECK_TAPER_DP * density;
        mShadowPx = SHADOW_DP * density;
        mFillPaint.setColor(context.getColor(R.color.ios_key_normal));
        mShadowPaint.setColor(context.getColor(R.color.ios_key_shadow));
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(mWidthPx);
    }

    @Override
    public int getIntrinsicHeight() {
        // The height is imposed by keyPreviewHeight; the drawable has no opinion of its own.
        return -1;
    }

    @Override
    public boolean getPadding(final Rect padding) {
        padding.set(0, 0, 0, Math.round(mBottomPaddingPx));
        return true;
    }

    @Override
    protected void onBoundsChange(final Rect bounds) {
        buildPath(bounds.width(), bounds.height());
    }

    private void buildPath(final float width, final float height) {
        mPath.reset();
        if (width <= 0f || height <= 0f) {
            return;
        }
        // The body occupies the VISIBLE height; the neck then reaches the parent key's top edge.
        final float bodyBottom = Math.max(mBodyRadiusPx * 2f, height - mBottomPaddingPx);
        final float neckBottom = bodyBottom + mNeckHeightPx;
        final float centerX = width / 2f;
        float neckLeft = centerX - mNeckWidthPx / 2f;
        float neckRight = centerX + mNeckWidthPx / 2f;
        // Keep the taper curves inside the body's bottom corners, however narrow the balloon is.
        final float maxTaperRight = width - mBodyBottomRadiusPx;
        if (neckRight + mNeckTaperPx > maxTaperRight) {
            neckRight = Math.max(centerX, maxTaperRight - mNeckTaperPx);
            neckLeft = width - neckRight;
        }
        final float r = mBodyRadiusPx;
        final float rb = mBodyBottomRadiusPx;
        mPath.moveTo(r, 0f);
        mPath.lineTo(width - r, 0f);
        mPath.quadTo(width, 0f, width, r);
        mPath.lineTo(width, bodyBottom - rb);
        mPath.quadTo(width, bodyBottom, width - rb, bodyBottom);
        mPath.lineTo(neckRight + mNeckTaperPx, bodyBottom);
        mPath.quadTo(neckRight, bodyBottom, neckRight, neckBottom);
        mPath.lineTo(neckLeft, neckBottom);
        mPath.quadTo(neckLeft, bodyBottom, neckLeft - mNeckTaperPx, bodyBottom);
        mPath.lineTo(rb, bodyBottom);
        mPath.quadTo(0f, bodyBottom, 0f, bodyBottom - rb);
        mPath.lineTo(0f, r);
        mPath.quadTo(0f, 0f, r, 0f);
        mPath.close();
    }

    @Override
    public void draw(final Canvas canvas) {
        if (mPath.isEmpty()) {
            return;
        }
        // The same hard 1dp shadow the keys carry: the shape once, offset down, then the fill.
        canvas.translate(0f, mShadowPx);
        canvas.drawPath(mPath, mShadowPaint);
        canvas.translate(0f, -mShadowPx);
        canvas.drawPath(mPath, mFillPaint);
    }

    @Override
    public void setAlpha(final int alpha) {
        mFillPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(final ColorFilter colorFilter) {
        // Honours KeyPreviewView.setColor, which tints the balloon for the custom-colour theme.
        mFillPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     * Test seam for the geometry contract: the body must end where the visible part ends, so the
     * neck's bottom is exactly {@code height − bottomPadding + neckHeight}. Kept as a static pure
     * function so the JVM suite can check it without android.graphics.
     */
    public static float neckBottomOf(final float height, final float bottomPadding,
            final float neckHeight) {
        return height - bottomPadding + neckHeight;
    }
}

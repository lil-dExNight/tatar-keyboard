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
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import rkr.simplekeyboard.inputmethod.R;

/**
 * The key preview balloon as an iOS-style droplet instead of a rectangle.
 *
 * <p>The preview bounds are {@code keyPreviewHeight} tall, and the background's bottom padding
 * marks the part that must stay invisible, the part that hangs over the parent key (see
 * {@link KeyPreviewDrawParams#setGeometry}). A layer-list shape would fill the whole bounds and
 * paint across two key rows. Here a rounded body fills the visible height
 * ({@code height − bottomPadding}), a short neck of {@link #NECK_HEIGHT_DP} runs from the body's
 * bottom edge down to the parent key's top edge, and everything below is transparent.</p>
 *
 * <p>{@code KeyPreviewChoreographer.placeKeyPreview} clamps the balloon inside the key grid's
 * side edges; when the body shifts, the neck mirrors the shift and stays centered on the parent
 * key (the iOS edge behavior). The anchor offset arrives via {@link #setNeckOffset} before
 * placement.</p>
 *
 * <p>The path is rebuilt only on a bounds change or a neck-offset change, never per frame;
 * {@link #draw} touches nothing but the cached path and two paints.</p>
 */
public final class KeyPreviewBalloonDrawable extends Drawable {
    /** Intrinsic width of the balloon. */
    private static final float WIDTH_DP = 45f;
    /** The invisible part that hangs over the parent key. */
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

    // Horizontal offset of the neck's anchor from the balloon's center, in pixels: the clamp
    // shift of the balloon body, negated, so the neck stays over the parent key.
    private float mNeckOffsetPx;
    // The bounds the cached path was built for, so an offset change can rebuild without a
    // bounds change.
    private int mPathWidth;
    private int mPathHeight;

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
        // The fill follows the letter-key surface of the active theme (both themes declare it at
        // the theme root, so the balloon always matches the key it rises from); the shadow stays
        // the shared neutral translucent of the key drawables.
        final TypedArray themeAttr = context.getTheme().obtainStyledAttributes(
                new int[] { R.attr.keyNormalBackgroundColor });
        mFillPaint.setColor(themeAttr.getColor(0, context.getColor(R.color.ios_key_normal)));
        themeAttr.recycle();
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
        mPathWidth = bounds.width();
        mPathHeight = bounds.height();
        buildPath(mPathWidth, mPathHeight);
    }

    /**
     * Moves the neck's anchor horizontally, keeping it centered on the parent key when the
     * balloon is clamped at the key grid's edge. Rebuilds the path only when the offset
     * changes; an offset change before the first layout is picked up by {@link #onBoundsChange}.
     */
    public void setNeckOffset(final float offsetPx) {
        if (mNeckOffsetPx == offsetPx) {
            return;
        }
        mNeckOffsetPx = offsetPx;
        if (mPathWidth > 0 && mPathHeight > 0) {
            buildPath(mPathWidth, mPathHeight);
            invalidateSelf();
        }
    }

    private void buildPath(final float width, final float height) {
        mPath.reset();
        if (width <= 0f || height <= 0f) {
            return;
        }
        // The body occupies the VISIBLE height; the neck then reaches the parent key's top edge.
        final float bodyBottom = Math.max(mBodyRadiusPx * 2f, height - mBottomPaddingPx);
        final float neckBottom = bodyBottom + mNeckHeightPx;
        // The anchor stays inside the body by at least a taper run, so the taper never crosses
        // the balloon's edge however large the clamp shift is.
        final float centerX = Math.max(mNeckTaperPx,
                Math.min(width - mNeckTaperPx, width / 2f + mNeckOffsetPx));
        // The neck narrows when the balloon is narrow, so both taper curves stay clear of the
        // body's bottom corners; a slid neck may lean into the near corner.
        final float neckHalf = Math.max(0f, Math.min(mNeckWidthPx / 2f,
                Math.min(width / 2f - mBodyBottomRadiusPx - mNeckTaperPx,
                        Math.min(centerX, width - centerX) - mNeckTaperPx)));
        final float neckLeft = centerX - neckHalf;
        final float neckRight = centerX + neckHalf;
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
        // Honors KeyPreviewView.setColor, which tints the balloon for the custom-color theme.
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

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
 * The pause-learning badge on the spacebar: two small bars (a pause mark) drawn right after the
 * language name while pause learning is on. Rectangles, not a text glyph: a ⏸ code point is
 * missing from some device fonts, and the bars need no typeface.
 *
 * Pure geometry plus the draw plan, Android-free so the JVM tests pin the fit rule: the badge
 * never crowds the language name out.
 */
public final class SpacebarPauseBadge {
    /** Draw the language name alone (learning not paused, or the name won the fit). */
    public static final int PLAN_NAME_ONLY = 0;
    /** Draw the language name with the badge after it, as one centered group. */
    public static final int PLAN_NAME_AND_BADGE = 1;
    /** Draw the badge alone, centered: the name fit not even without the badge. */
    public static final int PLAN_BADGE_ONLY = 2;

    private SpacebarPauseBadge() {
        // This utility class is not publicly instantiable.
    }

    /** One bar's width, as a share of the spacebar text size. */
    public static float barWidth(final float textSizePx) {
        return textSizePx * 0.13f;
    }

    /** The gap between the two bars. */
    public static float barGap(final float textSizePx) {
        return textSizePx * 0.15f;
    }

    /** The whole badge's width: two bars and the gap between them. */
    public static float badgeWidth(final float textSizePx) {
        return 2f * barWidth(textSizePx) + barGap(textSizePx);
    }

    /** The gap between the language name and the badge. */
    public static float nameGap(final float textSizePx) {
        return textSizePx * 0.35f;
    }

    /** What the badge takes from the name's fit width: the badge and its gap. */
    public static float reservedWidth(final float textSizePx) {
        return badgeWidth(textSizePx) + nameGap(textSizePx);
    }

    /** A bar's height: under the ascent-to-descent span, so the mark reads as an accessory. */
    public static float badgeHeight(final float textHeightPx) {
        return textHeightPx * 0.62f;
    }

    /**
     * What the spacebar draws while pause learning is on. The badge yields to the name: a name
     * that fits alone but not with the badge is drawn alone, so the badge can never push the
     * name out. A name that fits not even alone (the cache then keeps it empty) is replaced by
     * the badge, so a paused keyboard always shows the mark.
     */
    public static int drawPlan(final boolean learningPaused, final boolean nameEmpty,
            final boolean nameFitsWithBadge) {
        if (!learningPaused) {
            return PLAN_NAME_ONLY;
        }
        if (nameEmpty) {
            return PLAN_BADGE_ONLY;
        }
        return nameFitsWithBadge ? PLAN_NAME_AND_BADGE : PLAN_NAME_ONLY;
    }

    /**
     * The name's center x (the paint is Align.CENTER) when name and badge draw as one centered
     * group: the group is the name plus the reservation, so the name's center shifts left by
     * half the reservation.
     */
    public static float groupedNameCenterX(final float keyWidthPx, final float reservationPx) {
        return (keyWidthPx - reservationPx) / 2f;
    }

    /** The badge's left edge: the name's right edge plus the gap. */
    public static float badgeLeftX(final float nameCenterXPx, final float scaledNameWidthPx,
            final float nameGapPx) {
        return nameCenterXPx + scaledNameWidthPx / 2f + nameGapPx;
    }

    /** The badge's left edge when it replaces the name: centered on the key. */
    public static float centeredBadgeLeftX(final float keyWidthPx, final float badgeWidthPx) {
        return (keyWidthPx - badgeWidthPx) / 2f;
    }

    /** A bar's top edge: the badge centers on the text line, which sits at the key's middle. */
    public static float barTop(final float keyHeightPx, final float textHeightPx) {
        return (keyHeightPx - badgeHeight(textHeightPx)) / 2f;
    }
}

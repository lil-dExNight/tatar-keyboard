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

package rkr.simplekeyboard.inputmethod.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause-learning badge on the spacebar: two bars after the language name while pause
 * learning is on. The fit rule is pinned: the badge never crowds the language name out — a name
 * that fits alone but not with the badge is drawn alone — and a name that fits not even alone is
 * replaced by the badge, so the pause is always visible. The view half is checked by source
 * contract (no Robolectric here): the draw is gated on the cached pause-learning state (read
 * once at attach, refreshed by a preference listener — never read per frame) and the badge
 * shares the language name's themed paint, so both shipped themes color it correctly.
 */
class SpacebarPauseBadgeTest {

    // --- The draw plan (pure) -----------------------------------------------------------------

    @Test
    fun learningNotPausedDrawsTheNameAlone() {
        assertEquals(
            SpacebarPauseBadge.PLAN_NAME_ONLY,
            SpacebarPauseBadge.drawPlan(false, false, true),
        )
        assertEquals(
            SpacebarPauseBadge.PLAN_NAME_ONLY,
            SpacebarPauseBadge.drawPlan(false, false, false),
        )
        // No badge without the pause, even when the name is empty.
        assertEquals(
            SpacebarPauseBadge.PLAN_NAME_ONLY,
            SpacebarPauseBadge.drawPlan(false, true, false),
        )
    }

    @Test
    fun aPausedKeyboardWithRoomDrawsTheNameAndTheBadge() {
        assertEquals(
            SpacebarPauseBadge.PLAN_NAME_AND_BADGE,
            SpacebarPauseBadge.drawPlan(true, false, true),
        )
    }

    /** The pin: the badge yields, the name stays. The badge never pushes the name to empty. */
    @Test
    fun theBadgeNeverCrowdsTheLanguageNameOut() {
        assertEquals(
            SpacebarPauseBadge.PLAN_NAME_ONLY,
            SpacebarPauseBadge.drawPlan(true, false, false),
        )
    }

    @Test
    fun aNameThatFitsNotEvenAloneIsReplacedByTheBadge() {
        assertEquals(
            SpacebarPauseBadge.PLAN_BADGE_ONLY,
            SpacebarPauseBadge.drawPlan(true, true, false),
        )
    }

    // --- The geometry (pure) ------------------------------------------------------------------

    @Test
    fun theReservationIsTheBadgeAndItsGap() {
        val size = 40f
        assertEquals(
            SpacebarPauseBadge.badgeWidth(size) + SpacebarPauseBadge.nameGap(size),
            SpacebarPauseBadge.reservedWidth(size),
            0f,
        )
        assertTrue(SpacebarPauseBadge.badgeWidth(size) > 0f)
        assertTrue(SpacebarPauseBadge.nameGap(size) > 0f)
    }

    @Test
    fun theGroupedNameAndBadgeAreCenteredAsOneGroup() {
        // The group is the name plus the reservation, centered on the key: the name's center
        // shifts left by half the reservation, and the badge's right edge is the group's right
        // edge (as far from the key's right edge as the group's left edge is from its left).
        val key = 400f
        val size = 40f
        val reservation = SpacebarPauseBadge.reservedWidth(size)
        val nameCenter = SpacebarPauseBadge.groupedNameCenterX(key, reservation)
        assertEquals((key - reservation) / 2f, nameCenter, 0f)
        val nameWidth = 200f
        val badgeLeft = SpacebarPauseBadge.badgeLeftX(nameCenter, nameWidth,
                SpacebarPauseBadge.nameGap(size))
        val groupLeft = nameCenter - nameWidth / 2f
        assertEquals(key - groupLeft, badgeLeft + SpacebarPauseBadge.badgeWidth(size), 0.001f)
    }

    @Test
    fun theBadgeReplacesTheNameCentered() {
        val key = 400f
        val size = 40f
        val left = SpacebarPauseBadge.centeredBadgeLeftX(key, SpacebarPauseBadge.badgeWidth(size))
        assertEquals(key - left, left + SpacebarPauseBadge.badgeWidth(size), 0.001f)
    }

    @Test
    fun theBarsCenterOnTheTextLineAndStayUnderIt() {
        val keyHeight = 100f
        val textHeight = 30f
        val top = SpacebarPauseBadge.barTop(keyHeight, textHeight)
        val height = SpacebarPauseBadge.badgeHeight(textHeight)
        // Centered on the key's middle, where the text line sits.
        assertEquals(keyHeight / 2f, top + height / 2f, 0.001f)
        assertTrue(height < textHeight)
    }

    // --- The view half (source contracts) ------------------------------------------------------

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun source(path: String) = File(sourceRoot(), path).readText()

    private val view by lazy {
        source("java/rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java")
    }

    private val drawBody by lazy {
        view.substringAfter("private void drawLanguageOnSpacebar(")
    }

    @Test
    fun theDrawConditionIsThePauseLearningPref() {
        assertTrue(
            "the badge must be gated on the cached pause-learning state",
            drawBody.contains("final boolean learningPaused = mLearningPaused;"),
        )
        assertTrue(
            "the plan must come from SpacebarPauseBadge.drawPlan",
            drawBody.contains("SpacebarPauseBadge.drawPlan("),
        )
    }

    /**
     * onDraw runs per frame, so the spacebar draw path (the key-top visuals hook and everything
     * it calls) must not touch the preferences: the pause state is read once at attach and
     * refreshed by a listener; the draw reads only the cached field.
     */
    @Test
    fun theDrawPathReadsNoPreferences() {
        val drawPath = view.substringAfter("protected void onDrawKeyTopVisuals(")
        for (forbidden in listOf("Settings.read", "getDeviceSharedPreferences", "SharedPreferences")) {
            assertFalse(
                "the spacebar draw path must not contain $forbidden",
                drawPath.contains(forbidden),
            )
        }
    }

    /**
     * The badge must still follow the toggle without a settings push into the view: the cache is
     * read at attach and refreshed by a field-held listener (SharedPreferencesImpl keeps listeners
     * in a WeakHashMap), which also repaints.
     */
    @Test
    fun theCachedPauseStateFollowsTheToggle() {
        val attachBody = view.substringAfter("protected void onAttachedToWindow()")
            .substringBefore("protected void onDetachedFromWindow()")
        val detachBody = view.substringAfter("protected void onDetachedFromWindow()")
            .substringBefore("public MoreKeysPanel showMoreKeysKeyboard(")
        val listenerBody = view.substringAfter("private void onLearningPauseMaybeChanged(")
            .substringBefore("public MoreKeysPanel showMoreKeysKeyboard(")

        assertTrue(
            "the pause state must be read once at attach",
            attachBody.contains("mLearningPaused = Settings.readIncognitoModeEnabled("),
        )
        assertTrue(
            "attach must register the listener",
            attachBody.contains("registerOnSharedPreferenceChangeListener(mLearningPauseListener)"),
        )
        assertTrue(
            "detach must unregister the listener",
            detachBody.contains("unregisterOnSharedPreferenceChangeListener(mLearningPauseListener)"),
        )
        assertTrue(
            "the listener must be held in a field, not an anonymous lambda",
            view.contains(
                "private final SharedPreferences.OnSharedPreferenceChangeListener mLearningPauseListener",
            ),
        )
        assertTrue(
            "the listener must refresh the cache and repaint",
            listenerBody.contains("mLearningPaused = Settings.readIncognitoModeEnabled(")
                    && listenerBody.contains("invalidate()"),
        )
    }

    /**
     * Both shipped themes color the badge: the bars draw with the language name's paint (its
     * color set once from the themed `languageOnSpacebarTextColor`), so no hardcoded color may
     * appear after the gate, and both theme files must keep the attr.
     */
    @Test
    fun theBadgeTracksBothThemesThroughTheNamePaint() {
        val afterGate = drawBody.substringAfter("final boolean learningPaused =")
        assertFalse(
            "the badge must not set a color of its own",
            afterGate.contains("paint.setColor("),
        )
        assertTrue(
            "the name paint must carry the themed color before the badge block",
            drawBody.substringBefore("final boolean learningPaused =")
                .contains("paint.setColor(mLanguageOnSpacebarTextColor);"),
        )
        for (theme in listOf("values/themes-tatar.xml", "values/themes-dynamic.xml")) {
            assertTrue(
                "$theme must keep languageOnSpacebarTextColor",
                source("res/$theme").contains("name=\"languageOnSpacebarTextColor\""),
            )
        }
    }

    @Test
    fun theNameIsFittedAgainstTheBadgeReservation() {
        assertTrue(
            "the badge's share must come off the name's fit width",
            drawBody.contains("fitsTextIntoWidth(width - reservation, language, paint)"),
        )
        // When the fit fails the badge yields and the unbadged scale goes back on the paint.
        assertTrue(
            "the badge-yields branch must restore the cached scale",
            drawBody.contains("paint.setTextScaleX(mLanguageOnSpacebarTextScaleX);"),
        )
    }

    @Test
    fun theBarsAreDrawnAsFilledRectsWithTheNamePaint() {
        val badgeBlock = drawBody.substringAfter("if (plan != SpacebarPauseBadge.PLAN_NAME_ONLY)")
        assertTrue(badgeBlock.contains("paint.setStyle(Paint.Style.FILL);"))
        // Two bars: two drawRect calls.
        assertEquals(2, Regex("drawRect\\(").findAll(badgeBlock).count())
    }
}

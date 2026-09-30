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
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The `?123` key must switch the layout from anywhere on the key, not only from its center.
 *
 * Pressing a modifier key (`?123`, shift) switches the layout at once
 * and arms `SWITCH_STATE_MOMENTARY_ALPHA_AND_SYMBOL`, which springs back on release as soon as
 * `PointerTracker` decides the finger left the key. That decision used to be made by
 * `keyHysteresisDistance` alone, measured *from the key edge*, so a press landing at the edge left
 * the key after 5dp of movement while a press in the center needed a whole half key width, so
 * the switch bounced back unless the press hit the middle.
 *
 * All coordinates below are measured, not invented: they come from the AVD `tatar_e5_test`
 * (1080x2280, density 440, 2.75 px/dp), where the `?123` hitbox is x in [0, 162), y in [2008, 2150)
 * and the switch was observed to bounce back at exactly 15px past the right edge and 14px past the
 * top edge.
 *
 * `PointerTracker` and `Key` cannot be loaded in a JVM unit test (their static initializers and
 * constructors need a live `Resources`), but [KeyDetector] is free of Android imports. The gesture
 * replay below therefore drives the real [KeyDetector] and mirrors only the two lines of
 * `Key.squaredDistanceToHitboxEdge` and the surrounding branch of
 * `PointerTracker.isMajorEnoughMoveToBeOnNewKey`.
 */
class SlidingModifierSlopTest {

    private data class Point(val x: Int, val y: Int)

    /** Half-open hitbox, exactly as `Key.mHitbox`. */
    private data class Hitbox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        /** Mirrors `Key.squaredDistanceToHitboxEdge`; right and bottom are exclusive there too. */
        fun squaredDistanceToEdge(p: Point): Int {
            val edgeX = if (p.x < left) left else minOf(p.x, right - 1)
            val edgeY = if (p.y < top) top else minOf(p.y, bottom - 1)
            val dx = p.x - edgeX
            val dy = p.y - edgeY
            return dx * dx + dy * dy
        }

        fun contains(p: Point) = p.x in left until right && p.y in top until bottom
    }

    private companion object {
        /** AVD `tatar_e5_test`: 1080x2280 at density 440, i.e. 2.75 px per dp. */
        private const val PX_PER_DP = 440.0 / 160.0

        /** `res/values/config.xml` phone value, unchanged by this fix. */
        private const val HYSTERESIS_DP = 5.0

        /** Measured hitbox of `?123` on the letters keyboard (and of `АБВ` on the symbols one). */
        private val SYMBOL_KEY = Hitbox(left = 0, top = 2008, right = 162, bottom = 2150)

        /** Measured hitbox of the `,` key immediately to its right, and its center. */
        private val COMMA_KEY = Hitbox(left = 162, top = 2008, right = 270, bottom = 2150)

        /** An ordinary letter key from the same keyboard, used as the untouched control. */
        private val LETTER_KEY = Hitbox(left = 189, top = 1445, right = 351, bottom = 1585)

        private fun dpToPx(dp: Double) = (dp * PX_PER_DP).toFloat()
    }

    /**
     * The branch of `PointerTracker.isMajorEnoughMoveToBeOnNewKey` this fix touches, for a pointer
     * that is not yet dragging (`mIsInDraggingFinger == false`), i.e. the very first departure --
     * the one that arms the momentary layout switch.
     */
    private fun leavesKey(
        detector: KeyDetector,
        key: Hitbox,
        down: Point,
        current: Point,
        downOnModifierKey: Boolean,
    ): Boolean {
        if (key.contains(current)) return false // newKey == curKey
        if (downOnModifierKey &&
            !detector.isBeyondSlidingModifierSlop(down.x, down.y, current.x, current.y)
        ) {
            return false
        }
        return key.squaredDistanceToEdge(current) >=
            detector.getKeyHysteresisDistanceSquared(false /* isSlidingFromModifier */)
    }

    private fun detector(slidingModifierSlopDp: Double) = KeyDetector(
        dpToPx(HYSTERESIS_DP),
        dpToPx(8.0) /* keyHysteresisDistanceForSlidingModifier */,
        dpToPx(slidingModifierSlopDp),
    )

    /** The slop as it is actually shipped, read from the resource the app builds against. */
    private fun shippedSlopDp(): Double {
        val dir = listOf(File("src/main/res/values"), File("app/src/main/res/values"))
            .firstOrNull(File::isDirectory)
            ?: error("cannot locate res/values from ${File(".").absolutePath}")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder().parse(File(dir, "config-common.xml"))
        val dimens = doc.getElementsByTagName("dimen")
        for (i in 0 until dimens.length) {
            val el = dimens.item(i) as Element
            if (el.getAttribute("name") == "config_sliding_modifier_slop") {
                val raw = el.textContent.trim()
                assertTrue("config_sliding_modifier_slop must be in dp, got $raw", raw.endsWith("dp"))
                return raw.removeSuffix("dp").toDouble()
            }
        }
        error("config_sliding_modifier_slop is not defined in config-common.xml")
    }

    // ---------------------------------------------------------------- the defect, as it was

    /**
     * Characterizes the behavior this fix removes. With no slop -- the state of the fork before
     * the fix -- a press 6px inside the right edge of `?123` leaves the key after the 15px of
     * movement that was measured on the emulator, which is what springs the layout back.
     */
    @Test
    fun withoutSlopAnEdgePressLeavesTheKeyOnTremor() {
        val det = detector(0.0)
        val down = Point(155, 2068) // 6px inside the right edge
        assertTrue(
            "pre-fix behaviour: 15px past the edge already counts as leaving the key",
            leavesKey(det, SYMBOL_KEY, down, Point(176, 2068), downOnModifierKey = true),
        )
        assertEquals(
            "and that is only 21px = 7.6dp of finger travel",
            21,
            176 - down.x,
        )
    }

    // ---------------------------------------------------------------- the fix

    @Test
    fun edgePressSurvivesTremorInEveryDirection() {
        val det = detector(shippedSlopDp())
        // Right edge: the exact point at which the emulator used to bounce back.
        assertFalse(
            leavesKey(det, SYMBOL_KEY, Point(155, 2068), Point(176, 2068), downOnModifierKey = true),
        )
        // Top edge: 4px below it, moving 14px above it.
        assertFalse(
            leavesKey(det, SYMBOL_KEY, Point(80, 2012), Point(80, 1994), downOnModifierKey = true),
        )
        // Top-left corner and the bottom edge of the same key.
        assertFalse(
            leavesKey(det, SYMBOL_KEY, Point(2, 2012), Point(2, 1992), downOnModifierKey = true),
        )
        assertFalse(
            leavesKey(det, SYMBOL_KEY, Point(80, 2145), Point(95, 2165), downOnModifierKey = true),
        )
        // A diagonal tremor of the same magnitude is gated too.
        assertFalse(
            leavesKey(det, SYMBOL_KEY, Point(155, 2012), Point(172, 1995), downOnModifierKey = true),
        )
    }

    @Test
    fun centrePressWasNeverAffectedAndStillIsNot() {
        val det = detector(shippedSlopDp())
        val centre = Point(80, 2068)
        assertFalse(leavesKey(det, SYMBOL_KEY, centre, Point(101, 2068), downOnModifierKey = true))
        // A press in the center has always needed 96px to break out; that is unchanged.
        assertTrue(leavesKey(det, SYMBOL_KEY, centre, Point(176, 2068), downOnModifierKey = true))
    }

    // ---------------------------------------------------------------- what must keep working

    @Test
    fun deliberateSlideStillLeavesTheModifierKey() {
        val det = detector(shippedSlopDp())
        // Press ?123 in the center, slide up to the "5" key: the momentary switch must still arm.
        assertTrue(
            leavesKey(det, SYMBOL_KEY, Point(80, 2068), Point(484, 1558), downOnModifierKey = true),
        )
        // The shortest deliberate slide there is: from the right edge of ?123 to the center of the
        // neighboring "," key. This is the case the slop must not eat.
        val commaCentre = Point(
            (COMMA_KEY.left + COMMA_KEY.right) / 2,
            (COMMA_KEY.top + COMMA_KEY.bottom) / 2,
        )
        assertTrue(
            leavesKey(det, SYMBOL_KEY, Point(155, 2068), commaCentre, downOnModifierKey = true),
        )
        assertTrue(
            "the shortest deliberate slide must stay clear of the slop",
            shippedSlopDp() < (commaCentre.x - 155) / PX_PER_DP,
        )
    }

    @Test
    fun ordinaryKeysAreUntouched() {
        val det = detector(shippedSlopDp())
        // The same 15px-past-the-edge movement on a letter key still changes the key, exactly as
        // before: the slop applies only to presses that started on a modifier.
        val down = Point(345, 1515) // 6px inside the right edge of the letter key
        assertTrue(
            leavesKey(det, LETTER_KEY, down, Point(366, 1515), downOnModifierKey = false),
        )
        assertFalse(
            leavesKey(det, LETTER_KEY, down, Point(360, 1515), downOnModifierKey = false),
        )
    }

    /**
     * The second half of the defect, and the reason the slop alone did nothing at first.
     *
     * `MainKeyboardView` is bottom aligned, so touch coordinates are relative to a view whose
     * origin moves when the keyboard height changes. The Tatar alphabet keyboard carries a fifth
     * row for ә ө ү җ ң һ and measures 728px against the symbols keyboard's 667px (logged on the
     * AVD), so the instant `?123` is pressed every following coordinate is 61px smaller. The
     * touch-down point recorded before the switch therefore has to be moved into the new
     * keyboard's space, or the slop is swamped by 61px of pure bookkeeping. Upstream AOSP never
     * meets this: there the alphabet and symbols keyboards both have four rows.
     */
    @Test
    fun aLayoutSwitchThatChangesTheKeyboardHeightIsCompensated() {
        val alphabetHeight = 728
        val symbolsHeight = 667
        val det = detector(shippedSlopDp())
        val downInAlphabetSpace = Point(155, 648)
        val downInSymbolsSpace = Point(
            downInAlphabetSpace.x,
            downInAlphabetSpace.y + symbolsHeight - alphabetHeight,
        )
        val tremor = Point(176, 587) // the same finger position, 21px later, in symbols space
        assertTrue(
            "uncompensated, 21px of tremor reads as 65px of travel and opens the gate",
            det.isBeyondSlidingModifierSlop(
                downInAlphabetSpace.x, downInAlphabetSpace.y, tremor.x, tremor.y,
            ),
        )
        assertFalse(
            "compensated, the same gesture is the 21px of tremor it really is",
            det.isBeyondSlidingModifierSlop(
                downInSymbolsSpace.x, downInSymbolsSpace.y, tremor.x, tremor.y,
            ),
        )
    }

    // ---------------------------------------------------------------- what is shipped

    @Test
    fun slopIsWiredIntoTheKeyboardViewStyle() {
        val dir = listOf(File("src/main/res/values"), File("app/src/main/res/values"))
            .firstOrNull(File::isDirectory)
            ?: error("cannot locate res/values from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }

        val attrs = factory.newDocumentBuilder().parse(File(dir, "attrs.xml"))
        val declared = (0 until attrs.getElementsByTagName("attr").length)
            .map { attrs.getElementsByTagName("attr").item(it) as Element }
            .map { it.getAttribute("name") }
        assertTrue("slidingModifierSlop must be declared", declared.contains("slidingModifierSlop"))

        val themes = factory.newDocumentBuilder().parse(File(dir, "themes-common.xml"))
        val items = (0 until themes.getElementsByTagName("item").length)
            .map { themes.getElementsByTagName("item").item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
        assertEquals(
            "@dimen/config_sliding_modifier_slop",
            items["slidingModifierSlop"],
        )
        assertEquals(
            "the AOSP value dropped at fork time must be restored as well",
            "@dimen/config_key_hysteresis_distance_for_sliding_modifier",
            items["keyHysteresisDistanceForSlidingModifier"],
        )
        assertNotNull(items["keyHysteresisDistance"])
    }

    /**
     * The shipped slop must sit between the platform's own tap/drag boundary and the shortest
     * deliberate slide.
     */
    @Test
    fun shippedSlopStaysWithinItsMeasuredBounds() {
        val slop = shippedSlopDp()
        val platformTouchSlopDp = 8.0
        val shortestDeliberateSlideDp = (216 - 155) / PX_PER_DP // ?123 edge -> "," center
        assertTrue("slop $slop dp must exceed the platform touch slop", slop >= platformTouchSlopDp)
        assertTrue(
            "slop $slop dp must stay below the shortest deliberate slide $shortestDeliberateSlideDp dp",
            slop < shortestDeliberateSlideDp,
        )
    }
}

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * `config_key_hysteresis_distance` is how far past the edge of the pressed key a finger must move
 * before the key changes. The fork shipped 5.0dp, which is *below* the platform's own touch slop
 * (`ViewConfiguration.getScaledTouchSlop()`, 8dp) -- below the movement Android itself still calls
 * a tap rather than a drag. A press landing a few pixels inside the edge of a letter therefore
 * typed the neighbor after 5dp of tremor. The value is now the platform touch slop, which is
 * also what HeliBoard ships.
 *
 * The price is that every slide now switches key 3dp later. The tests below bound that price with
 * the geometry the keyboard actually has, all of it measured on the AVD `tatar_e5_test`
 * (1080x2280, density 440, 2.75 px/dp) by tapping and reading back what was typed.
 *
 * As in [SlidingModifierSlopTest], `Key` and `PointerTracker` cannot be loaded in a JVM unit test,
 * so the gesture replay drives the real [KeyDetector] and mirrors only `squaredDistanceToHitboxEdge`
 * and the branch of `PointerTracker.isMajorEnoughMoveToBeOnNewKey` that consumes it.
 */
class KeyHysteresisDistanceTest {

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
        val centre get() = Point((left + right) / 2, (top + bottom) / 2)
    }

    private companion object {
        /** AVD `tatar_e5_test`: 1080x2280 at density 440, i.e. 2.75 px per dp. */
        private const val PX_PER_DP = 440.0 / 160.0

        /** The platform's own tap/drag boundary, `ViewConfiguration.getScaledTouchSlop()`. */
        private const val PLATFORM_TOUCH_SLOP_DP = 8.0

        /**
         * `ц` on the Tatar alphabet keyboard: the second key of the eleven-key row `й ц у к е н г
         * ш щ з х`. Boundaries found by binary search over `adb shell input tap`: x=97 types `й`
         * and x=98 types `ц`, x=195 types `ц` and x=196 types `у`; the row runs from y=1579 to
         * y=1721 inclusive.
         */
        private val TSE_KEY = Hitbox(left = 98, top = 1579, right = 196, bottom = 1722)

        /** `у`, immediately to its right, same size. */
        private val U_KEY = Hitbox(left = 196, top = 1579, right = 295, bottom = 1722)

        /**
         * The narrowest ordinary key on any shipped layout: the third letter row is `8.711%p` per
         * key after a `10.8%p` shift. Measured: the shift ends at x=117 and `я` ends at x=211.
         */
        private const val NARROWEST_ORDINARY_KEY_WIDTH = 211 - 117

        private fun dpToPx(dp: Double) = (dp * PX_PER_DP).toFloat()

        private fun resValues(qualifier: String = ""): File =
            listOf(File("src/main/res/values$qualifier"), File("app/src/main/res/values$qualifier"))
                .firstOrNull(File::isDirectory)
                ?: error("cannot locate res/values$qualifier from ${File(".").absolutePath}")

        /** Reads a `<dimen>` in dp straight out of the resource the app builds against. */
        private fun dimenDp(qualifier: String, file: String, name: String): Double {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder().parse(File(resValues(qualifier), file))
            val dimens = doc.getElementsByTagName("dimen")
            for (i in 0 until dimens.length) {
                val el = dimens.item(i) as Element
                if (el.getAttribute("name") == name) {
                    val raw = el.textContent.trim()
                    assertTrue("$name must be in dp, got $raw", raw.endsWith("dp"))
                    return raw.removeSuffix("dp").toDouble()
                }
            }
            error("$name is not defined in values$qualifier/$file")
        }

        private fun shippedHysteresisDp() =
            dimenDp("", "config.xml", "config_key_hysteresis_distance")

        private fun shippedSlidingModifierHysteresisDp() =
            dimenDp("", "config-common.xml", "config_key_hysteresis_distance_for_sliding_modifier")
    }

    /** The detector as the phone builds it, from the shipped resources. */
    private fun shippedDetector() = KeyDetector(
        dpToPx(shippedHysteresisDp()),
        dpToPx(shippedSlidingModifierHysteresisDp()),
        dpToPx(dimenDp("", "config-common.xml", "config_sliding_modifier_slop")),
    )

    /**
     * The branch of `PointerTracker.isMajorEnoughMoveToBeOnNewKey` a press on an *ordinary* key
     * takes: no sliding-modifier slop, no dragging state, just the hysteresis from the key edge.
     */
    private fun leavesKey(detector: KeyDetector, key: Hitbox, current: Point): Boolean {
        if (key.contains(current)) return false // newKey == curKey
        return key.squaredDistanceToEdge(current) >=
            detector.getKeyHysteresisDistanceSquared(false /* isSlidingFromModifier */)
    }

    /** Pixels of travel to the right from [from] before the key changes. */
    private fun travelToLeaveRightwards(detector: KeyDetector, key: Hitbox, from: Point): Int {
        for (d in 1..400) {
            if (leavesKey(detector, key, Point(from.x + d, from.y))) return d
        }
        error("the key never changes")
    }

    // ---------------------------------------------------------------- the value itself

    @Test
    fun theHysteresisIsAtLeastThePlatformTouchSlop() {
        assertTrue(
            "config_key_hysteresis_distance is ${shippedHysteresisDp()}dp, which is below the " +
                "platform's own $PLATFORM_TOUCH_SLOP_DP dp tap/drag boundary: a movement Android " +
                "still calls a tap already types the neighbouring key",
            shippedHysteresisDp() >= PLATFORM_TOUCH_SLOP_DP,
        )
    }

    /**
     * Both halves of the pair now read the same. AOSP keeps the sliding-modifier one larger than
     * the ordinary one; here the ordinary one has caught up with it, which is what HeliBoard does
     * and is the state this fix intends. Fails loudly if either resource drifts.
     */
    @Test
    fun theTwoHysteresisValuesAreBothThePlatformTouchSlop() {
        assertEquals(8.0, shippedHysteresisDp(), 0.0)
        assertEquals(8.0, shippedSlidingModifierHysteresisDp(), 0.0)
    }

    /**
     * One value for every device. `values-sw600dp/config.xml` used to override the hysteresis with
     * AOSP's 35.0dp -- seven times the phone's, more than half the width of a key on many tablet
     * layouts, and never measured by this project, which has no tablet AVD and does not count
     * tablets among its devices. It is gone, so a tablet now gets the 8.0dp that *is* measured.
     * The test fails if any qualifier declares the dimension again.
     */
    @Test
    fun theHysteresisIsDeclaredExactlyOnceInTheWholeTree() {
        val declaring = resValues().parentFile!!.listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") }
            .flatMap { dir -> dir.listFiles()!!.filter { it.name.endsWith(".xml") } }
            .filter { it.readText().contains("\"config_key_hysteresis_distance\"") }
            .map { "${it.parentFile!!.name}/${it.name}" }
            .sorted()
        assertEquals(listOf("values/config.xml"), declaring)
        assertEquals(8.0, shippedHysteresisDp(), 0.0)
    }

    // ---------------------------------------------------------------- what gets better

    @Test
    fun tremorAtTheEdgeOfALetterNoLongerTypesTheNeighbour() {
        val det = shippedDetector()
        val down = Point(190, 1650) // 6px inside the right edge of "ц"
        // 20px of travel was measured on the emulator as the point at which 5.0dp gave up "ц".
        assertFalse(
            "the movement that used to type \"у\" must now stay on \"ц\"",
            leavesKey(det, TSE_KEY, Point(down.x + 20, down.y)),
        )
        // The same at the top edge, where the row above is a whole other row of letters.
        assertFalse(leavesKey(det, TSE_KEY, Point(147, 1584 - 19)))
        // And in the corner, the worst case for a distance measured from the edge.
        assertFalse(leavesKey(det, TSE_KEY, Point(195 + 14, 1579 - 14)))
    }

    @Test
    fun theEdgeNowNeedsTheSameTravelAsThePlatformCallsADrag() {
        val det = shippedDetector()
        val down = Point(190, 1650)
        val travel = travelToLeaveRightwards(det, TSE_KEY, down)
        // 5px to reach the edge (it is exclusive) plus the 22px hysteresis. The emulator agrees
        // to the pixel: the same gesture flips from "ц" to "у" between 26px and 27px of swipe.
        assertEquals(27, travel)
        assertTrue("that is ${travel / PX_PER_DP} dp", travel / PX_PER_DP >= PLATFORM_TOUCH_SLOP_DP)
    }

    // ---------------------------------------------------------------- what it costs

    /**
     * The price of the higher threshold: every slide switches key later. Bound it -- the key must
     * still change before the finger reaches the middle of the key it is sliding onto, otherwise
     * what is under the finger and what gets typed disagree.
     */
    @Test
    fun aSlideStillSwitchesBeforeTheNeighboursCentre() {
        val det = shippedDetector()
        val from = TSE_KEY.centre
        val travel = travelToLeaveRightwards(det, TSE_KEY, from)
        assertEquals(70, travel) // measured on the emulator: 70px, against 63px at 5.0dp
        val toNeighbourCentre = U_KEY.centre.x - from.x
        assertEquals(98, toNeighbourCentre)
        assertTrue(
            "the switch at ${travel}px must land before the neighbour's centre at " +
                "${toNeighbourCentre}px",
            travel < toNeighbourCentre,
        )
    }

    /**
     * The same bound stated in the general form, for the narrowest key any shipped layout has: the
     * hysteresis is measured from the edge, so it must stay under half a key width.
     */
    @Test
    fun theHysteresisFitsInsideHalfOfTheNarrowestKey() {
        val hysteresisPx = dpToPx(shippedHysteresisDp())
        assertEquals(94, NARROWEST_ORDINARY_KEY_WIDTH)
        assertTrue(
            "hysteresis ${hysteresisPx}px must stay under half of the narrowest key " +
                "(${NARROWEST_ORDINARY_KEY_WIDTH}px)",
            hysteresisPx < NARROWEST_ORDINARY_KEY_WIDTH / 2.0,
        )
    }

    /**
     * The long-press popup picks its key by nearest hitbox with its own slide allowance and
     * constructs [KeyDetector] with no hysteresis at all, so raising the keyboard's hysteresis
     * cannot reach it. Asserted on the real [MoreKeysDetector], not on a mirror of it.
     */
    @Test
    fun theLongPressPopupIsUnaffected() {
        val popup = MoreKeysDetector(dpToPx(16.0))
        assertEquals(0, popup.getKeyHysteresisDistanceSquared(false))
        assertEquals(0, popup.getKeyHysteresisDistanceSquared(true))
        assertTrue(popup.alwaysAllowsKeySelectionByDraggingFinger())
    }
}

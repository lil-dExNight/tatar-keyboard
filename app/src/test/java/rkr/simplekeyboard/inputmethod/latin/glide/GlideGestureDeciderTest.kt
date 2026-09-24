package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GlideGestureDecider]: the glide-vs-tap-vs-long-press decision, the FlorisBoard
 * reference constants (one key width, 0.10 dp/ms, 500 ms window), and the fail-closed cancels.
 * Fixture scale: 100 px keys, 2.75 px/dp (the reference 440 dpi screen), so the velocity
 * threshold is 0.275 px/ms.
 */
class GlideGestureDeciderTest {

    private fun decider() = GlideGestureDecider(
        distanceThresholdPx = 100f,
        velocityThresholdPxPerMs = 0.275f,
        maxDetectTimeMs = 500L,
        slopPx = 25f,
    )

    @Test
    fun aTapNeverArms() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        assertEquals(GlideGestureDecider.State.TRACKING, d.state)
        // Tremor of a tap: a few pixels over a hundred milliseconds.
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1003f, 1002f, 16L))
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(997f, 1001f, 64L))
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1000f, 1000f, 120L))
        d.onUpOrCancel()
        assertEquals(GlideGestureDecider.State.IDLE, d.state)
    }

    @Test
    fun aFastGlideArms() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        // 150 px in 100 ms = 1.5 px/ms, way past both thresholds.
        assertEquals(GlideGestureDecider.State.ARMED, d.onMove(1150f, 1000f, 100L))
        assertTrue(d.isArmed)
    }

    @Test
    fun aStillFingerNeverStartsTheClock() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        // Resting tremor stays within the slop — for minutes if need be: no window runs.
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1005f, 1000f, 200L))
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1010f, 1000f, 600L))
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1000f, 995f, 60_000L))
        assertFalse(d.isArmed)
    }

    @Test
    fun holdThenFastSwipeArmsEvenPastTheOldWindow() {
        // The field report verbatim: a finger rests on the first key ~0.5-1 s, then swipes.
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        d.onMove(1005f, 1002f, 400L) // resting, within the slop
        d.onMove(1010f, 1000f, 700L) // still resting at 700 ms — past the old 500 ms window
        assertEquals(GlideGestureDecider.State.ARMED, d.onMove(1150f, 1000f, 750L))
    }

    @Test
    fun theWindowRunsFromTheFirstMoveBeyondTheSlop() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        // The anchor lands at t=600 (the first sample past the 25 px slop).
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1030f, 1000f, 600L))
        // 500 ms after the anchor the gesture is still undecided...
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1035f, 1000f, 1100L))
        // ...and one sample past it the touch is rejected: a slow drift is never a glide.
        assertEquals(GlideGestureDecider.State.REJECTED, d.onMove(1035f, 1000f, 1101L))
    }

    @Test
    fun theVelocityIsMeasuredFromTheAnchor() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        d.onMove(1030f, 1000f, 300L) // 30 px > slop: anchored at t=300
        // 110 px by t=700: 110/400 = 0.275 px/ms from the anchor — NOT strictly above the
        // threshold: past the distance threshold but too slow. (The old from-down semantics
        // would have REJECTED the touch at t=500 already — the window runs from the anchor.)
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1110f, 1000f, 700L))
        // A fast continuation arms: 150 px from down over 50 ms from the anchor.
        val e = decider()
        e.onDown(1000f, 1000f, 0L, eligible = true)
        e.onMove(1030f, 1000f, 300L)
        assertEquals(GlideGestureDecider.State.ARMED, e.onMove(1150f, 1000f, 350L))
    }

    @Test
    fun theSlopBoundaryIsStrict() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        // Exactly the slop: no anchor, no clock (the boundary is a strict >).
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1025f, 1000f, 900L))
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1025f, 1000f, 2000L))
        // One pixel past it: anchored, the window starts there.
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1026f, 1000f, 2100L))
        assertEquals(GlideGestureDecider.State.REJECTED, d.onMove(1030f, 1000f, 2700L))
    }

    @Test
    fun anIneligibleStartNeverArms() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = false)
        assertEquals(GlideGestureDecider.State.REJECTED, d.state)
        // Even a fast, far, immediate move.
        assertEquals(GlideGestureDecider.State.REJECTED, d.onMove(1500f, 1000f, 50L))
    }

    @Test
    fun distanceAloneIsNotEnough_velocityIsRequired() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        // Anchored at t=100 (30 px > 25 px slop), then 110 px by t=500: 110/400 = 0.275 px/ms —
        // NOT strictly above the threshold: past the distance threshold but too slow.
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1030f, 1000f, 100L))
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1110f, 1000f, 500L))
        // Still tracking: the window has not closed yet (400 < 500 ms from the anchor).
        assertFalse(d.isArmed)
    }

    @Test
    fun velocityAloneIsNotEnough_distanceIsRequired() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        // 50 px in 20 ms = 2.5 px/ms but below the one-key distance.
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1050f, 1000f, 20L))
        // A continued fast move past the key width arms.
        assertEquals(GlideGestureDecider.State.ARMED, d.onMove(1150f, 1000f, 80L))
    }

    @Test
    fun thresholdsAreStrict() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        // Exactly the distance threshold does not arm.
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1100f, 1000f, 50L))
    }

    @Test
    fun armingIsStickyWithinTheTouch() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        d.onMove(1150f, 1000f, 100L)
        // Slowing down, stopping, even waiting past the window: armed stays armed.
        assertEquals(GlideGestureDecider.State.ARMED, d.onMove(1151f, 1000f, 800L))
        assertEquals(GlideGestureDecider.State.ARMED, d.onMove(1150f, 1000f, 2000L))
    }

    @Test
    fun cancelGlideDropsArmedToRejected() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        d.onMove(1150f, 1000f, 100L)
        assertTrue(d.isArmed)
        d.cancelGlide()
        assertEquals(GlideGestureDecider.State.REJECTED, d.state)
        assertEquals(GlideGestureDecider.State.REJECTED, d.onMove(1300f, 1000f, 150L))
    }

    @Test
    fun cancelGlideDropsTrackingToRejected() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        d.cancelGlide()
        assertEquals(GlideGestureDecider.State.REJECTED, d.state)
        assertEquals(GlideGestureDecider.State.REJECTED, d.onMove(1300f, 1000f, 50L))
    }

    @Test
    fun aNewDownResetsTheMachine() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        d.onMove(1150f, 1000f, 100L)
        d.onUpOrCancel()
        assertEquals(GlideGestureDecider.State.IDLE, d.state)
        d.onDown(2000f, 500f, 10_000L, eligible = true)
        assertEquals(GlideGestureDecider.State.TRACKING, d.state)
        // The distance is measured from the NEW down point.
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(2010f, 500f, 10_100L))
    }

    @Test
    fun anImmediateSwipeArmsExactlyAsBeforeTheAnchor() {
        // The P7-2 behavior on an immediately-swiped gesture is unchanged: the first move sample
        // crosses the slop at once and the arm lands on the same sample it always did.
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1050f, 1000f, 20L))
        assertEquals(GlideGestureDecider.State.ARMED, d.onMove(1150f, 1000f, 80L))
    }

    @Test
    fun theReferenceConstantsArePinned() {
        assertEquals(500L, GlideGestureDecider.DEFAULT_MAX_DETECT_TIME_MS)
        assertEquals(0.10f, GlideGestureDecider.VELOCITY_THRESHOLD_DP_PER_MS, 0.0f)
        // The first-move anchor slop: a quarter of a key width (≈ 8.9 dp on the reference phone).
        assertEquals(0.25f, GlideGestureDecider.DEFAULT_SLOP_KEY_WIDTH_FRACTION, 0.0f)
    }
}

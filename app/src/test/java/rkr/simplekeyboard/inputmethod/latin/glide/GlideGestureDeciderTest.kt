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
    fun aSlowLongPressIsRejectedAfterTheWindow() {
        val d = decider()
        d.onDown(1000f, 1000f, 0L, eligible = true)
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1005f, 1000f, 200L))
        // Past the 500 ms window without reaching the thresholds: never a glide.
        assertEquals(GlideGestureDecider.State.REJECTED, d.onMove(1010f, 1000f, 600L))
        // And a late fast move cannot revive it.
        assertEquals(GlideGestureDecider.State.REJECTED, d.onMove(1300f, 1000f, 650L))
        assertFalse(d.isArmed)
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
        // 110 px in 420 ms = 0.262 px/ms < 0.275 — past the distance threshold but too slow.
        assertEquals(GlideGestureDecider.State.TRACKING, d.onMove(1110f, 1000f, 420L))
        // Still tracking: the window has not closed yet (420 < 500).
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
    fun theReferenceConstantsArePinned() {
        assertEquals(500L, GlideGestureDecider.DEFAULT_MAX_DETECT_TIME_MS)
        assertEquals(0.10f, GlideGestureDecider.VELOCITY_THRESHOLD_DP_PER_MS, 0.0f)
    }
}

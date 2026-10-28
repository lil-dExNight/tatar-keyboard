package rkr.simplekeyboard.inputmethod.latin.glide

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic arm-rate harness for [GlideGestureDecider]: generated touch traces are replayed
 * through the decider's real API (the onDown / onMove / onUpOrCancel sequence PointerTracker
 * performs) and the arm verdicts are counted over a (distance x velocity x window) threshold
 * grid around the shipped constants. Populations: tap-with-tremor (must never arm), the fast
 * tap with a slip past one key width (the false-arm class), deliberate glide onsets (arm floor
 * in [TRUE_ONSET_ARM_FLOOR]), and slow two-key drags. The printout carries the retune rule and
 * the shipped-point rates are pinned as the baseline.
 */
class GlideDeciderSyntheticTest {

    @Test
    fun tapWithTremorNeverArmsAtTheShippedPoint() {
        assertEquals(0.0, GRID.shipped.tremorArm, 0.0)
    }

    @Test
    fun tapWithTremorNeverArmsAnywhereOnTheGrid() {
        // Sub-slop jitter never sets the first-move anchor, so no grid point can arm it.
        for (point in GRID.points) {
            assertEquals(
                "tremor armed at d=${point.distanceKeyWidths}kw " +
                    "v=${point.velocityDpPerMs}dp/ms w=${point.windowMs}ms",
                0.0,
                point.rates.tremorArm,
                0.0,
            )
        }
    }

    @Test
    fun deliberateOnsetsArmAtLeast99PercentAtTheShippedPoint() {
        assertTrue(
            "deliberate onsets armed ${pct(GRID.shipped.onsetArm)}, " +
                "below the ${pct(TRUE_ONSET_ARM_FLOOR)} floor",
            GRID.shipped.onsetArm >= TRUE_ONSET_ARM_FLOOR,
        )
    }

    @Test
    fun shippedPointRatesMatchThePinnedBaseline() {
        assertEquals("tremor arm rate", BASELINE_TREMOR_ARM, GRID.shipped.tremorArm, BASELINE_TOLERANCE)
        assertEquals("slip false-arm rate", BASELINE_SLIP_FALSE_ARM, GRID.shipped.slipArm, BASELINE_TOLERANCE)
        assertEquals("onset miss rate", BASELINE_ONSET_MISS, GRID.shipped.onsetMiss, BASELINE_TOLERANCE)
        assertEquals("drag arm rate", BASELINE_DRAG_ARM, GRID.shipped.dragArm, BASELINE_TOLERANCE)
    }

    @Test
    fun thePrintedVerdictEncodesTheRetuneRule() {
        val token =
            if (GRID.shipped.slipArm > RETUNE_FALSE_ARM_GATE) "RETUNE JUSTIFIED" else "KEEP INHERITED CONSTANTS"
        assertTrue("the grid printout lacks the verdict line", GRID.rendered.contains("-> $token"))
    }

    private companion object {

        // Fixture scale shared with GlideGestureDeciderTest: 100 px keys on the 440 dpi
        // reference screen (2.75 px/dp), so the shipped velocity threshold is 0.275 px/ms.
        const val KEY_WIDTH_PX = 100f
        const val DENSITY_PX_PER_DP = 2.75f
        const val SHIPPED_DISTANCE_KEY_WIDTHS = 1.0f
        val SLOP_PX = KEY_WIDTH_PX * GlideGestureDecider.DEFAULT_SLOP_KEY_WIDTH_FRACTION

        const val TRACE_SEED = 0x5EEDB50A01L

        /** Touch-report periods spanning common sampling rates; one is drawn per trace. */
        val SAMPLE_PERIODS_MS = intArrayOf(6, 8, 12, 16)

        // Population sizes; every rate below is a count over these.
        const val TREMOR_COUNT = 600
        const val SLIP_COUNT = 2000
        const val ONSET_COUNT = 3000
        const val DRAG_COUNT = 800

        // Trace model ranges: speeds in dp/s, lengths in key widths, times in ms.
        const val TREMOR_MIN_HOLD_MS = 60.0
        const val TREMOR_MAX_HOLD_MS = 300.0

        /** Tremor stays strictly sub-slop: under 90 % of the first-move anchor slop. */
        val TREMOR_MAX_RADIUS_PX = 0.9 * SLOP_PX

        const val SLIP_MIN_DP_PER_S = 100.0
        const val SLIP_MAX_DP_PER_S = 400.0
        const val SLIP_MIN_KEY_WIDTHS = 1.05
        const val SLIP_MAX_KEY_WIDTHS = 1.5

        const val ONSET_MIN_DP_PER_S = 300.0
        const val ONSET_MAX_DP_PER_S = 1200.0
        const val ONSET_MIN_KEY_WIDTHS = 1.5
        const val ONSET_MAX_KEY_WIDTHS = 4.0
        const val ONSET_MAX_BEND_DEG = 25.0
        const val ONSET_BEND_MIN_FRACTION = 0.4
        const val ONSET_BEND_MAX_FRACTION = 0.7

        const val DRAG_MIN_DP_PER_S = 20.0
        const val DRAG_MAX_DP_PER_S = 70.0
        const val DRAG_MIN_KEY_WIDTHS = 1.8
        const val DRAG_MAX_KEY_WIDTHS = 2.2

        const val DOWN_MIN_PX = 400.0
        const val DOWN_MAX_PX = 1400.0

        val TWO_PI = 2.0 * Math.PI

        // The threshold grid around the shipped point (1 key width, 0.10 dp/ms, 500 ms).
        val GRID_DISTANCE_KEY_WIDTHS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val GRID_VELOCITY_DP_PER_MS = floatArrayOf(0.05f, 0.075f, 0.10f, 0.125f, 0.15f, 0.20f)
        val GRID_WINDOW_MS = longArrayOf(250L, 375L, 500L, 750L, 1000L)

        // The decision rule: a retune is justified only above this slip false-arm rate, and any
        // kept constants must still arm true onsets at least TRUE_ONSET_ARM_FLOOR of the time.
        const val RETUNE_FALSE_ARM_GATE = 0.02
        const val TRUE_ONSET_ARM_FLOOR = 0.99

        // Baseline rates at the shipped point, pinned from the measured run of this harness;
        // re-pin when the trace model, the populations or the shipped constants change.
        const val BASELINE_TREMOR_ARM = 0.0
        const val BASELINE_SLIP_FALSE_ARM = 1.0
        const val BASELINE_ONSET_MISS = 0.0
        const val BASELINE_DRAG_ARM = 0.0
        const val BASELINE_TOLERANCE = 0.005

        /** The grid, computed once per test run; the computation prints the full table. */
        val GRID: GridResult by lazy { runGrid() }

        /** One generated touch: the down at index 0, then move samples in time order. */
        private class Trace(val xs: FloatArray, val ys: FloatArray, val ts: LongArray) {
            val size: Int get() = ts.size
        }

        /** Deterministic trace randomness: the SplitMix64 stream the other generators use. */
        private class Rng(private var state: Long) {

            fun nextLong(): Long {
                state += 0x9E3779B97F4A7C15uL.toLong()
                var z = state
                z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
                z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
                return z xor (z ushr 31)
            }

            /** Uniform in [lo, hi). */
            fun uniform(lo: Double, hi: Double): Double =
                lo + (hi - lo) * ((nextLong() ushr 11) * (1.0 / 9007199254740992.0))

            /** One of [options], uniformly. */
            fun pick(options: IntArray): Int = options[((nextLong() ushr 33) % options.size).toInt()]
        }

        /** Accumulates one trace: the down in the constructor, then [move] samples. */
        private class TraceBuilder(x: Float, y: Float, t: Long) {
            private val xs = ArrayList<Float>()
            private val ys = ArrayList<Float>()
            private val ts = ArrayList<Long>()

            init {
                move(x, y, t)
            }

            fun move(x: Float, y: Float, t: Long) {
                xs.add(x)
                ys.add(y)
                ts.add(t)
            }

            fun build(): Trace = Trace(xs.toFloatArray(), ys.toFloatArray(), ts.toLongArray())
        }

        /** Arm verdicts of one grid point, one rate per population. */
        private class Rates(
            val tremorArm: Double,
            val slipArm: Double,
            val onsetArm: Double,
            val dragArm: Double,
        ) {
            val onsetMiss: Double get() = 1.0 - onsetArm
        }

        /** One (distance, velocity, window) point of the threshold grid and its rates. */
        private class GridPoint(
            val distanceKeyWidths: Float,
            val velocityDpPerMs: Float,
            val windowMs: Long,
            val rates: Rates,
        ) {
            val isShipped: Boolean
                get() = distanceKeyWidths == SHIPPED_DISTANCE_KEY_WIDTHS &&
                    velocityDpPerMs == GlideGestureDecider.VELOCITY_THRESHOLD_DP_PER_MS &&
                    windowMs == GlideGestureDecider.DEFAULT_MAX_DETECT_TIME_MS
        }

        /** The full grid run: every point, the shipped point's rates and the rendered table. */
        private class GridResult(
            val points: List<GridPoint>,
            val shipped: Rates,
            val rendered: String,
        )

        private class Populations(
            val tremor: List<Trace>,
            val slip: List<Trace>,
            val onsets: List<Trace>,
            val drags: List<Trace>,
        )

        private fun generatePopulations(): Populations {
            val rng = Rng(TRACE_SEED)
            return Populations(
                tremor = List(TREMOR_COUNT) { tremorTrace(rng) },
                slip = List(SLIP_COUNT) { slipTrace(rng) },
                onsets = List(ONSET_COUNT) { onsetTrace(rng) },
                drags = List(DRAG_COUNT) { dragTrace(rng) },
            )
        }

        /** A held tap whose fingertip jitters strictly inside the anchor slop until the up. */
        private fun tremorTrace(rng: Rng): Trace {
            val period = rng.pick(SAMPLE_PERIODS_MS)
            val holdMs = rng.uniform(TREMOR_MIN_HOLD_MS, TREMOR_MAX_HOLD_MS)
            val downX = rng.uniform(DOWN_MIN_PX, DOWN_MAX_PX)
            val downY = rng.uniform(DOWN_MIN_PX, DOWN_MAX_PX)
            val trace = TraceBuilder(downX.toFloat(), downY.toFloat(), 0L)
            val samples = (holdMs / period).toInt()
            for (k in 1..samples) {
                val radius = rng.uniform(0.0, TREMOR_MAX_RADIUS_PX)
                val angle = rng.uniform(0.0, TWO_PI)
                trace.move(
                    (downX + radius * StrictMath.cos(angle)).toFloat(),
                    (downY + radius * StrictMath.sin(angle)).toFloat(),
                    (k * period).toLong(),
                )
            }
            return trace.build()
        }

        /** The false-arm class: a quick tap whose finger slips past one key width at slip speed. */
        private fun slipTrace(rng: Rng): Trace {
            val period = rng.pick(SAMPLE_PERIODS_MS)
            val speed = dpPerSecondToPxPerMs(rng.uniform(SLIP_MIN_DP_PER_S, SLIP_MAX_DP_PER_S))
            val length = rng.uniform(SLIP_MIN_KEY_WIDTHS, SLIP_MAX_KEY_WIDTHS) * KEY_WIDTH_PX
            val angle = rng.uniform(0.0, TWO_PI)
            return straightTrace(rng, speed, length, angle, period)
        }

        /** A deliberate glide onset: fast, well past one key width, with a slight mid-onset bend. */
        private fun onsetTrace(rng: Rng): Trace {
            val period = rng.pick(SAMPLE_PERIODS_MS)
            val speed = dpPerSecondToPxPerMs(rng.uniform(ONSET_MIN_DP_PER_S, ONSET_MAX_DP_PER_S))
            val length = rng.uniform(ONSET_MIN_KEY_WIDTHS, ONSET_MAX_KEY_WIDTHS) * KEY_WIDTH_PX
            val angle = rng.uniform(0.0, TWO_PI)
            val bendRad = Math.toRadians(rng.uniform(-ONSET_MAX_BEND_DEG, ONSET_MAX_BEND_DEG))
            val bendFraction = rng.uniform(ONSET_BEND_MIN_FRACTION, ONSET_BEND_MAX_FRACTION)
            return straightTrace(rng, speed, length, angle, period, bendFraction, bendRad)
        }

        /** A slow two-key drag, far below the glide velocity band. */
        private fun dragTrace(rng: Rng): Trace {
            val period = rng.pick(SAMPLE_PERIODS_MS)
            val speed = dpPerSecondToPxPerMs(rng.uniform(DRAG_MIN_DP_PER_S, DRAG_MAX_DP_PER_S))
            val length = rng.uniform(DRAG_MIN_KEY_WIDTHS, DRAG_MAX_KEY_WIDTHS) * KEY_WIDTH_PX
            val angle = rng.uniform(0.0, TWO_PI)
            return straightTrace(rng, speed, length, angle, period)
        }

        /**
         * A straight move of [lengthPx] at [speedPxPerMs], sampled every [periodMs], with the
         * terminal sample exactly at the length. When [bendFraction] is not NaN the direction
         * turns by [bendRad] at that fraction of the length.
         */
        private fun straightTrace(
            rng: Rng,
            speedPxPerMs: Double,
            lengthPx: Double,
            angleRad: Double,
            periodMs: Int,
            bendFraction: Double = Double.NaN,
            bendRad: Double = 0.0,
        ): Trace {
            val downX = rng.uniform(DOWN_MIN_PX, DOWN_MAX_PX)
            val downY = rng.uniform(DOWN_MIN_PX, DOWN_MAX_PX)
            val trace = TraceBuilder(downX.toFloat(), downY.toFloat(), 0L)
            val dirX = StrictMath.cos(angleRad)
            val dirY = StrictMath.sin(angleRad)
            val bendDirX = StrictMath.cos(angleRad + bendRad)
            val bendDirY = StrictMath.sin(angleRad + bendRad)
            val bendDistance = bendFraction * lengthPx
            val bending = !bendFraction.isNaN()

            fun positionX(d: Double): Double =
                if (bending && d > bendDistance) {
                    downX + bendDistance * dirX + (d - bendDistance) * bendDirX
                } else {
                    downX + d * dirX
                }

            fun positionY(d: Double): Double =
                if (bending && d > bendDistance) {
                    downY + bendDistance * dirY + (d - bendDistance) * bendDirY
                } else {
                    downY + d * dirY
                }

            var t = periodMs.toLong()
            var lastT = 0L
            while (speedPxPerMs * t < lengthPx) {
                val d = speedPxPerMs * t
                trace.move(positionX(d).toFloat(), positionY(d).toFloat(), t)
                lastT = t
                t += periodMs
            }
            // The terminal sample lands exactly at the length; the up follows right after.
            val endT = maxOf(lastT + 1, (lengthPx / speedPxPerMs).toLong())
            trace.move(positionX(lengthPx).toFloat(), positionY(lengthPx).toFloat(), endT)
            return trace.build()
        }

        /** dp/s to px/ms at the fixture density (the conversion PointerTracker applies). */
        private fun dpPerSecondToPxPerMs(speedDpPerS: Double): Double =
            speedDpPerS / 1000.0 * DENSITY_PX_PER_DP

        /** The decider as PointerTracker wires it, with the thresholds moved to one grid point. */
        private fun thresholdDecider(
            distanceKeyWidths: Float,
            velocityDpPerMs: Float,
            windowMs: Long,
        ) = GlideGestureDecider(
            distanceThresholdPx = distanceKeyWidths * KEY_WIDTH_PX,
            velocityThresholdPxPerMs = velocityDpPerMs * DENSITY_PX_PER_DP,
            maxDetectTimeMs = windowMs,
            slopPx = SLOP_PX,
        )

        /**
         * Replays [trace] the way PointerTracker feeds the decider; true if the gesture armed.
         * Feeding stops at a sticky verdict (ARMED / REJECTED are final within the touch). Every
         * synthetic touch is an eligible letter-key down (eligibility is upstream of the decider).
         */
        private fun arms(decider: GlideGestureDecider, trace: Trace): Boolean {
            decider.onDown(trace.xs[0], trace.ys[0], trace.ts[0], eligible = true)
            var armed = false
            for (i in 1 until trace.size) {
                when (decider.onMove(trace.xs[i], trace.ys[i], trace.ts[i])) {
                    GlideGestureDecider.State.ARMED -> {
                        armed = true
                        break
                    }
                    GlideGestureDecider.State.REJECTED -> break
                    else -> Unit
                }
            }
            decider.onUpOrCancel()
            return armed
        }

        private fun armRate(decider: GlideGestureDecider, traces: List<Trace>): Double {
            var armedCount = 0
            for (trace in traces) {
                if (arms(decider, trace)) armedCount++
            }
            return armedCount.toDouble() / traces.size
        }

        private fun runGrid(): GridResult {
            val populations = generatePopulations()
            val points = ArrayList<GridPoint>()
            var shipped: Rates? = null
            for (distance in GRID_DISTANCE_KEY_WIDTHS) {
                for (velocity in GRID_VELOCITY_DP_PER_MS) {
                    for (window in GRID_WINDOW_MS) {
                        val decider = thresholdDecider(distance, velocity, window)
                        val rates = Rates(
                            tremorArm = armRate(decider, populations.tremor),
                            slipArm = armRate(decider, populations.slip),
                            onsetArm = armRate(decider, populations.onsets),
                            dragArm = armRate(decider, populations.drags),
                        )
                        val point = GridPoint(distance, velocity, window, rates)
                        points.add(point)
                        if (point.isShipped) shipped = rates
                    }
                }
            }
            val shippedRates = requireNotNull(shipped) { "the grid must contain the shipped point" }
            val rendered = render(points, shippedRates, populations)
            println(rendered)
            return GridResult(points, shippedRates, rendered)
        }

        private fun render(points: List<GridPoint>, shipped: Rates, populations: Populations): String {
            val out = StringBuilder()
            out.appendLine("Glide decider synthetic arm-rate grid:")
            out.appendLine(
                "  fixture: keyWidthPx=${KEY_WIDTH_PX.toInt()} densityPxPerDp=$DENSITY_PX_PER_DP " +
                    "slopPx=$SLOP_PX samplePeriodsMs=${SAMPLE_PERIODS_MS.contentToString()} seed=$TRACE_SEED",
            )
            out.appendLine(
                "  populations: tremor=${populations.tremor.size} slip=${populations.slip.size} " +
                    "onsets=${populations.onsets.size} drags=${populations.drags.size}",
            )
            out.appendLine(
                "  shipped point: distance=${SHIPPED_DISTANCE_KEY_WIDTHS}kw " +
                    "velocity=${GlideGestureDecider.VELOCITY_THRESHOLD_DP_PER_MS}dp/ms " +
                    "window=${GlideGestureDecider.DEFAULT_MAX_DETECT_TIME_MS}ms",
            )
            out.appendLine(
                "  shipped rates: tremorArm=${pct(shipped.tremorArm)} " +
                    "slipFalseArm=${pct(shipped.slipArm)} onsetArm=${pct(shipped.onsetArm)} " +
                    "onsetMiss=${pct(shipped.onsetMiss)} dragArm=${pct(shipped.dragArm)}",
            )
            val verdict =
                if (shipped.slipArm > RETUNE_FALSE_ARM_GATE) "RETUNE JUSTIFIED" else "KEEP INHERITED CONSTANTS"
            out.appendLine(
                "  verdict: slip false-arm ${pct(shipped.slipArm)} vs ${pct(RETUNE_FALSE_ARM_GATE)} " +
                    "gate -> $verdict",
            )
            out.appendLine(
                "  grid (distanceKw velocityDpPerMs windowMs): tremorArm slipArm onsetArm onsetMiss dragArm",
            )
            for (point in points) {
                out.appendLine(
                    "    d=%4.2fkw v=%6.3f w=%4d: %s %s %s %s %s%s".format(
                        Locale.ROOT,
                        point.distanceKeyWidths,
                        point.velocityDpPerMs,
                        point.windowMs,
                        pct(point.rates.tremorArm),
                        pct(point.rates.slipArm),
                        pct(point.rates.onsetArm),
                        pct(point.rates.onsetMiss),
                        pct(point.rates.dragArm),
                        if (point.isShipped) "  <- shipped" else "",
                    ),
                )
            }
            val candidates = points.filter {
                it.rates.slipArm <= RETUNE_FALSE_ARM_GATE && it.rates.onsetArm >= TRUE_ONSET_ARM_FLOOR
            }
            out.appendLine(
                "  retune candidates (slipArm <= ${pct(RETUNE_FALSE_ARM_GATE)}, " +
                    "onsetArm >= ${pct(TRUE_ONSET_ARM_FLOOR)}): ${candidates.size} of ${points.size}",
            )
            for (point in candidates) {
                out.appendLine(
                    "    d=%4.2fkw v=%6.3f w=%4d: slipArm=%s onsetArm=%s dragArm=%s".format(
                        Locale.ROOT,
                        point.distanceKeyWidths,
                        point.velocityDpPerMs,
                        point.windowMs,
                        pct(point.rates.slipArm),
                        pct(point.rates.onsetArm),
                        pct(point.rates.dragArm),
                    ),
                )
            }
            out.appendLine(
                "  baseline pins: tremorArm=$BASELINE_TREMOR_ARM slipFalseArm=$BASELINE_SLIP_FALSE_ARM " +
                    "onsetMiss=$BASELINE_ONSET_MISS dragArm=$BASELINE_DRAG_ARM " +
                    "tolerance=$BASELINE_TOLERANCE",
            )
            return out.toString()
        }

        /** Percent with two decimals, locale-stable. */
        private fun pct(rate: Double): String = "%6.2f%%".format(Locale.ROOT, rate * 100.0)
    }
}

package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The P7-5 glide-feedback contract, pinned at source level (same reason as
 * [GlideTouchIntegrationContractTest]: PointerTracker's static state needs a live Resources,
 * and the view needs a real window — the behavioral halves live in GlideTrailTest and the
 * device-side GlidePointerDeviceTest).
 *
 * What is pinned here: the trail feed sits exactly inside the two armed-glide branches (so the
 * preference-off path is dead by construction), the hover is graphics-only (no preview, no
 * listener), every glide terminal ends the feedback, the view draws the trail on top of the
 * keys from preallocated state, and the theme carries the trail color.
 */
class GlideTrailContractTest {

    private val trackerSource = read(
        "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java",
        "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java",
    )
    private val viewSource = read(
        "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java",
        "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java",
    )
    private val proxySource = read(
        "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/internal/DrawingProxy.java",
        "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/internal/DrawingProxy.java",
    )

    @Test
    fun theTrailFeedSitsExactlyInsideTheArmedBranches() {
        // Two call sites: the armed branch, and the tracking branch that just armed. With the
        // preference off the decider sits in REJECTED, neither branch runs, and the trail stays
        // dead — the pref-off path is inert by construction.
        var count = 0
        var from = 0
        while (true) {
            val at = trackerSource.indexOf("updateGlideFeedback(x, y, eventTime);", from)
            if (at < 0) break
            count++
            from = at + 1
        }
        assertEquals("updateGlideFeedback must have exactly the two armed-glide call sites", 2, count)

        val move = methodBody("onMoveEventInternal")
        val armed = move.indexOf("mGlideDecider.isArmed()")
        val addPoint = move.indexOf("mGlidePath.addPoint(x, y, (float) eventTime);", armed)
        val feedback = move.indexOf("updateGlideFeedback(x, y, eventTime);", armed)
        val spaceSwipe = move.indexOf("Constants.CODE_SPACE")
        assertTrue("the armed branch must feed the trail", feedback in addPoint until spaceSwipe)

        val arm = move.indexOf("armGlide();")
        val armFeedback = move.indexOf("updateGlideFeedback(x, y, eventTime);", arm)
        assertTrue("the just-armed point feeds the trail too", armFeedback in arm until spaceSwipe)
    }

    @Test
    fun theHoverIsGraphicsOnly() {
        val update = methodBody("updateGlideFeedback")
        assertTrue("the hovered key lights up without a preview popup",
            update.contains("sDrawingProxy.onKeyPressed(key, false /* withPreview */);"))
        assertTrue("the previous hover is released",
            update.contains("sDrawingProxy.onKeyReleased(mGlideHoveredKey, false /* withAnimation */);"))
        assertTrue("the hover feeds the trail first", update.contains("sDrawingProxy.onGlideTrailPoint(x, y, eventTime);"))
        // No listener call: no haptics, no sound, no key commit from the hover.
        assertTrue("the hover must never touch the listener", !update.contains("sListener."))
    }

    @Test
    fun everyGlideTerminalEndsTheFeedback() {
        val up = methodBody("onUpEventInternal")
        val end = up.indexOf("endGlideFeedback();")
        val armedCheck = up.indexOf("if (armedGlide) {")
        assertTrue("the up handler ends the feedback", end >= 0)
        assertTrue("the feedback ends for the delivery and the cancel alike", end < armedCheck)

        assertTrue("the multi-touch steal ends the feedback",
            methodBody("cancelArmedGlide").contains("endGlideFeedback();"))
        assertTrue("the cancel event ends the feedback",
            methodBody("onCancelEventInternal").contains("endGlideFeedback();"))

        // The close-mid-gesture path reaches the trackers without a CANCEL event.
        val cancelAll = trackerSource.substringAfter("public static void cancelAllPointerTrackers()")
        assertTrue("cancelAllPointerTrackers drops the live feedback", cancelAll.contains("endGlideFeedback();"))

        val endBody = methodBody("endGlideFeedback")
        assertTrue("the hover is released and nulled", endBody.contains("mGlideHoveredKey = null;"))
        assertTrue("the trail end always fires (the view no-ops on an empty trail)",
            endBody.contains("sDrawingProxy.onGlideTrailEnd();"))
    }

    @Test
    fun theViewDrawsTheTrailOnTopOfTheKeysFromPreallocatedState() {
        val onDraw = viewSource.substringAfter("protected void onDraw(final Canvas canvas)")
        val superDraw = onDraw.indexOf("super.onDraw(canvas);")
        val trail = onDraw.indexOf("drawGlideTrail(canvas);")
        assertTrue("the view overrides onDraw", superDraw >= 0)
        assertTrue("the trail draws on top of the keys", trail > superDraw)

        val draw = viewSource.substringAfter("private void drawGlideTrail(final Canvas canvas)")
        assertTrue("only the visible tail window is walked", draw.contains("mGlideTrail.firstVisible()"))
        assertTrue("a single point draws nothing", draw.contains("size - first < 2"))
        assertTrue("the per-segment alpha comes from the trail's fade math",
            draw.contains("paint.setAlpha(mGlideTrail.alphaAt(i + 1));"))
        assertTrue("the draw is a plain polyline on the preallocated paint",
            draw.contains("canvas.drawLine("))

        // The feed and the drain.
        val point = viewSource.substringAfter("public void onGlideTrailPoint(")
        assertTrue(point.contains("mGlideTrail.addPoint(x, y, (float) eventTime);"))
        assertTrue(point.contains("invalidate();"))
        val endView = viewSource.substringAfter("public void onGlideTrailEnd()")
        assertTrue("an empty trail is a no-op (no per-keystroke invalidate)",
            endView.contains("if (mGlideTrail.isEmpty())"))
        assertTrue(endView.contains("mGlideTrail.clear();"))

        // Preallocated paint configured from the theme attr.
        assertTrue(viewSource.contains("private final GlideTrail mGlideTrail = new GlideTrail();"))
        assertTrue(viewSource.contains("R.styleable.MainKeyboardView_glideTrailColor"))
        assertTrue(viewSource.contains("R.dimen.config_glide_trail_stroke_width"))
    }

    @Test
    fun theThemeCarriesTheTrailColorAndTheProxyHasTheSeams() {
        assertTrue(proxySource.contains("void onGlideTrailPoint(float x, float y, long eventTime);"))
        assertTrue(proxySource.contains("void onGlideTrailEnd();"))

        val attrs = read(
            "src/main/res/values/attrs.xml",
            "app/src/main/res/values/attrs.xml",
        )
        assertTrue(attrs.contains("<attr name=\"glideTrailColor\" format=\"color\" />"))
        val tatar = read(
            "src/main/res/values/themes-tatar.xml",
            "app/src/main/res/values/themes-tatar.xml",
        )
        assertTrue(tatar.contains("<item name=\"glideTrailColor\">@color/app_accent</item>"))
        val config = read(
            "src/main/res/values/config.xml",
            "app/src/main/res/values/config.xml",
        )
        assertTrue(config.contains("<dimen name=\"config_glide_trail_stroke_width\">"))
    }

    /** The body of one method, from its declaration to the next one (good enough for pins). */
    private fun methodBody(name: String): String {
        // The " void " prefix skips javadoc {@link} references to the same method.
        val start = trackerSource.indexOf(" void $name(")
        assertTrue("method $name not found", start >= 0)
        val next = trackerSource.indexOf("\n    private ", start + 1)
        val nextPublic = trackerSource.indexOf("\n    public ", start + 1)
        val end = listOf(next, nextPublic).filter { it > 0 }.minOrNull() ?: trackerSource.length
        return trackerSource.substring(start, end)
    }

    private fun read(vararg paths: String): String =
        paths.map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate ${paths.first()}")
}

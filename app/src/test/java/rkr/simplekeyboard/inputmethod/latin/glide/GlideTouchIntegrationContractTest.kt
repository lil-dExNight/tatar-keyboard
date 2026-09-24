package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The P7-2 touch-integration contract, pinned at source level (PointerTracker's static state
 * needs a live Resources, so it cannot be instantiated in a JVM test — the behavioral half of
 * the contract lives in [GlideGestureDeciderTest], which pins the real decision machine).
 *
 * What is pinned here: the exact branch structure that makes the glide integration safe — the
 * armed/tracking checks ahead of the legacy space/delete swipe branches, the fail-closed UP
 * delivery, the multi-touch and phantom-up cancels, the historical-batch feed, the eligibility
 * gate at DOWN, and the legacy swipe branches' verbatim survival (the pref-OFF path is
 * byte-identical by construction and these pins make a silent restructure loud).
 */
class GlideTouchIntegrationContractTest {

    private val trackerSource = read(
        "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java",
        "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java",
    )

    @Test
    fun armedGlidePrecedesAndShortCircuitsTheLegacyMoveBranches() {
        val move = methodBody("onMoveEventInternal")
        val armed = move.indexOf("mGlideDecider.isArmed()")
        val tracking = move.indexOf("mGlideDecider.isTracking()")
        val spaceSwipe = move.indexOf("Constants.CODE_SPACE")
        val deleteSwipe = move.indexOf("Constants.CODE_DELETE")
        assertTrue("armed branch missing", armed >= 0)
        assertTrue("tracking branch missing", tracking >= 0)
        assertTrue("armed branch must precede the space swipe branch", armed < spaceSwipe)
        assertTrue("tracking branch must precede the space swipe branch", tracking < spaceSwipe)
        assertTrue("armed branch must precede the delete swipe branch", armed < deleteSwipe)
        // An armed MOVE feeds the path and returns.
        assertTrue(move.contains("mGlidePath.addPoint(x, y, eventTime);"))
        // A cursor swipe that already started can no longer become a glide.
        assertTrue(move.contains("mGlideDecider.isTracking() && !mCursorMoved"))
    }

    @Test
    fun theLegacySwipeBranchesSurviveVerbatim() {
        // The pref-OFF path is byte-identical to the pre-glide code; these are its exact lines.
        assertTrue(
            trackerSource.contains(
                "if (oldKey != null && oldKey.getCode() == Constants.CODE_SPACE && " +
                    "Settings.getInstance().getCurrent().mSpaceSwipeEnabled)",
            ),
        )
        assertTrue(
            trackerSource.contains(
                "if (oldKey != null && oldKey.getCode() == Constants.CODE_DELETE && " +
                    "Settings.getInstance().getCurrent().mDeleteSwipeEnabled)",
            ),
        )
        assertTrue(trackerSource.contains("sListener.onMoveCursorPointer(steps);"))
        assertTrue(trackerSource.contains("sListener.onMoveDeletePointer(steps);"))
    }

    @Test
    fun upDeliveryIsFailClosedAndSkipsKeyCommit() {
        val up = methodBody("onUpEventInternal")
        val armedCheck = up.indexOf("if (armedGlide) {")
        assertTrue("armed UP block missing", armedCheck >= 0)
        assertTrue(
            "delivery must be guarded against a cancelled tracker",
            up.contains("if (!mIsTrackingForActionDisabled)"),
        )
        val deliver = up.indexOf("sListener.onGlideInput(mGlidePath);")
        val sendKey = up.indexOf("detectAndSendKey(currentKey, mKeyX, mKeyY);")
        assertTrue("glide delivery missing", deliver >= 0)
        assertTrue("delivery must precede (and return before) the key commit", deliver < sendKey)
    }

    @Test
    fun multiTouchAndPhantomUpCancelTheGlide() {
        assertTrue(trackerSource.contains("cancelArmedGlideTrackersExcept(mPointerId);"))
        val phantom = methodBody("onPhantomUpEvent")
        val cancel = phantom.indexOf("mGlideDecider.cancelGlide();")
        val up = phantom.indexOf("onUpEventInternal(mLastX, mLastY);")
        assertTrue("phantom up must cancel the glide", cancel >= 0)
        assertTrue("the cancel must precede the up handling", cancel < up)
    }

    @Test
    fun armingCancelsTimersAndReleasesThePreview() {
        val arm = methodBody("armGlide")
        assertTrue(arm.contains("sTimerProxy.cancelKeyTimersOf(this);"))
        assertTrue(arm.contains("setReleasedKeyGraphics(mCurrentKey, true"))
        // The in-flight timer callbacks carry the armed guard.
        assertTrue(methodBody("onLongPressed").contains("if (mGlideDecider.isArmed())"))
        assertTrue(methodBody("onKeyRepeat").contains("if (mGlideDecider.isArmed())"))
    }

    @Test
    fun theHistoricalBatchIsConsumedForActiveGlidesOnly() {
        assertTrue(trackerSource.contains("me.getHistoricalX(index, h)"))
        assertTrue(trackerSource.contains("me.getHistoricalEventTime(h)"))
        assertTrue(
            trackerSource.contains(
                "if (tracker.isGlideGestureActive() && !tracker.isShowingMoreKeysPanel())",
            ),
        )
    }

    @Test
    fun eligibilityIsEvaluatedAtDownWithPrefAndLetterGate() {
        val down = methodBody("onDownEventInternal")
        assertTrue(down.contains("mGlideTypingEnabled"))
        assertTrue(down.contains("Character.isLetter(key.getCode())"))
        assertTrue(down.contains("mGlideDecider.onDown(x, y, eventTime, glideEligible);"))
    }

    @Test
    fun theListenerHasADefaultNoOpAndLatinIMEForwardsToTheController() {
        val listener = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/keyboard/KeyboardActionListener.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/keyboard/KeyboardActionListener.java",
        )
        assertTrue(listener.contains("default void onGlideInput("))
        val latinIme = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        )
        assertTrue(latinIme.contains("public void onGlideInput("))
        // P7-3: the receiver now forwards to the suggestions controller (the P7-2 stub is gone).
        assertTrue(latinIme.contains("controller.onGlideInput(path);"))
        assertTrue(latinIme.contains("setGlideGate("))
        assertTrue(latinIme.contains("setGlideShiftStateGate("))
    }

    @Test
    fun thePreferenceIsSubordinateToTheSuggestionsToggle() {
        val settings = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java",
        )
        assertTrue(settings.contains("PREF_GLIDE_TYPING = \"pref_glide_typing\""))
        val reader = settings.substring(settings.indexOf("readGlideTypingEnabled"))
        assertTrue(reader.contains("readTatarSuggestionsEnabled(prefs)"))
        assertTrue(
            "the reader defaults to ON (subordinate to the suggestions master)",
            reader.contains("prefs.getBoolean(PREF_GLIDE_TYPING, true)"),
        )
        val values = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsValues.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsValues.java",
        )
        assertTrue(values.contains("mGlideTypingEnabled = Settings.readGlideTypingEnabled(prefs);"))
    }

    @Test
    fun theSettingsScreenShowsTheSameDefaultAndFollowsTheMasterSwitch() {
        // The EmojiSuggestDefaultSourceContractTest shape: the screen default must never drift
        // from the reader default, and the row is grayed when the master switch is off.
        val host = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
        )
        assertTrue(host.contains("switchRow(Settings.PREF_GLIDE_TYPING, true"))
        assertTrue(host.contains("glideRow?.let {"))
        assertTrue(host.contains("setRowEnabled(glideSwitch,"))
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

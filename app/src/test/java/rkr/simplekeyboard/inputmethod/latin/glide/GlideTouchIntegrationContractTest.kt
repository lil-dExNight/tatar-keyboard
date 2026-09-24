package rkr.simplekeyboard.inputmethod.latin.glide

import org.junit.Assert.assertFalse
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
        // An armed MOVE feeds the path and returns. (The cast was made explicit in the 2026-09-24
        // audit wave, finding 12: the same call, the same narrowing — now written out.)
        assertTrue(move.contains("mGlidePath.addPoint(x, y, (float) eventTime);"))
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
        // The field fix: a long-press that FIRES for an undecided tracker cancels the decider —
        // a finger moving after the panel opened is a panel selection, never a glide.
        val longPress = methodBody("onLongPressed")
        val armedGuard = longPress.indexOf("if (mGlideDecider.isArmed())")
        val cancel = longPress.indexOf("mGlideDecider.cancelGlide();")
        assertTrue("the fired long-press cancels the decider", cancel >= 0)
        assertTrue("the cancel sits behind the armed guard", cancel > armedGuard)
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
    fun thePreferenceIsIndependentOfTheSuggestionsMaster() {
        // P7-6 (docs/ROADMAP-P7.md, the 2026-09-24 field report): glide answers its own toggle
        // only — the P7-2 subordination is reverted (Gboard parity).
        val settings = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/Settings.java",
        )
        assertTrue(settings.contains("PREF_GLIDE_TYPING = \"pref_glide_typing\""))
        val reader = settings.substringAfter("boolean readGlideTypingEnabled")
            .substringBefore("public static")
        assertTrue(
            "the reader defaults to ON",
            reader.contains("prefs.getBoolean(PREF_GLIDE_TYPING, true)"),
        )
        assertFalse(
            "the reader must NOT consult the suggestions master",
            reader.contains("readTatarSuggestionsEnabled"),
        )
        val values = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsValues.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsValues.java",
        )
        assertTrue(values.contains("mGlideTypingEnabled = Settings.readGlideTypingEnabled(prefs);"))
    }

    @Test
    fun theSettingsScreenShowsTheSameDefaultAndDoesNotFollowTheMasterSwitch() {
        // The EmojiSuggestDefaultSourceContractTest shape: the screen default must never drift
        // from the reader default. P7-6: the row is NOT grayed when the master switch is off —
        // only the MDM restriction disables it (both the callback site and the initial state).
        val host = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SettingsHostActivity.kt",
        )
        assertTrue(host.contains("switchRow(Settings.PREF_GLIDE_TYPING, true"))
        assertTrue(host.contains("glideRow?.let {"))
        assertTrue(
            "the glide row must not be grayed by the master switch",
            host.contains("setRowEnabled(it, !isRestricted(Settings.PREF_GLIDE_TYPING))"),
        )
        assertFalse(
            "the master-off graying of the glide row is gone",
            host.contains("setRowEnabled(it, checked && !isRestricted(Settings.PREF_GLIDE_TYPING))"),
        )
        // The controller carries the split eligibility the unhook needs.
        val controller = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
        )
        assertTrue(controller.contains("glideEligible: Boolean = eligible"))
        assertTrue(controller.contains("this.glideEligible = glideEligible && activeLanguage != null"))
        // LatinIME passes the two gates separately.
        val latinIme = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        )
        assertTrue(latinIme.contains("isSuggestionsEligible(), activeDictionarySubtype(), isGlideEligible()"))
        // The device catch of the P7-6 UAT: the geometry push must ride the GLIDE gate — with the
        // master off a suggestions-gated push disables the decode outright (fail-closed null).
        val geometrySlice = latinIme.substringAfter("GlideKeyGeometry geometry = null;")
            .substringBefore("mSuggestionsController.updateGlideGeometry(geometry);")
        assertTrue("the glide geometry answers the glide gate",
            geometrySlice.contains("isGlideEligible()"))
        assertFalse("the glide geometry must not answer the suggestions gate",
            geometrySlice.contains("isSuggestionsEligible()"))
    }

    @Test
    fun theLiftCommitWiringIsPinned() {
        // The UX amendment (docs/ROADMAP-P7.md, 2026-09-24): lift commits top-1, alternatives
        // replace in-editor, one backspace deletes the whole word. The wiring points:
        val controller = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
        )
        // The commit goes through the glide's OWN commit path (P7-6: the predicted-word re-checks
        // minus the sentence-start requirement for an empty context — the "сүз ? " field report).
        val apply = controller.substringAfter("private fun applyGlideResult")
        assertTrue(apply.contains("editor.commitGlideWord(pendingGlideContext, committed)"))
        assertTrue(apply.contains("glideCommittedWord = committed"))
        assertTrue(apply.contains("displayedGlideAlternativesFor = committed"))
        // The alternatives tap replaces in-editor.
        assertTrue(controller.contains("editor.replaceGlideLiftedWord(glideAlternativesFor, suggestion)"))
        // The undo.
        assertTrue(controller.contains("fun maybeUndoGlideCommit()"))
        assertTrue(controller.contains("editor.deleteGlideLiftedWord(word)"))

        val surfaces = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionSurfaces.kt",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionSurfaces.kt",
        )
        // The seams default to false: an editor surface that predates them never edits.
        assertTrue(surfaces.contains("fun replaceGlideLiftedWord(committedWord: String, alternative: String): Boolean = false"))
        assertTrue(surfaces.contains("fun deleteGlideLiftedWord(committedWord: String): Boolean = false"))
        assertTrue(surfaces.contains("fun commitGlideWord(expectedContextWord: String, suggestion: String): Boolean = false"))

        val latinIme = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        )
        // The backspace route for the gesture-undo, right after the autocorrect revert.
        assertTrue(latinIme.contains("LatinImeGlide.maybeUndoGlideCommit(this, event)"))
        val glideHelper = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinImeGlide.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/LatinImeGlide.java",
        )
        assertTrue(glideHelper.contains("mSuggestionsController.maybeUndoGlideCommit()"))
        assertTrue(glideHelper.contains("event.mKeyCode != Constants.CODE_DELETE"))

        val inputLogic = read(
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
            "app/src/main/java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
        )
        // The position checks are the suffix match, and both paths refuse a mid-word cursor.
        for (method in listOf("replaceGlideLiftedWord", "deleteGlideLiftedWord")) {
            val body = inputLogic.substringAfter("public boolean $method(")
            assertTrue("$method matches the committed word + its space", body.contains("committedWord + AUTO_SPACE"))
            assertTrue("$method refuses a mid-word cursor", body.contains("startsWithWordCharacter(mConnection.getCachedTextAfterCursor())"))
        }
        // P7-6: the glide commit keeps every live re-check of the prediction commit EXCEPT the
        // sentence-start requirement — that asymmetry is the field fix and must not drift back.
        val glideCommit = inputLogic.substringAfter("public boolean commitGlideWord(")
            .substringBefore("public boolean")
        assertTrue("the glide commit re-derives the live context",
            glideCommit.contains("extractNextWordContext"))
        assertTrue("the glide commit refuses a half-typed word",
            glideCommit.contains("extractTrailingWord"))
        assertFalse("the glide commit must NOT require a sentence start for an empty context",
            glideCommit.contains("isSentenceStartContext"))
        val predictedCommit = inputLogic.substringAfter("public boolean commitPredictedWord(")
            .substringBefore("/** Allocation-free suffix test")
        assertTrue("the prediction commit keeps its P4 sentence-start guard",
            predictedCommit.contains("isSentenceStartContext"))
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

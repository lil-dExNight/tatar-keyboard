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

package rkr.simplekeyboard.inputmethod.latin.suggestions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionStripSourceContractTest {
    @Test
    fun oneLazyCanvasViewAndBothLayoutsPreserveGoneBaseline() {
        val main = sourceRoot()
        val normalLayout = File(main, "res/layout/input_view.xml").readText()
        val v28Layout = File(main, "res/layout-v28/input_view.xml").readText()
        val stripLayout = File(main, "res/layout/suggestion_strip.xml").readText()

        for (layout in listOf(normalLayout, v28Layout)) {
            assertTrue(layout.contains("<ViewStub"))
            assertTrue(layout.contains("@layout/suggestion_strip"))
            assertTrue(layout.contains("android:layout_height=\"40dp\""))
            assertEquals(1, "<rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView".toRegex()
                .findAll(layout).count())
        }
        assertEquals(1, "SuggestionStripView".toRegex().findAll(stripLayout).count())
        assertTrue(stripLayout.contains("android:visibility=\"gone\""))
        assertFalse(stripLayout.contains("<Button"))
        assertFalse(stripLayout.contains("<TextView"))
    }

    @Test
    fun hotDrawAndTouchBodiesContainNoKnownAllocationSites() {
        val viewSource = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt",
        ).readText()
        val drawBody = viewSource.substringAfter("override fun onDraw")
            .substringBefore("@Suppress(\"ClickableViewAccessibility\")")
        val touchBody = viewSource.substringAfter("override fun onTouchEvent")
            .substringBefore("override fun dispatchHoverEvent")

        listOf(drawBody, touchBody).forEach { hotBody ->
            listOf("= Rect(", "= Paint(", "MotionEvent.obtain", "intArrayOf(", "TextUtils.", ".toString()")
                .forEach { forbidden -> assertFalse("hot path contains $forbidden", hotBody.contains(forbidden)) }
        }
        assertTrue(viewSource.contains("VIRTUAL_ID_LEFT = 0"))
        assertTrue(viewSource.contains("VIRTUAL_ID_CENTER = 1"))
        assertTrue(viewSource.contains("VIRTUAL_ID_RIGHT = 2"))
        assertTrue(viewSource.contains("ExploreByTouchHelper"))
    }

    @Test
    fun touchDispatchTracksActivePointerAndHandlesPointerUpExplicitly() {
        val viewSource = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt",
        ).readText()
        val touchBody = viewSource.substringAfter("override fun onTouchEvent")
            .substringBefore("override fun dispatchHoverEvent")

        assertTrue(touchBody.contains("event.findPointerIndex(state.activePointerId())"))
        assertTrue(touchBody.contains("MotionEvent.ACTION_POINTER_UP"))
        assertTrue(touchBody.contains("state.onPointerUp(event.getPointerId(event.actionIndex))"))
        assertTrue(touchBody.contains("event.getPointerId(pointerIndex)"))
    }

    @Test
    fun detachClearsSuggestionsListenerAndRejectsStaleAccessibilityActions() {
        val main = sourceRoot()
        val viewSource = File(
            main,
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt",
        ).readText()
        val inputViewSource = File(
            main,
            "java/rkr/simplekeyboard/inputmethod/latin/InputView.java",
        ).readText()
        val releaseBody = viewSource.substringAfter("fun release()")
            .substringBefore("fun getSuggestion")
        val detachBody = viewSource.substringAfter("override fun onDetachedFromWindow()")
            .substringBefore("private fun activateCell")
        val actionableBody = viewSource.substringAfter("private fun isVirtualCellActionable")
            .substringBefore("companion object")

        assertTrue(releaseBody.contains("state.clear()"))
        assertTrue(releaseBody.contains("listener = null"))
        assertTrue(releaseBody.contains("visibility = GONE"))
        assertTrue(releaseBody.contains("accessibilityHelper.invalidateRoot()"))
        assertTrue(detachBody.contains("release()"))
        assertTrue(actionableBody.contains("ViewCompat.isAttachedToWindow"))
        assertTrue(actionableBody.contains("isShown"))
        assertTrue(actionableBody.contains("state.isCellPopulated(virtualViewId)"))
        assertTrue(inputViewSource.contains("mSuggestionStripView.release()"))
        assertTrue(inputViewSource.contains("mInsetsChangedListener = null"))
    }

    @Test
    fun insetsUseCombinedBoundsAndKeepMoreKeysAndHardwareGuards() {
        val javaRoot = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin")
        val inputView = File(javaRoot, "InputView.java").readText()
        val latinIme = File(javaRoot, "LatinIME.java").readText()

        assertTrue(inputView.contains("outBounds.union(mTemporaryBounds)"))
        assertTrue(inputView.contains("strip.isShown()"))
        assertTrue(latinIme.contains("getVisibleInputBounds"))
        assertTrue(latinIme.contains("showingMoreKeys ? 0 : visibleTopY"))
        assertTrue(latinIme.contains("isImeSuppressedByHardwareKeyboard()"))
        assertTrue(latinIme.contains("outInsets.touchableRegion.setEmpty()"))
    }

    @Test
    fun suggestionsAreAnnouncedOnlyOnTheEmptyToPopulatedTransition() {
        val viewSource = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt",
        ).readText()
        val setSuggestionsBody = viewSource.substringAfter("fun setSuggestions(")
            .substringBefore("fun clearSuggestions()")

        // The triple changes on every keystroke: announcing each one would bury the key echo.
        assertTrue(setSuggestionsBody.contains("val hadSuggestions = state.hasAnySuggestion()"))
        assertTrue(
            setSuggestionsBody.contains(
                "if (hadSuggestions || !accessibilityManager.isTouchExplorationEnabled) return",
            ),
        )
        assertTrue(setSuggestionsBody.contains("announceForAccessibility"))
        assertTrue(setSuggestionsBody.contains("R.string.spoken_suggestions_available"))
    }

    // --- P2 of Phase 3 (docs/ROADMAP-P3.md): the autocorrect preview's emphasis ------------------

    @Test
    fun theEmphasizedCellDrawsBoldAccentedAndUnderlinedWithNoAllocation() {
        val main = sourceRoot()
        val viewSource = File(
            main,
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionStripView.kt",
        ).readText()
        val drawBody = viewSource.substringAfter("override fun onDraw")
            .substringBefore("@Suppress(\"ClickableViewAccessibility\")")

        // The correction cell: its own paint (bold, theme accent) and an underline measured
        // against exactly the drawn (ellipsized) text.
        assertTrue(drawBody.contains("state.isEmphasized(cell)"))
        assertTrue(drawBody.contains("emphasisTextPaint"))
        assertTrue(drawBody.contains("emphasisTextPaint.measureText(suggestion)"))
        assertTrue(drawBody.contains("canvas.drawLine("))

        // The paint is bold by construction, not per frame; the colour is a theme attr with the
        // plain text colour as the fail-closed default.
        assertTrue(viewSource.contains("Typeface.create(textPaint.typeface, Typeface.BOLD)"))
        assertTrue(
            viewSource.contains("R.styleable.SuggestionStripView_suggestionEmphasisColor"),
        )
        val attrs = File(main, "res/values/attrs.xml").readText()
        val styleable = attrs
            .substringAfter("<declare-styleable name=\"SuggestionStripView\">")
            .substringBefore("</declare-styleable>")
        assertTrue(styleable.contains("suggestionEmphasisColor"))
        val theme = File(main, "res/values/themes-tatar.xml").readText()
        assertTrue(theme.contains("name=\"suggestionEmphasisColor\">@color/app_accent"))
    }

    @Test
    fun theEmphasisMarkerTravelsFromTheControllerThroughTheProductionWiring() {
        val main = sourceRoot()
        val controller = File(
            main,
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsController.kt",
        ).readText()
        // The seam is a default no-op, so a surface written before P2 simply never emphasizes.
        assertTrue(controller.contains("fun setEmphasizedCell(cell: Int) {}"))
        // The single band writer publishes the marker with the words, never apart from them.
        val showBandBody = controller.substringAfter("private fun showBand(")
            .substringBefore("    /**")
        assertTrue(showBandBody.contains("strip.setEmphasizedCell(emphasizedCell)"))
        val latinIme = File(
            main,
            "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        ).readText()
        assertTrue(latinIme.contains("public void setEmphasizedCell(final int cell)"))
        assertTrue(latinIme.contains("inputView.setSuggestionStripEmphasis(cell)"))
        val inputView = File(
            main,
            "java/rkr/simplekeyboard/inputmethod/latin/InputView.java",
        ).readText()
        assertTrue(inputView.contains("mSuggestionStripView.setEmphasis(cell)"))
    }

    @Test
    fun internalCursorGesturesNotifyTheControllerDirectly() {
        val latinIme = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        ).readText()
        // onUpdateSelection() cannot cover these: the connection keeps the expected selection in
        // sync (so the move is not "external") and it returns early while the gesture runs.
        val gestureBodies = listOf(
            latinIme.substringAfter("public void onMoveCursorPointer")
                .substringBefore("public void onMoveDeletePointer"),
            latinIme.substringAfter("public void onMoveDeletePointer")
                .substringBefore("public void onUpWithDeletePointerActive"),
            latinIme.substringAfter("public void onUpWithDeletePointerActive")
                .substringBefore("public void onUpWithSpacePointerActive"),
            latinIme.substringAfter("public void onUpWithSpacePointerActive")
                .substringBefore("/**"),
        )
        gestureBodies.forEach {
            assertTrue(it.contains("onSuggestionsAffectingCursorMove()"))
        }
        assertTrue(latinIme.contains("mSuggestionsController.onSelectionChanged()"))
    }

    @Test
    fun commitPathRefusesToReplaceAWordTheCursorSitsInside() {
        val javaRoot = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin")
        val inputLogic = File(javaRoot, "inputlogic/InputLogic.java").readText()
        val connection = File(javaRoot, "RichInputConnection.java").readText()
        val commitBody = inputLogic.substringAfter("public boolean commitChosenSuggestion")
            .substringBefore("private boolean layoutUsesAutoCaps")

        // Fail-closed second line of defense, before any edit reaches the editor.
        assertTrue(
            commitBody.indexOf("startsWithWordCharacter(mConnection.getCachedTextAfterCursor())")
                in 0 until commitBody.indexOf("deleteTextBeforeCursor"),
        )
        // The right-hand context comes from the local cache, never from IPC, and is never logged.
        assertTrue(connection.contains("public CharSequence getCachedTextAfterCursor()"))
        assertTrue(
            connection.substringAfter("public CharSequence getCachedTextAfterCursor()")
                .substringBefore("}")
                .contains("Do not log the returned value"),
        )
    }

    @Test
    fun acceptedSuggestionCarriesItsAutoSpaceInsideTheSameCommit() {
        val javaRoot = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin")
        val inputLogic = File(javaRoot, "inputlogic/InputLogic.java").readText()
        // D3 made the word replacement a method shared by BOTH insertion paths of the frozen text
        // contract, so the slice moved from `commitChosenSuggestion` (which now only says "with
        // auto-space" and delegates) to that shared method. Everything asserted below is unchanged
        // and still describes the accepted suggestion: it is the same code, in its new single home.
        val commitBody = inputLogic.substringAfter("private boolean replaceTrailingWord")
            .substringBefore("public boolean revertTatarAutocorrection")

        // One commitText for word + space: two would flash the word without its space and would
        // cost a second IPC round trip.
        assertEquals(1, "mConnection\\.commitText\\(".toRegex().findAll(commitBody).count())
        assertTrue(commitBody.contains("replacement + AUTO_SPACE"))
        assertTrue(
            commitBody.contains(
                "TatarWordUtils.needsAutoSpace(mConnection.getCachedTextAfterCursor())",
            ),
        )
        assertTrue(
            commitBody.indexOf("beginBatchEdit")
                in 0 until commitBody.indexOf("mConnection.commitText"),
        )
        // The inserted space must not arm the double-space-to-period gesture.
        assertTrue(
            commitBody.indexOf("mConnection.endBatchEdit()")
                in 0 until commitBody.indexOf("mJustDoubleSpaced = false"),
        )
        assertTrue(commitBody.contains("mLastSpaceDownTime = 0"))
    }

    // --- E5d: commitPredictedWord ("Контракт текста" amendment, 2026-08-17, пункт 4) ------------

    @Test
    fun commitChosenSuggestionIsByteForByteUnchangedByThisPhase() {
        // commitChosenSuggestion's own body is a single delegating line; asserting it verbatim is a
        // full "unchanged" proof for the method itself, not just a partial one — there is no more of
        // it to diverge. replaceTrailingWord's shared body is proven unchanged separately by
        // acceptedSuggestionCarriesItsAutoSpaceInsideTheSameCommit above, unaffected by
        // commitPredictedWord living outside that slice (see the next test).
        val inputLogic = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
        ).readText()
        val commitChosenSuggestionBody = inputLogic
            .substringAfter("public boolean commitChosenSuggestion(final String expectedPrefix, final String suggestion) {")
            .substringBefore("}")
            .trim()
        assertEquals(
            "return replaceTrailingWord(expectedPrefix, suggestion, true /* withAutoSpace */);",
            commitChosenSuggestionBody,
        )
    }

    @Test
    fun commitPredictedWordDeletesExactlyZeroCharacters() {
        val inputLogic = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
        ).readText()
        val commitBody = inputLogic.substringAfter("public boolean commitPredictedWord(")
            .substringBefore("/** Allocation-free suffix test over the cached text")
        // NEXT_WORD never trails an existing prefix to remove — the whole point of the third
        // insertion path (PROPOSALS.md, "Контракт текста" amendment, "Отдельный путь коммита") —
        // so this method must never call a delete of any kind.
        assertFalse(commitBody.contains("deleteTextBeforeCursor"))
        assertFalse(commitBody.contains("deleteSurroundingText"))
    }

    @Test
    fun commitPredictedWordInsertsWithAutoSpaceInOneCommitText() {
        val inputLogic = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
        ).readText()
        val commitBody = inputLogic.substringAfter("public boolean commitPredictedWord(")
            .substringBefore("/** Allocation-free suffix test over the cached text")
        assertEquals(1, "mConnection\\.commitText\\(".toRegex().findAll(commitBody).count())
        assertTrue(commitBody.contains("suggestion + AUTO_SPACE"))
        assertTrue(
            commitBody.contains(
                "TatarWordUtils.needsAutoSpace(mConnection.getCachedTextAfterCursor())",
            ),
        )
        assertTrue(
            commitBody.indexOf("beginBatchEdit")
                in 0 until commitBody.indexOf("mConnection.commitText"),
        )
        assertTrue(commitBody.contains("mJustDoubleSpaced = false"))
        assertTrue(commitBody.contains("mLastSpaceDownTime = 0"))
    }

    @Test
    fun commitPredictedWordRequiresCollapsedSelectionNoLetterAfterCursorEmptyTailAndLiveContextMatch() {
        val inputLogic = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/inputlogic/InputLogic.java",
        ).readText()
        val commitBody = inputLogic.substringAfter("public boolean commitPredictedWord(")
            .substringBefore("/** Allocation-free suffix test over the cached text")
        // Collapsed selection — the same check replaceTrailingWord makes.
        assertTrue(commitBody.contains("mConnection.hasSelection()"))
        // No letter right after the cursor — the same check replaceTrailingWord makes.
        assertTrue(
            commitBody.contains(
                "TatarWordUtils.startsWithWordCharacter(mConnection.getCachedTextAfterCursor())",
            ),
        )
        // An EMPTY trailing word, not a matching non-empty prefix like replaceTrailingWord checks —
        // NEXT_WORD only ever applies when the prefix is empty.
        assertTrue(
            commitBody.contains(
                "TatarWordUtils.extractTrailingWord(mConnection.getCachedTextBeforeCursor()).isEmpty()",
            ),
        )
        // The live context word, re-extracted by the exact same algorithm the request was built
        // with — cache-boundary knowledge included (docs/NEXTWORD-RACE.md): the tap path and the
        // request path must agree on a first-word-of-field context, or the tap would be refused
        // as stale — matching expectedContextWord. The knowledge is the connection's provenance
        // flag (audit 2026-09-02, C6), NOT a length re-derivation, which local cache mutations
        // (a cursor swipe re-slicing the window, a long backspace run) make lie.
        assertTrue(
            commitBody.contains(
                "TatarWordUtils.extractNextWordContext(mConnection.getCachedTextBeforeCursor(),",
            ),
        )
        assertTrue(
            commitBody.contains(
                "mConnection.cacheReachedTextStart()",
            ),
        )
        assertTrue(commitBody.contains("expectedContextWord.equals(liveContext)"))
    }

    @Test
    fun theCacheBoundaryKnowledgeIsProvenanceNotLengthInference() {
        // Audit 2026-09-02, C6: after a local cache mutation (a setSelection re-slice, a
        // deleteTextBeforeCursor truncation) the cache's length proves nothing about the text
        // start, so the only place allowed to compare it against the window size is the reload
        // seam inside RichInputConnection. The two consumers read the provenance flag.
        for (file in listOf("latin/LatinIME.java", "latin/inputlogic/InputLogic.java")) {
            val source = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/$file").readText()
            assertFalse(
                "$file must read RichInputConnection.cacheReachedTextStart(), not the length",
                source.contains("EDITOR_CONTENTS_CACHE_SIZE"),
            )
        }
    }

    @Test
    fun noSeparateNextWordPredictionToggleExistsAnywhere() {
        // PROPOSALS.md, "E5d. Отдельного тумблера предсказаний НЕТ": prediction is governed by the
        // existing PREF_TATAR_SUGGESTIONS, not by a new PREF_NEXT_WORD_PREDICTION key, reader,
        // SettingsValues field, switchRow string, or translation — the same logic E4 already applied
        // to reject two personal-dictionary toggles.
        val settingsRoot = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/settings")
        val settings = File(settingsRoot, "Settings.java").readText()
        val settingsValues = File(settingsRoot, "SettingsValues.java").readText()
        val settingsHostActivity = File(settingsRoot, "SettingsHostActivity.kt").readText()
        val stringsEn = File(sourceRoot(), "res/values/strings.xml").readText()
        val stringsTt = File(sourceRoot(), "res/values-tt/strings.xml").readText()

        val forbiddenNeedles = listOf(
            "PREF_NEXT_WORD_PREDICTION",
            "next_word_prediction",
            "pref_next_word_prediction",
        )
        val sources = mapOf(
            "Settings.java" to settings,
            "SettingsValues.java" to settingsValues,
            "SettingsHostActivity.kt" to settingsHostActivity,
            "values/strings.xml" to stringsEn,
            "values-tt/strings.xml" to stringsTt,
        )
        for ((fileName, text) in sources) {
            for (needle in forbiddenNeedles) {
                assertFalse(
                    "$fileName must not mention a separate prediction toggle ($needle)",
                    text.contains(needle),
                )
            }
        }
    }

    @Test
    fun successfulTapRefreshesTheShiftStateLikeTypedInputDoes() {
        val latinIme = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        ).readText()
        // A tap never builds an InputTransaction, so updateStateAfterInputTransaction() cannot
        // recompute auto-caps for it; the surface has to make the same call itself.
        val commitSurfaceBody = latinIme.substringAfter("public boolean commitSuggestion(")
            .substringBefore("public boolean hasKnownCursor()")

        assertTrue(commitSurfaceBody.contains("mInputLogic.commitChosenSuggestion"))
        assertTrue(
            commitSurfaceBody.contains(
                "mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),",
            ),
        )
        assertTrue(commitSurfaceBody.contains("getCurrentRecapitalizeState())"))
    }

    @Test
    fun theOfferTriggerRunsEveryKeyPressThroughTheWordFinishingPredicate() {
        val latinIme = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java",
        ).readText()
        val triggerBody = latinIme.substringAfter("private void maybeOfferTatarSuggestions(")
            .substringBefore("// A helper method to split the code point")

        // The decision must not be "the editor calls this a word separator" on its own: Enter and
        // Tab are separators and would spend the one-shot offer on a keystroke that hides the
        // keyboard. Routing through the controller keeps that rule in one JVM-tested place.
        assertTrue(triggerBody.contains("mSuggestionsOffer.onKeyPressCommitted("))
        assertFalse(triggerBody.contains("onWordSeparatorCommitted"))
        val offerController = File(
            sourceRoot(),
            "java/rkr/simplekeyboard/inputmethod/latin/suggestions/SuggestionsOfferController.kt",
        ).readText()
        val predicateBody = offerController.substringAfter("fun isWordFinishingKeyPress(")
            .substringBefore("}")
        assertTrue(predicateBody.contains("codePoint != Constants.CODE_ENTER"))
        assertTrue(predicateBody.contains("codePoint != Constants.CODE_TAB"))
    }

    @Test
    fun noPersonalizedLearningIsReadFromImeOptionsAndHonouredByBothSuggestionPredicates() {
        val javaRoot = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin")
        val inputAttributes = File(javaRoot, "InputAttributes.java").readText()
        val latinIme = File(javaRoot, "LatinIME.java").readText()

        // Every other attribute of the class comes from inputType; this one can only come from
        // imeOptions, because an incognito field's inputType is an ordinary text one.
        assertTrue(
            inputAttributes.contains(
                "0 != (imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)",
            ),
        )
        // Assigned before the early return of the non-TYPE_CLASS_TEXT branch, so it holds for every
        // input class rather than for text fields only.
        assertTrue(
            inputAttributes.indexOf("mNoPersonalizedLearning = readNoPersonalizedLearning(")
                in 0 until inputAttributes.indexOf("if (inputClass != InputType.TYPE_CLASS_TEXT)"),
        )
        // Settings are reused when isSameInputType() says the field did not change. An app flipping
        // its own incognito switch calls restartInput() with the same inputType and only imeOptions
        // changed, so the flag has to take part in that comparison or the permissive attributes of
        // the previous field survive into the incognito one.
        assertTrue(
            inputAttributes.substringAfter("public boolean isSameInputType(")
                .substringBefore("public String toString()")
                .contains("readNoPersonalizedLearning(editorInfo) == mNoPersonalizedLearning"),
        )

        // Both predicates, because they stop different things: the first one the one-shot dialog
        // (and, before it, any read of the field's text), the second one the reserved strip and
        // every prefix lookup.
        val offerBody = latinIme.substringAfter("public boolean editorAllowsSuggestions()")
            .substringBefore("public boolean isUserUnlocked()")
        val eligibilityBody = latinIme
            .substringAfter("private boolean isTatarSuggestionsEligible(final boolean")
            .substringBefore("public void onDestroy()")
        listOf(offerBody, eligibilityBody).forEach { body ->
            assertTrue(
                body.contains("!settingsValues.mInputAttributes.mNoPersonalizedLearning"),
            )
        }
    }

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }
}

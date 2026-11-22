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

import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import android.view.ContextThemeWrapper
import org.json.JSONArray
import org.json.JSONObject
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import rkr.simplekeyboard.inputmethod.R
import rkr.simplekeyboard.inputmethod.keyboard.MoreKeysPanel
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingProxy
import rkr.simplekeyboard.inputmethod.keyboard.internal.TimerProxy
import rkr.simplekeyboard.inputmethod.latin.common.Constants
import rkr.simplekeyboard.inputmethod.latin.InputAttributes
import rkr.simplekeyboard.inputmethod.latin.Subtype
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FuzzyEditPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictGlideInventory
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.glide.GlideDecoder
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath
import rkr.simplekeyboard.inputmethod.latin.glide.GlideResult
import rkr.simplekeyboard.inputmethod.latin.settings.Settings
import rkr.simplekeyboard.inputmethod.latin.suggestions.GlideKeyGeometryBuilder
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils
import java.io.File
import java.nio.ByteBuffer

/**
 * Device negative controls through the real PointerTracker and production decoder: generated
 * behavior probes plus a fingerprint-pinned replay of every tracked Tatar negative record and its
 * paired ordinary positive. The action listener records code/text commits but deliberately forwards
 * none to an editor, so a failed control cannot type into a test activity or a user's field. Every
 * trial reports arming, glide delivery, decoded candidates, and commit outcomes through logcat and
 * instrumentation status.
 */
class GlideNegativeControlDeviceTest : InstrumentationTestCase() {
    private var savedSuggestions = false
    private var savedGlide = false
    private var armed = 0
    private val delivered = ArrayList<GlidePath>()
    private val codeEvents = ArrayList<Int>()
    private val textEvents = ArrayList<String>()

    private lateinit var keyboard: Keyboard
    private lateinit var keyDetector: KeyDetector
    private lateinit var decoder: GlideDecoder

    private val recorder = object : KeyboardActionListener.Adapter() {
        override fun onGlideArmed() {
            armed++
        }

        override fun onGlideInput(path: GlidePath) {
            val copy = GlidePath(path.size)
            path.copyInto(copy)
            delivered.add(copy)
        }

        override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
            codeEvents.add(primaryCode)
        }

        override fun onTextInput(rawText: String) {
            textEvents.add(rawText)
        }
    }

    private val timerProxy = object : TimerProxy {
        override fun startTypingStateTimer(typedKey: Key?) {}
        override fun isTypingState(): Boolean = false
        override fun startKeyRepeatTimerOf(tracker: PointerTracker?, repeatCount: Int, delay: Int) {}
        override fun startLongPressTimerOf(tracker: PointerTracker?, delay: Int) {}
        override fun cancelLongPressTimersOf(tracker: PointerTracker?) {}
        override fun cancelLongPressShiftKeyTimer() {}
        override fun cancelKeyTimersOf(tracker: PointerTracker?) {}
        override fun startDoubleTapShiftKeyTimer() {}
        override fun cancelDoubleTapShiftKeyTimer() {}
        override fun isInDoubleTapShiftKeyTimeout(): Boolean = false
    }

    private val drawingProxy = object : DrawingProxy {
        override fun onKeyPressed(key: Key?, withPreview: Boolean) {}
        override fun onKeyReleased(key: Key?, withAnimation: Boolean) {}
        override fun showMoreKeysKeyboard(key: Key?, tracker: PointerTracker?): MoreKeysPanel? = null
        override fun startWhileTypingAnimation(fadeInOrOut: Int) {}
        override fun onGlideTrailPoint(x: Float, y: Float, eventTime: Long) {}
        override fun onGlideTrailEnd() {}
    }

    override fun setUp() {
        val context = instrumentation.targetContext
        val prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
            .getDeviceSharedPreferences(context)
        savedSuggestions = prefs.getBoolean("pref_tatar_suggestions", false)
        savedGlide = prefs.getBoolean("pref_glide_typing", true)
        prefs.edit()
            .putBoolean("pref_tatar_suggestions", true)
            .putBoolean("pref_glide_typing", true)
            .commit()
        Settings.init(context)
        Settings.getInstance().loadSettings(InputAttributes(EditorInfo()))
        PointerTracker.init(
            context.theme.obtainStyledAttributes(R.styleable.MainKeyboardView),
            timerProxy,
            drawingProxy,
        )
        PointerTracker.setKeyboardActionListener(recorder)
        PointerTracker.setGlideAvailable(true)

        val themed = ContextThemeWrapper(context, R.style.KeyboardTheme_Tatar)
        val resources = themed.resources
        val builtKeyboard: Keyboard = requireNotNull(
            KeyboardLayoutSet.Builder(themed, null)
                .setKeyboardTheme(KeyboardTheme.THEME_ID_TATAR)
                .setKeyboardGeometry(
                    resources.displayMetrics.widthPixels,
                    ResourceUtils.getDefaultKeyboardHeight(resources),
                    0,
                )
                .setSubtype(Subtype("tt_RU", "tatar", "tatar", false, resources))
                .setLanguageSwitchKeyEnabled(true)
                .setShowSpecialChars(true)
                .setShowNumberRow(false)
                .setShowEmojiKey(false)
                .build()
                .getKeyboard(KeyboardId.ELEMENT_ALPHABET)
        ) { "failed to build the Tatar alphabet keyboard" }
        keyboard = builtKeyboard
        // Pass the local explicitly: inside KeyDetector.apply, a bare `keyboard` resolves to
        // the receiver's own getKeyboard(), which is null until this call sets it.
        keyDetector = KeyDetector(
            dimen(R.dimen.config_key_hysteresis_distance),
            dimen(R.dimen.config_key_hysteresis_distance_for_sliding_modifier),
            dimen(R.dimen.config_sliding_modifier_slop),
        ).apply { setKeyboard(builtKeyboard, 0f, 0f) }
        PointerTracker.setKeyDetector(keyDetector)
        decoder = GlideDecoder(
            GlideKeyGeometryBuilder.fromKeyboard(keyboard),
            TdictGlideInventory(openIndex(inflateTatarDictionary())),
        )
        resetOutcomes()
    }

    override fun tearDown() {
        PointerTracker.setKeyboardActionListener(KeyboardActionListener.Adapter.EMPTY_LISTENER)
        PointerTracker.cancelAllPointerTrackers()
        PointerTracker.setGlideAvailable(false)
        val prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat
            .getDeviceSharedPreferences(instrumentation.targetContext)
        prefs.edit()
            .putBoolean("pref_tatar_suggestions", savedSuggestions)
            .putBoolean("pref_glide_typing", savedGlide)
            .commit()
    }

    fun testTapFallsBackToTheCorrectKeyAndNeverArmsAGlide() {
        val expectedCode = 'с'.code
        val start = center('с')
        drive(listOf(start), MotionEvent.ACTION_UP)
        val outcome = record("tap", expectedCode = expectedCode)
        assertEquals("a tap must produce one ordinary key event", listOf(expectedCode), outcome.codeEvents)
        assertEquals("a tap must not be delivered as a glide", 0, outcome.delivered)
    }

    fun testTremorRecordsArmCandidateAndCommitOutcomes() {
        val start = center('с')
        val points = ArrayList<Pair<Float, Float>>()
        points.add(start)
        val dx = key('с').width / 12f
        val dy = key('с').height / 12f
        repeat(6) { index ->
            val sign = if (index % 2 == 0) 1f else -1f
            points.add((start.first + sign * dx) to (start.second - sign * dy))
            points.add((start.first - sign * dx) to (start.second + sign * dy))
        }
        drive(points, MotionEvent.ACTION_UP)
        recordAndAssertNoUnexpectedCommit("tremor", terminalFallbackCode(points.last()))
    }

    fun testCanceledArmedGestureDeliversNothingAndCommitsNothing() {
        val points = listOf(center('с'), center('ә'), center('л'))
        drive(points, MotionEvent.ACTION_CANCEL, stopAfterArm = true)
        val armedCount = armed
        val deliveredCount = delivered.size
        recordAndAssertNoUnexpectedCommit("canceled")
        assertTrue("the cancellation probe must reach the armed state", armedCount > 0)
        assertEquals("a canceled glide must not be delivered", 0, deliveredCount)
    }

    fun testShortDragRecordsArmCandidateAndCommitOutcomes() {
        val start = center('с')
        val end = center('ә')
        val shortEnd = (start.first + (end.first - start.first) * 0.2f) to
            (start.second + (end.second - start.second) * 0.2f)
        drive(listOf(start, shortEnd), MotionEvent.ACTION_UP)
        recordAndAssertNoUnexpectedCommit("short_drag", terminalFallbackCode(shortEnd))
    }

    fun testUnintendedStartBetweenKeysRecordsArmCandidateAndCommitOutcomes() {
        val start = betweenKeys()
        val end = (start.first + key('с').width * 1.5f) to start.second
        drive(listOf(start, end), MotionEvent.ACTION_UP)
        recordAndAssertNoUnexpectedCommit("unintended_start", terminalFallbackCode(end))
    }

    private class PinnedRecord(val source: String, val json: JSONObject) {
        val recordId: String get() = json.getString("record_id")
    }

    fun testFrozenNegativeAndPairedPositiveRecordsReplayWithoutEditorCommits() {
        val manifest = loadJsonAsset("glide-negative-controls/manifest.json")
        val negatives = loadPinnedRecords("glide-negative-controls/frozen-negatives.jsonl")
        val positives = loadPinnedRecords("glide-negative-controls/frozen-positive-pairs.jsonl")
        assertEquals(29, negatives.size)
        assertEquals(manifest.getJSONArray("records").length(), negatives.size)
        assertEquals(manifest.getJSONArray("positive_records").length(), positives.size)
        for (index in negatives.indices) {
            verifyPinnedLine(negatives[index], manifest.getJSONArray("records").getJSONObject(index))
        }
        for (index in positives.indices) {
            verifyPinnedLine(
                positives[index],
                manifest.getJSONArray("positive_records").getJSONObject(index),
            )
            assertEquals("ordinary paired source", "ordinary", positives[index].json.getString("trial_kind"))
        }
        val negativeById = negatives.associateBy { it.recordId }
        val positiveById = positives.associateBy { it.recordId }
        // The non-tap negatives whose delivered path decodes to a top candidate — the exact set
        // production would commit on lift. The expected residual set is pinned exactly: a new
        // projected negative, or the disappearance of a residual record, fails and forces review.
        val projectedCommitNegatives = HashSet<String>()
        for (index in 0 until manifest.getJSONArray("pairs").length()) {
            val pair = manifest.getJSONArray("pairs").getJSONObject(index)
            val negative = requireNotNull(negativeById[pair.getString("negative_record_id")]) {
                "missing frozen negative ${pair.getString("negative_record_id")}"
            }
            val positive = requireNotNull(positiveById[pair.getString("positive_record_id")]) {
                "missing paired positive ${pair.getString("positive_record_id")}"
            }
            resetOutcomes()
            val trialKind = negative.json.getString("trial_kind")
            val points = replayCoordinates(negative.json)
            val fallbackCode = when (trialKind) {
                "canceled" -> Constants.NOT_A_CODE
                // Tap records are replayed at their recorded down point; non-tap records end
                // at the last recorded point, which determines any ordinary fallback key.
                "tap" -> terminalFallbackCode(points.first())
                else -> terminalFallbackCode(points.last())
            }
            val outcome = replayRecord(negative, expectedCode = fallbackCode)
            if (trialKind == "tap") {
                assertEquals(
                    "${outcome.trial} must commit its tapped key once",
                    listOf(fallbackCode),
                    outcome.codeEvents,
                )
                assertEquals("tap must not deliver a glide", 0, outcome.delivered)
                assertEquals(
                    "${outcome.trial} projected a glide commit",
                    0,
                    outcome.projectedCommitCount,
                )
            } else {
                // Every non-tap negative must project ZERO top-candidate commits: production
                // commits the decode's top word on lift (SuggestionsController.applyGlideResult),
                // so any projected commit here is text the keyboard would type on an accidental
                // gesture. Seven records form the expected residual set — no input-side rule can
                // reject them without also rejecting their paired positives (tremor 83f39982-49
                // is a decode twin of 83f39982-35) or the top1-correct short-word glides (see
                // local://glide-false-commit-fix-result.md). The set is pinned both ways: any
                // NEW projected negative, or any residual record that stops projecting, fails
                // here and forces review.
                if (outcome.projectedCommitCount > 0) {
                    projectedCommitNegatives += negative.recordId
                }
                if (trialKind == "canceled") {
                    assertEquals(
                        "${outcome.trial} must never deliver a canceled glide",
                        0,
                        outcome.delivered,
                    )
                    assertEquals(
                        "${outcome.trial} projected a commit from a canceled glide",
                        0,
                        outcome.projectedCommitCount,
                    )
                }
            }
            if (outcome.armed > 0 || outcome.delivered > 0 ||
                fallbackCode == Constants.NOT_A_CODE
            ) {
                assertEquals("${outcome.trial} must not emit code events", emptyList<Int>(), outcome.codeEvents)
            } else {
                assertTrue(
                    "${outcome.trial} must commit only its terminal-key tap fallback: " +
                        outcome.codeEvents,
                    outcome.codeEvents.isEmpty() || outcome.codeEvents == listOf(fallbackCode),
                )
            }
            assertEquals("${outcome.trial} must not emit text", emptyList<String>(), outcome.textEvents)
            resetOutcomes()
            val positiveOutcome = replayRecord(positive)
            assertEquals(
                "${positiveOutcome.trial} must not emit text",
                emptyList<String>(),
                positiveOutcome.textEvents,
            )
            if (positiveOutcome.delivered > 0) {
                assertEquals(
                    "${positiveOutcome.trial} must not emit code events",
                    emptyList<Int>(),
                    positiveOutcome.codeEvents,
                )
                // Paired positives must retain their decoded candidates: today every delivered
                // positive decodes non-empty except 83f39982-136, whose device-mapped path
                // prunes to nothing (verified under the collector mapping, the device mapping,
                // recorded timestamps and live replay — an empty decode is its stable truth).
                if (positive.recordId == "83f39982-136") {
                    assertEquals(
                        "${positiveOutcome.trial} decodes empty today; candidates appeared",
                        0,
                        positiveOutcome.candidateCount,
                    )
                } else {
                    assertTrue(
                        "${positiveOutcome.trial} lost its decoded candidates",
                        positiveOutcome.candidateCount > 0,
                    )
                }
            } else {
                // An unarmed replay (23bdf97a-16's onset closes the 500 ms window under replay
                // sleeps) cancels the ordinary tap: no key events may leak either.
                assertEquals(
                    "${positiveOutcome.trial} must not emit code events when unarmed",
                    emptyList<Int>(),
                    positiveOutcome.codeEvents,
                )
            }
            resetOutcomes()
        }
        // The expected residual set, pinned exactly: a NEW projected negative is a
        // decider/decoder regression that would type a word on an accidental gesture; a residual
        // record that stops projecting means replay or decoder behavior moved and the pin is
        // stale. Both directions fail with the offending records so review has the full picture.
        val unexpected = projectedCommitNegatives - ACCEPTED_PROJECTED_COMMIT_NEGATIVES
        val missing = ACCEPTED_PROJECTED_COMMIT_NEGATIVES - projectedCommitNegatives
        assertTrue(
            "projected-commit negative set moved off the expected residual pin.\n" +
                "NEW projected commits (must force review): " +
                "${if (unexpected.isEmpty()) "<none>" else unexpected.sorted()}\n" +
                "expected residual records that no longer project (fix landed or replay moved): " +
                "${if (missing.isEmpty()) "<none>" else missing.sorted()}",
            unexpected.isEmpty() && missing.isEmpty(),
        )
    }

    private data class Outcome(
        val trial: String,
        val armed: Int,
        val delivered: Int,
        val codeEvents: List<Int>,
        val textEvents: List<String>,
        val unexpectedCodeEvents: Int,
        val candidateCount: Int,
        val candidates: List<String>,
        val projectedCommitCount: Int,
    )

    private fun record(
        trial: String,
        expectedCode: Int = Constants.NOT_A_CODE,
    ): Outcome {
        val candidates = ArrayList<String>()
        var projectedCommitCount = 0
        for (path in delivered) {
            val result = GlideResult()
            val count = decoder.decode(path, result)
            for (index in 0 until count) candidates.add(result.words[index]!!)
            if (count > 0) projectedCommitCount++
        }
        val remainingCode = codeEvents.toMutableList()
        if (expectedCode != Constants.NOT_A_CODE) {
            val expectedIndex = remainingCode.indexOf(expectedCode)
            if (expectedIndex >= 0) remainingCode.removeAt(expectedIndex)
        }
        val unexpectedText = textEvents
        val line = "NEGATIVE_CONTROL|trial=$trial|armed=$armed|delivered=${delivered.size}" +
            "|code_events=${codeEvents.size}|text_events=${textEvents.size}" +
            "|unexpected_code_events=${remainingCode.size}" +
            "|unexpected_text_events=${unexpectedText.size}" +
            "|candidate_count=${candidates.size}|candidates=${candidates.joinToString(",")}" +
            "|projected_top_candidate_commits=$projectedCommitCount"
        Log.i(TAG, line)
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("tt_glide_negative_control", line) },
        )
        val outcome = Outcome(
            trial = trial,
            armed = armed,
            delivered = delivered.size,
            codeEvents = codeEvents.toList(),
            textEvents = textEvents.toList(),
            unexpectedCodeEvents = remainingCode.size,
            candidateCount = candidates.size,
            candidates = candidates.toList(),
            projectedCommitCount = projectedCommitCount,
        )
        resetOutcomes()
        return outcome
    }

    private fun recordAndAssertNoUnexpectedCommit(
        trial: String,
        expectedCode: Int = Constants.NOT_A_CODE,
    ): Outcome {
        val outcome = record(trial, expectedCode)
        val validCodeEvents = if (outcome.armed > 0 || outcome.delivered > 0 ||
            expectedCode == Constants.NOT_A_CODE
        ) {
            outcome.codeEvents.isEmpty()
        } else {
            // An unarmed short movement may cancel the ordinary tap entirely. If it falls back
            // to a tap, it must commit exactly the key under the replay's terminal point.
            outcome.codeEvents.isEmpty() || outcome.codeEvents == listOf(expectedCode)
        }
        assertTrue(
            "$trial must commit only its terminal-key tap fallback: ${outcome.codeEvents}",
            validCodeEvents,
        )
        assertEquals("$trial produced text outside the keyboard event interface", emptyList<String>(), outcome.textEvents)
        // A synthetic non-tap probe must never arm-and-deliver a path whose decode would commit:
        // today every probe ends unarmed with nothing delivered, so any projected commit here is
        // a decider or decoder regression that would type a word on an accidental gesture.
        assertEquals(
            "$trial projected a top-candidate commit from a delivered glide path",
            0,
            outcome.projectedCommitCount,
        )
        return outcome
    }

    private fun resetOutcomes() {
        armed = 0
        delivered.clear()
        codeEvents.clear()
        textEvents.clear()
    }

    private fun loadJsonAsset(name: String): JSONObject = JSONObject(readAsset(name))

    private fun loadPinnedRecords(name: String): List<PinnedRecord> =
        readAsset(name).lineSequence().filter { it.isNotBlank() }.map {
            PinnedRecord(it, JSONObject(it))
        }.toList()

    private fun readAsset(name: String): String =
        instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }

    private fun verifyPinnedLine(record: PinnedRecord, pin: JSONObject) {
        assertEquals(pin.getString("record_id"), record.recordId)
        assertEquals(pin.getString("sha256"), sha256(record.source))
    }

    private fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun replayRecord(
        pinned: PinnedRecord,
        expectedCode: Int = Constants.NOT_A_CODE,
    ): Outcome {
        val json = pinned.json
        val points = replayCoordinates(json)
        val tracker = PointerTracker.getPointerTracker(0)
        process(tracker, MotionEvent.ACTION_DOWN, points.first(), SystemClock.uptimeMillis())
        if (json.getString("trial_kind") != "tap") {
            for (index in 1 until points.size) {
                val delay = json.getJSONArray("dt_ms").optDouble(index, 16.0).toLong()
                if (delay > 0) Thread.sleep(delay)
                process(tracker, MotionEvent.ACTION_MOVE, points[index], SystemClock.uptimeMillis())
            }
            val terminal = when (json.getString("trial_kind")) {
                "canceled" -> MotionEvent.ACTION_CANCEL
                else -> MotionEvent.ACTION_UP
            }
            process(tracker, terminal, points.last(), SystemClock.uptimeMillis())
        } else {
            Thread.sleep(60)
            process(tracker, MotionEvent.ACTION_UP, points.first(), SystemClock.uptimeMillis())
        }
        return record(
            "frozen:${json.getString("trial_kind")}:${pinned.recordId}",
            expectedCode = expectedCode,
        )
    }

    private fun replayCoordinates(json: JSONObject): List<Pair<Float, Float>> {
        val geometry = json.getJSONObject("key_geometry")
        val gridWidth = geometry.getInt("grid_width").toFloat()
        val radius = geometry.getDouble("key_radius").toFloat()
        val boardWidth = json.getInt("board_w").toFloat()
        val boardHeight = json.getInt("board_h").toFloat()
        check(boardWidth > 0f && boardHeight > 0f)
        val xs = json.getJSONArray("path_x")
        val ys = json.getJSONArray("path_y")
        assertEquals(xs.length(), ys.length())
        // Both axes are recorded in key-radius units. Horizontal coordinates map by occupied-width
        // fraction; vertical coordinates map the recorded board height (top padding plus letter rows)
        // onto the live keyboard's letter-row block, so frozen paths stay inside the live rows.
        val recordedHeightUnits = boardHeight / boardWidth * gridWidth
        var letterBottom = 0f
        for (key in keyboard.sortedKeys) {
            if (Character.isLetter(key.code)) {
                letterBottom = maxOf(letterBottom, (key.y + key.height).toFloat())
            }
        }
        check(recordedHeightUnits > 0f && letterBottom > 0f)
        return List(xs.length()) { index ->
            val x = xs.getDouble(index).toFloat() * radius / gridWidth * keyboard.mOccupiedWidth
            val y = ys.getDouble(index).toFloat() * radius / recordedHeightUnits * letterBottom
            x to y
        }
    }

    private fun drive(
        points: List<Pair<Float, Float>>,
        terminalAction: Int,
        stopAfterArm: Boolean = false,
    ) {
        val tracker = PointerTracker.getPointerTracker(0)
        val downTime = SystemClock.uptimeMillis()
        process(tracker, MotionEvent.ACTION_DOWN, points.first(), downTime)
        var current = points.first()
        for (segment in 0 until points.size - 1) {
            val from = points[segment]
            val to = points[segment + 1]
            for (step in 1..8) {
                Thread.sleep(16)
                current = (
                    from.first + (to.first - from.first) * step / 8f to
                        from.second + (to.second - from.second) * step / 8f
                )
                process(tracker, MotionEvent.ACTION_MOVE, current, SystemClock.uptimeMillis())
                if (stopAfterArm && armed > 0) {
                    process(tracker, terminalAction, current, SystemClock.uptimeMillis())
                    return
                }
            }
        }
        if (points.size == 1) Thread.sleep(60)
        process(tracker, terminalAction, current, SystemClock.uptimeMillis())
    }

    private fun process(
        tracker: PointerTracker,
        action: Int,
        point: Pair<Float, Float>,
        eventTime: Long,
    ) {
        val event = MotionEvent.obtain(eventTime, eventTime, action, point.first, point.second, 0)
        try {
            tracker.processMotionEvent(event, keyDetector)
        } finally {
            event.recycle()
        }
    }

    private fun key(letter: Char): Key = keyboard.sortedKeys.first {
        Character.isLetter(it.code) && Character.toLowerCase(it.code) == letter.code
    }

    private fun center(letter: Char): Pair<Float, Float> = key(letter).let {
        (it.x + it.width / 2f) to (it.y + it.height / 2f)
    }

    private fun betweenKeys(): Pair<Float, Float> {
        val keys = keyboard.sortedKeys.filter { Character.isLetter(it.code) }.sortedWith(
            compareBy<Key> { it.y }.thenBy { it.x }
        )
        for (index in 0 until keys.size - 1) {
            val left = keys[index]
            val right = keys[index + 1]
            if (left.y == right.y && right.x > left.x + left.width) {
                return (left.x + left.width + (right.x - left.x - left.width) / 2f) to
                    (left.y + left.height / 2f)
            }
        }
        throw AssertionError("no inter-key gap in the live Tatar keyboard")
    }

    private fun terminalFallbackCode(point: Pair<Float, Float>): Int =
        keyDetector.detectHitKey(point.first.toInt(), point.second.toInt())?.code ?: Constants.NOT_A_CODE

    private fun inflateTatarDictionary(): ByteArray {
        val context = instrumentation.targetContext
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val rawFile = File.createTempFile("glide-negative-", ".tdict", context.cacheDir)
        try {
            context.assets.open(spec.assetPath).use { input ->
                rawFile.outputStream().use { output ->
                    TdictValidator().inflateAsset(input, output, spec)
                }
            }
            TdictValidator().validateRaw(rawFile, spec)
            return rawFile.readBytes()
        } finally {
            rawFile.delete()
        }
    }

    private fun openIndex(raw: ByteArray): TdictPrefixIndex {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val identity = DictionaryIdentity(
            spec.generation, spec.schemaId, spec.formatVersion, spec.expectedRawSha256,
        )
        return requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(raw), identity, spec.expectedEntryCount, raw.size.toLong(),
                null, FuzzyEditPolicy.DEFAULT, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
            ),
        )
    }

    private fun dimen(id: Int): Float = instrumentation.targetContext.resources.getDimension(id)

    companion object {
        private const val TAG = "GlideNegative"

        /**
         * The expected residual negative set: the seven frozen non-tap negatives whose
         * delivered path decodes to a top candidate, so production commits a word on lift.
         * Five short drags (gesture length overlaps the top1-correct `ми`/`ин`/`юл`/`өч`
         * glide envelope) and two tremors (83f39982-49 is a decode twin of its paired positive
         * 83f39982-35); no input-side rule separates them without rejecting valid glides — see
         * local://glide-false-commit-fix-result.md. The negative-control test pins the observed
         * set to this exact list in both directions.
         */
        private val ACCEPTED_PROJECTED_COMMIT_NEGATIVES = setOf(
            "23bdf97a-102",
            "23bdf97a-52",
            "83f39982-106",
            "83f39982-162",
            "83f39982-51",
            "83f39982-104",
            "83f39982-49",
        )
    }
}

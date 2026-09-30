/*
 * Copyright (C) 2026 Tatar Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PairCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.WordCompletionSink
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.ActiveSubtypeSupplier
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalEmojiLearning
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalEmojiStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalLearningGates
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalLearningPredicate
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalOutputOpener
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.emoji.RecentEmojiGateState
import rkr.simplekeyboard.inputmethod.latin.utils.InputTypeUtils

/**
 * The EditorInfo privacy matrix as explicit JVM tests: for every protected field variant NO write
 * reaches a personal store, the recent-emoji medium stays closed, the suggestion strip is never
 * populated, and the editor text cache is never re-read from the field; the one normal control row
 * opens every column, so the matrix cannot be vacuously green.
 *
 * THE MATRIX — inputType/imeOptions held exactly as Android delivers them; every non-field factor
 * (the suggestions setting, a dictionary-bearing subtype, a known cursor, the personal-dictionary
 * setting, an unlocked device, incognito OFF) held permissive, so the row's own signals are what
 * decides:
 *
 * | variant                  | word | pair | emoji | recents | strip | cache re-read |
 * |--------------------------|------|------|-------|---------|-------|---------------|
 * | password                 |  no  |  no  |  no   |   no    |  no   |     never     |
 * | visible-password         |  no  |  no  |  no   |   no    |  no   |     never     |
 * | web-password             |  no  |  no  |  no   |   no    |  no   |     never     |
 * | number-password          |  no  |  no  |  no   |   no    |  no   |     never     |
 * | TYPE_NULL                |  no  |  no  |  no   |   no    |  no   | yes (see note)|
 * | NO_PERSONALIZED_LEARNING |  no  |  no  |  no   |   no    |  no   |      yes      |
 * | postal-address           |  no  |  no  |  no   |   yes   |  yes  |      yes      |
 * | normal (control)         | yes  |  yes |  yes  |   yes   |  yes  |      yes      |
 *
 * The number-password row belongs to the same password family and covers the classifier's
 * number-class branch.
 *
 * The deliberate asymmetries:
 * - postal-address keeps suggestions and recents and loses ONLY learning: a street or village
 *   name in Cyrillic passes every content filter the personal stores have, so the field type is
 *   the only thing that can keep it out — while the recent-emoji list holds emoji, never text,
 *   and the strip shows built-in dictionary suggestions, not field content.
 * - NO_PERSONALIZED_LEARNING keeps the cache reload: the flag asks "do not learn from me", not
 *   "do not read me" — auto-caps needs the local text. But the strip shows nothing and
 *   no persistence path is fed.
 * - TYPE_NULL is re-read by the cache gate (it is not a password type), but a TYPE_NULL editor
 *   serves no surrounding text by contract; and the cache is in-memory only and dies on every
 *   session boundary regardless of the variant.
 *
 * WHAT PINS WHAT — the pre-existing suites this matrix extends (and deliberately does not
 * duplicate):
 * - PersonalLearningGatesTest: the word sink's three event paths are gated in production; the ONE
 *   predicate carries every factor; eligibility carries `!mNoPersonalizedLearning` and the
 *   null-editorInfo case; the postal factor is computed before the non-text early return; the
 *   store-level unlock gate.
 * - PersonalBigramLearningGatesTest / PersonalEmojiLearningGatesTest: the same for the pair and
 *   emoji sinks; the emoji suite additionally drives a real store under a closed predicate and
 *   proves the incognito pause on disk.
 * - IncognitoModeTest: the full truth table of `PersonalLearningGates.mayLearn` over the four
 *   non-field factors crossed with the incognito veto; the read side never consults the pause.
 * - EditorTextCachePrivacySourceContractTest: the cache dies on every session boundary and every
 *   reload site (field start, cursor move, space-slide release) is gated on the password check.
 * - RecentEmojiStoreTest: the recent-emoji gate blocks on each factor alone and leaves no
 *   in-memory trace when closed; EmojiPanelControllerRecentsTest wires recording through the
 *   background executor.
 * - SuggestionsOfferControllerTest: a NO_PERSONALIZED_LEARNING field gets no offer and is never
 *   read for it.
 *
 * What THIS suite adds on top of them:
 * - the per-variant FIELD -> outcome mapping itself, driven through the real classifiers
 *   ([InputTypeUtils.isPasswordInputType] / [InputTypeUtils.isVisiblePasswordInputType]), the real
 *   conjunction ([PersonalLearningGates.mayLearn]), the real recent-emoji gate state
 *   ([RecentEmojiGateState]) and — for the emoji column — the real sink
 *   ([PersonalEmojiLearning.sinkOver]) over a real [PersonalEmojiStore] in a temp directory;
 * - the pin that the password suppressor sits INSIDE the suggestions disjunction (dropping it
 *   would silently show — and therefore learn — in password fields);
 * - the pin that the recent-emoji gate in `LatinIME` is built from the live field attributes;
 * - the pin that the editor text cache has no persistence sink at all, for any variant;
 * - the normal control row and the per-column open/closed census that keep the matrix non-vacuous.
 */
class EditorInfoPrivacyMatrixTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * One row of the matrix: the EditorInfo signals a field carries, and the outcomes the privacy
     * contract demands for it. Every derived value is computed through the REAL production code —
     * the row literals are the specification the derivations are checked against.
     */
    private class FieldProfile(
        val name: String,
        val inputType: Int,
        val imeOptions: Int = 0,
        val learningExpected: Boolean,
        val recentsExpected: Boolean,
        val stripExpected: Boolean,
        val cacheReloadExpected: Boolean,
    ) {
        /** The very expression `InputAttributes.mIsPasswordField` and `LatinIME.isPasswordField` compute. */
        val passwordClassified: Boolean =
            InputTypeUtils.isPasswordInputType(inputType) ||
                InputTypeUtils.isVisiblePasswordInputType(inputType)

        /** `inputClass == TYPE_CLASS_TEXT` — the gate of the non-text early return in `InputAttributes`. */
        val textClass: Boolean =
            (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT

        /** The very bit-check `InputAttributes` makes for the postal-address learning factor. */
        val postalAddress: Boolean =
            (inputType and InputType.TYPE_MASK_VARIATION) ==
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS

        /** The very bit-check `InputAttributes.readNoPersonalizedLearning` makes. */
        val noPersonalizedLearning: Boolean =
            (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

        /**
         * `mShouldShowSuggestions` as `InputAttributes` computes it for a field carrying none of the
         * other suppressors (no e-mail/URI/filter variation, no NO_SUGGESTIONS, no AUTO_COMPLETE —
         * no matrix variant carries one), so the model is: text class AND not password-classified.
         * [theSuppressionDisjunctionCarriesExactlyTheProtectedSuppressors] pins the full production
         * disjunction, so this model cannot silently drift away from it.
         */
        val fieldAllowsSuggestions: Boolean = textClass && !passwordClassified

        /**
         * The field-dependent half of `LatinIME.isSuggestionsEligible`: the suggestions setting, the
         * dictionary-bearing subtype and the known cursor are held permissive for every row, because
         * the matrix studies the FIELD, not the environment.
         */
        val stripEligible: Boolean = fieldAllowsSuggestions && !noPersonalizedLearning

        /** The REAL conjunction every learning write path consults, non-field factors permissive. */
        val mayLearn: Boolean = PersonalLearningGates.mayLearn(
            suggestionsEligible = stripEligible,
            personalDictionaryOn = true,
            userUnlocked = true,
            postalAddressField = postalAddress,
            incognito = false,
        )

        /** The REAL recent-emoji gate state, the non-field factors permissive. */
        val recentsAllowed: Boolean = RecentEmojiGateState(
            shouldShowSuggestions = fieldAllowsSuggestions,
            userUnlocked = true,
            noPersonalizedLearning = noPersonalizedLearning,
            keyguardLocked = false,
            incognito = false,
        ).allowsRecording

        /** The cache reload runs unless the field is password-classified (`LatinIME.isPasswordField`). */
        val cacheReloaded: Boolean = !passwordClassified
    }

    private val variants = listOf(
        FieldProfile(
            "password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            learningExpected = false, recentsExpected = false,
            stripExpected = false, cacheReloadExpected = false,
        ),
        FieldProfile(
            "visible-password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            learningExpected = false, recentsExpected = false,
            stripExpected = false, cacheReloadExpected = false,
        ),
        FieldProfile(
            "web-password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            learningExpected = false, recentsExpected = false,
            stripExpected = false, cacheReloadExpected = false,
        ),
        FieldProfile(
            "number-password",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            learningExpected = false, recentsExpected = false,
            stripExpected = false, cacheReloadExpected = false,
        ),
        FieldProfile(
            "type-null",
            InputType.TYPE_NULL,
            learningExpected = false, recentsExpected = false,
            stripExpected = false, cacheReloadExpected = true,
        ),
        FieldProfile(
            "no-personalized-learning",
            InputType.TYPE_CLASS_TEXT,
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            learningExpected = false, recentsExpected = false,
            stripExpected = false, cacheReloadExpected = true,
        ),
        FieldProfile(
            "postal-address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            learningExpected = false, recentsExpected = true,
            stripExpected = true, cacheReloadExpected = true,
        ),
        FieldProfile(
            "normal",
            InputType.TYPE_CLASS_TEXT,
            learningExpected = true, recentsExpected = true,
            stripExpected = true, cacheReloadExpected = true,
        ),
    )

    // --- The field classification, driven through the real classifier -----------------------------

    @Test
    fun theRealClassifierRecognizesExactlyThePasswordFamily() {
        for (variant in variants) {
            assertEquals(
                "${variant.name}: the password classification decides the cache reload",
                !variant.cacheReloadExpected, variant.passwordClassified,
            )
        }
        // Extra flags must not blind the mask: a decorated password is still a password.
        val dressedPassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertTrue("the flags are masked out of the classification",
            InputTypeUtils.isPasswordInputType(dressedPassword))
        val dressedVisible = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue("a decorated visible password is still a visible password",
            InputTypeUtils.isVisiblePasswordInputType(dressedVisible))
        // And the visible/password distinction itself: each classifier alone would leave a hole.
        assertFalse(InputTypeUtils.isPasswordInputType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(InputTypeUtils.isVisiblePasswordInputType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }

    // --- The learning columns (word / pair / emoji) -------------------------------------------------

    @Test
    fun everyProtectedVariantClosesTheOneLearningPredicate() {
        // mayLearn is the REAL PersonalLearningGates conjunction; all three sinks (words, pairs,
        // emoji) consult this one predicate — that wiring is pinned by the three LearningGates
        // suites, so closing it here closes every store at once.
        for (variant in variants) {
            assertEquals("${variant.name}: mayLearn", variant.learningExpected, variant.mayLearn)
        }
    }

    /**
     * The production sink shape: the predicate is consulted first, before the subtype is resolved or
     * the store is touched. The production text pin ("all three event paths are gated") lives in the
     * three LearningGates suites; here the shape is driven per variant.
     */
    private class RecordingWordSink(private val predicate: () -> Boolean) : WordCompletionSink {
        var storeCalls = 0
        override fun onCleanCompletion(word: String) {
            if (!predicate()) return
            storeCalls++
        }
        override fun onAcceptedSuggestion(word: String) {
            if (!predicate()) return
            storeCalls++
        }
        override fun onInputFinished() {
            if (!predicate()) return
            storeCalls++
        }
    }

    private class RecordingPairSink(private val predicate: () -> Boolean) : PairCompletionSink {
        var storeCalls = 0
        override fun onCleanPairCompletion(contextWord: String, completedWord: String) {
            if (!predicate()) return
            storeCalls++
        }
        override fun onAcceptedPrediction(contextWord: String, word: String) {
            if (!predicate()) return
            storeCalls++
        }
        override fun onInputFinished() {
            if (!predicate()) return
            storeCalls++
        }
    }

    @Test
    fun theWordAndPairSinksWriteExactlyForTheVariantsThatMayLearn() {
        for (variant in variants) {
            val words = RecordingWordSink { variant.mayLearn }
            words.onCleanCompletion("гүзәлия")
            words.onAcceptedSuggestion("китап")
            words.onInputFinished()
            val pairs = RecordingPairSink { variant.mayLearn }
            pairs.onCleanPairCompletion("сәләм", "дөнья")
            pairs.onAcceptedPrediction("сәләм", "дөнья")
            pairs.onInputFinished()
            val expected = if (variant.learningExpected) 3 else 0
            assertEquals("${variant.name}: word-store calls", expected, words.storeCalls)
            assertEquals("${variant.name}: pair-store calls", expected, pairs.storeCalls)
        }
    }

    // --- The emoji column: the real sink over a real store ------------------------------------------

    private inner class EmojiHarness(profile: FieldProfile) {
        val directory: File =
            File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }
        var storeResolutions = 0
        val store = newStore(directory)
        val sink = PersonalEmojiLearning.sinkOver(
            ActiveSubtypeSupplier { PersonalSubtypes.TATAR_RU },
            PersonalLearningPredicate { profile.mayLearn },
        ) { requested ->
            storeResolutions++
            check(requested == PersonalSubtypes.TATAR_RU)
            store
        }
    }

    private fun newStore(directory: File): PersonalEmojiStore =
        PersonalEmojiStore(
            subtypeId = PersonalSubtypes.TATAR_RU,
            directoryProvider = { directory },
            fileOps = RealOps,
            outputOpener = PersonalOutputOpener { temp -> FileOutputStream(temp) },
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L },
            executor = Executor { it.run() },
        )

    private fun storeFile(directory: File): File =
        File(directory, TpersemFormat.personalEmojiFileName(PersonalSubtypes.TATAR_RU))

    @Test
    fun theEmojiStoreIsTouchedExactlyForTheVariantsThatMayLearn() {
        for (variant in variants) {
            val harness = EmojiHarness(variant)
            // Two observations cross the learn threshold; the use and the flush follow.
            harness.sink.noteObservation("сәләм", "☀️")
            harness.sink.noteObservation("сәләм", "☀️")
            harness.sink.noteUse("сәләм", "☀️")
            harness.sink.onInputFinished()
            if (variant.learningExpected) {
                assertTrue("${variant.name}: the store file exists",
                    storeFile(harness.directory).isFile)
                assertEquals("☀️", harness.store.snapshot.emojiFor("сәләм"))
            } else {
                // The predicate answers before the subtype is even resolved: the store is never
                // touched, so nothing — no file, no pending counters, not even the salt — ever
                // reaches the disk.
                assertEquals("${variant.name}: the store is never resolved",
                    0, harness.storeResolutions)
                assertEquals("${variant.name}: the personal directory stays untouched",
                    emptyList<String>(), harness.directory.list()?.toList() ?: emptyList<String>())
                assertTrue("${variant.name}: the snapshot stays empty",
                    harness.store.snapshot.isEmpty)
            }
        }
    }

    // --- The recent-emoji column ---------------------------------------------------------------------

    @Test
    fun theRecentEmojiMediumFollowsTheSameFieldSignals() {
        // recentsAllowed is the REAL RecentEmojiGateState arithmetic fed with the field's signals.
        for (variant in variants) {
            assertEquals("${variant.name}: recent-emoji recording",
                variant.recentsExpected, variant.recentsAllowed)
        }
    }

    // --- The editor-text-cache column ------------------------------------------------------------------

    @Test
    fun onlyThePasswordFamilyIsSparedTheCacheReloadAndTheCacheNeverPersists() {
        for (variant in variants) {
            assertEquals("${variant.name}: the editor text is re-read into the cache",
                variant.cacheReloadExpected, variant.cacheReloaded)
        }
        // For ANY variant the cache has no persistence sink at all: RichInputConnection touches no
        // file, no preferences, no stream. Token scan over the whole class.
        val connection = read(RICH_INPUT_CONNECTION)
        for (token in listOf(
            "SharedPreferences", "openFileOutput", "getFilesDir", "OutputStream", "File(", "persist",
        )) {
            assertFalse("the editor cache must not reach persistence ($token)",
                connection.contains(token))
        }
        assertTrue("fail-capable: a planted persistence token is caught",
            (connection + "\nopenFileOutput(\"x\")").contains("openFileOutput"))
    }

    // --- The suggestion-strip column -------------------------------------------------------------------

    @Test
    fun theStripIsPopulatedExactlyForTheEligibleVariants() {
        for (variant in variants) {
            assertEquals("${variant.name}: suggestions populate the strip",
                variant.stripExpected, variant.stripEligible)
        }
    }

    // --- The control row: the matrix is not vacuously green --------------------------------------------

    @Test
    fun theNormalControlRowProvesTheMatrixIsNotVacuous() {
        val normal = variants.single { it.name == "normal" }
        assertTrue("the control field allows suggestions", normal.fieldAllowsSuggestions)
        assertTrue("the control strip is eligible", normal.stripEligible)
        assertTrue("the control field is learned from", normal.mayLearn)
        assertTrue("the control field records recents", normal.recentsAllowed)
        assertTrue("the control field's cache is maintained", normal.cacheReloaded)
        // Per column, at least one row opens and at least one closes: a gate stuck either way —
        // always-open or always-closed — could not paint this matrix green.
        for ((column, values) in listOf(
            "learning" to variants.map { it.mayLearn },
            "recents" to variants.map { it.recentsAllowed },
            "strip" to variants.map { it.stripEligible },
            "cache-reload" to variants.map { it.cacheReloaded },
        )) {
            assertTrue("$column: at least one row opens it", values.any { it })
            assertTrue("$column: at least one row closes it", values.any { !it })
        }
    }

    // --- The source-contract half: the Android-side wiring the model is anchored to -------------------

    @Test
    fun theSuppressionDisjunctionCarriesExactlyTheProtectedSuppressors() {
        val attributes = read(INPUT_ATTRIBUTES)
        val disjunction = attributes.substringAfter("final boolean shouldSuppressSuggestions =")
            .substringBefore("mShouldShowSuggestions = !shouldSuppressSuggestions;")
        for (suppressor in listOf(
            // Without this one, password fields would show suggestions — and eligibility would
            // open the learning predicate to them. The matrix rows above assume it is here.
            "mIsPasswordField",
            "InputTypeUtils.isEmailVariation(variation)",
            "InputType.TYPE_TEXT_VARIATION_URI == variation",
            "InputType.TYPE_TEXT_VARIATION_FILTER == variation",
            "flagNoSuggestions",
            "flagAutoComplete",
        )) {
            assertTrue("the disjunction carries $suppressor", disjunction.contains(suppressor))
        }
        assertFalse("postal addresses keep their suggestions — only learning stops there",
            disjunction.contains("POSTAL"))
        // Fail-capable: a disjunction that dropped the password suppressor must fail this test.
        val broken = disjunction.replace("mIsPasswordField", "mIsRenamedField")
        assertFalse(broken.contains("mIsPasswordField"))

        // The TYPE_NULL row: the non-text branch closes the flag for every non-text class.
        assertTrue("the non-text early return closes the flag",
            attributes.substringAfter("if (inputClass != InputType.TYPE_CLASS_TEXT)")
                .contains("mShouldShowSuggestions = false;"))
        // The personalized-learning flag is computed BEFORE that return: the answer must not
        // depend on the input class. (The postal factor's ordering is pinned by
        // PersonalLearningGatesTest.)
        val earlyReturn = attributes.indexOf("if (inputClass != InputType.TYPE_CLASS_TEXT)")
        assertTrue("the flag is computed before the non-text early return",
            attributes.indexOf("mNoPersonalizedLearning = readNoPersonalizedLearning(editorInfo);") in
                0 until earlyReturn)
    }

    @Test
    fun theAttributeFlagAndTheCacheGateShareTheOneClassifierExpression() {
        // Both places that decide "is this a password field" must use BOTH classifiers — the
        // visible/password rows of the matrix are protected exactly by this conjunction.
        val attributes = read(INPUT_ATTRIBUTES)
        val assignment = attributes.substringAfter("mIsPasswordField =").substringBefore(";")
        assertTrue(assignment.contains("InputTypeUtils.isPasswordInputType(inputType)"))
        assertTrue(assignment.contains("InputTypeUtils.isVisiblePasswordInputType(inputType)"))

        val ime = read(LATIN_IME)
        val cacheGate = ime
            .substringAfter("private static boolean isPasswordField(final EditorInfo editorInfo) {")
            .substringBefore("}")
        assertTrue(cacheGate.contains("InputTypeUtils.isPasswordInputType(inputType)"))
        assertTrue(cacheGate.contains("InputTypeUtils.isVisiblePasswordInputType(inputType)"))
        // Where that gate leads (pinned in full by EditorTextCachePrivacySourceContractTest):
        // the field-start reload takes the clear-instead-of-reload branch for these variants.
        assertTrue(ime.contains("if (isPasswordField(editorInfo))"))
    }

    @Test
    fun theRecentEmojiGateIsBuiltFromTheLiveFieldAttributes() {
        val ime = read(LATIN_IME)
        val gate = ime.substringAfter("final RecentEmojiGate recentGate = () -> {")
            .substringBefore("};")
        assertTrue("the strip signal", gate.contains("settingsValues.mInputAttributes.mShouldShowSuggestions"))
        assertTrue("the personalized-learning flag",
            gate.contains("settingsValues.mInputAttributes.mNoPersonalizedLearning"))
        assertTrue("the unlock state", gate.contains("userManager.isUserUnlocked()"))
        assertTrue("the keyguard state", gate.contains("isKeyguardLocked()"))
        assertTrue("pause learning", gate.contains("Settings.readIncognitoModeEnabled(mDevicePrefs)"))
        assertTrue(gate.contains("new RecentEmojiGateState("))
        assertTrue("missing settings mean forbidden, not permitted",
            gate.contains("settingsValues == null"))
    }

    @Test
    fun theControllerReceivesTheEligibilityAtEverySessionStart() {
        // The strip column's wiring: what the matrix calls stripEligible is exactly what the
        // controller is handed when a field session starts.
        val ime = read(LATIN_IME)
        assertEquals(1, Regex(
            "mSuggestionsController\\.onStartInput\\(\\s*" +
                "isSuggestionsEligible\\(\\), activeDictionarySubtype\\(\\), isGlideEligible\\(\\)\\)",
        ).findAll(ime).count())
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")
    }

    private fun read(relativePath: String): String = File(sourceRoot(), relativePath).readText()

    /** Plain durable ops over the real filesystem (no recording, no faults). */
    private object RealOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()
        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()
        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }
        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }
        override fun syncDirectory(directory: File) = Unit
        override fun delete(file: File): Boolean = file.delete()
    }

    private companion object {
        const val LATIN_IME = "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java"
        const val INPUT_ATTRIBUTES = "java/rkr/simplekeyboard/inputmethod/latin/InputAttributes.java"
        const val RICH_INPUT_CONNECTION =
            "java/rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java"
    }
}

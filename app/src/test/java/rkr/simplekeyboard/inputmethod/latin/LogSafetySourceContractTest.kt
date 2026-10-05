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

package rkr.simplekeyboard.inputmethod.latin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Log-safety check for the input pipeline: a keystroke or committed text must never reach logcat.
 * Checked from source because these JVM tests have no Android framework to observe logcat with.
 * Scanned packages, `.java` and `.kt` alike:
 *
 *   * `keyboard/` (incl. `keyboard/internal/`)
 *   * `latin/` top level (LatinIME, RichInputConnection and the small root classes — the
 *     subpackages are scan roots of their own, so the root is not recursed)
 *   * `latin/dictionary/`
 *   * `latin/emoji/`
 *   * `latin/inputlogic/`
 *   * `latin/setup/`
 *   * `latin/suggestions/`
 *   * `latin/utils/`
 *
 * Four rules:
 *
 * 1. **Pinned call-site set.** The exact set of `Log.d/e/i/v/w/wtf/println` call sites, normalized
 *    as `path::trimmed first line`, each group justified in [EXPECTED_CALL_SITES]. Any addition,
 *    removal, or edit of a log statement in the pipeline fails the test. To add a pipeline log,
 *    confirm it carries no composing/committed text, then pin it here with its justification.
 *
 * 2. **Text-carrier denylist.** Every call site's full statement (continuation lines and string
 *    literals included — a literal like `"committing text: "` is a red flag too) is checked
 *    against name fragments that only travel with user text (see [TEXT_CARRIER_DENYLIST]).
 *    Updating the rule-1 pin does not bypass this rule. `text` also matches harmless substrings
 *    like `context`; reword the log rather than relaxing the list.
 *
 * 3. **Debug guards stay off.** Every `boolean DEBUG*` declaration in the scanned packages must
 *    resolve to the compile-time constant `false` (a chain into another DEBUG flag is followed).
 *    These flags guard AOSP's keystroke tracers in `PointerTracker` and `KeyboardState`. The
 *    tracers now log only key position, key kind and functional-key flags, but they once logged
 *    the typed character and committed text, so the flags stay pinned off.
 *
 * 4. **LatinIME's TRACE stays off.** `TRACE` gates the keystroke-position logs and the
 *    session-long method tracing there; the constant is pinned to `false`.
 *
 * Limits: this is a grep-level check. A rewrite like `if (DEBUG_LISTENER)` → `if (true)` trips
 * no rule here (code review catches that), and the comment stripper does not honor comment
 * openers inside string literals (none exist in the scanned tree; rule 1 pins the consequence).
 */
class LogSafetySourceContractTest {

    @Test
    fun scannedPackagesExistAndContainSources() {
        val files = scannedFiles()
        assertEquals("scan must cover every pinned package", SCAN_PACKAGES.size,
            files.map { it.packageDir }.distinct().size)
        assertTrue("the scanned packages must contain sources", files.isNotEmpty())
    }

    @Test
    fun logCallSiteSetIsExactlyTheReviewedSet() {
        val actual = scanCallSites().map { "${it.path}::${it.trimmedLine}" }
        assertEquals(
            "android.util.Log call sites drifted in the input pipeline. A log statement there is" +
                " a reviewed act: confirm it carries no composing/committed text (rule 2 still" +
                " applies), then update EXPECTED_CALL_SITES with the justification in the same" +
                " commit. See the class KDoc.",
            EXPECTED_CALL_SITES.joinToString("\n"),
            actual.joinToString("\n"),
        )
    }

    @Test
    fun noLogStatementMentionsTextCarryingNames() {
        for (site in scanCallSites()) {
            val trimmed = site.statement.trimEnd()
            assertTrue(
                "log statement extraction broke at ${site.path}:${site.line}",
                trimmed.endsWith(";") || (site.path.endsWith(".kt") && trimmed.endsWith(")")),
            )
            val lowered = site.statement.lowercase()
            for (token in TEXT_CARRIER_DENYLIST) {
                if (token in lowered) {
                    fail(
                        "Log statement at ${site.path}:${site.line} mentions text-carrying" +
                            " name '$token'; keystrokes/committed text must never reach logcat." +
                            " Statement:\n${site.statement}",
                    )
                }
            }
        }
    }

    @Test
    fun theLatinImeTraceFlagStaysDisabled() {
        // Rule 4: TRACE gates LatinIME's keystroke-position logs and the session-long
        // Debug.startMethodTracing, so the constant is pinned off like the DEBUG* flags of rule 3.
        val file = File(sourceRoot(), "java/rkr/simplekeyboard/inputmethod/latin/LatinIME.java")
        assertTrue(
            "LatinIME.TRACE must stay a compile-time false",
            file.readText().contains("private static final boolean TRACE = false;"),
        )
    }

    @Test
    fun debugFlagsGuardingPipelineLogsStayDisabled() {
        val declarations = ArrayList<DebugFlag>()
        for (file in scannedFiles()) {
            for ((index, line) in file.logicalLines.withIndex()) {
                for (match in DEBUG_FLAG_DECLARATION.findAll(line)) {
                    declarations.add(DebugFlag(
                        name = match.groupValues[1],
                        initializer = match.groupValues[2].trim(),
                        where = "${file.path}:${index + 1}",
                    ))
                }
            }
        }
        assertEquals(
            "the DEBUG* flag set in the input pipeline drifted; every flag guards logs and must" +
                " stay a compile-time false — review the class KDoc finding before touching this",
            EXPECTED_DEBUG_FLAG_COUNT,
            declarations.size,
        )
        // The keystroke tracers described in the class KDoc are disarmed by these flags;
        // their absence would mean the guard was renamed or deleted.
        for (required in listOf("DEBUG_EVENT", "DEBUG_MOVE_EVENT", "DEBUG_LISTENER", "DEBUG_MODE",
                "DEBUG_ACTION", "DEBUG_TIMER_ACTION", "DEBUG_INTERNAL_ACTION", "DEBUG_CACHE")) {
            assertTrue("expected debug flag $required to exist", declarations.any { it.name == required })
        }
        val offenders = declarations.filter { !it.resolvesFalse(declarations) }
        assertTrue(
            "every DEBUG* flag in the input pipeline must resolve to compile-time false," +
                " offenders: " + offenders.joinToString { "${it.name} = ${it.initializer} @ ${it.where}" },
            offenders.isEmpty(),
        )
    }

    // --- scan machinery --------------------------------------------------------------------------

    private data class ScannedFile(val path: String, val packageDir: String, val logicalLines: List<String>)

    private data class CallSite(val path: String, val line: Int, val trimmedLine: String, val statement: String)

    private data class DebugFlag(val name: String, val initializer: String, val where: String) {
        fun resolvesFalse(all: List<DebugFlag>, seen: Set<String> = emptySet()): Boolean {
            if (initializer == "false") return true
            if (name in seen || !initializer.matches(Regex("[A-Za-z0-9_]+"))) return false
            val targets = all.filter { it.name == initializer }
            return targets.isNotEmpty() && targets.all { it.resolvesFalse(all, seen + name) }
        }
    }

    private fun scannedFiles(): List<ScannedFile> {
        val javaRoot = File(sourceRoot(), "java")
        val files = ArrayList<ScannedFile>()
        for (pkg in SCAN_PACKAGES) {
            val dir = File(javaRoot, pkg)
            assertTrue("input-pipeline package dir not found: ${dir.absolutePath}", dir.isDirectory)
            // The latin/ root is not recursed: its subpackages are scan roots of their own.
            val walk = if (pkg == LATIN_ROOT) dir.walkTopDown().maxDepth(1) else dir.walkTopDown()
            walk.filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .mapTo(files) {
                    ScannedFile(
                        path = it.relativeTo(javaRoot).invariantSeparatorsPath,
                        packageDir = pkg,
                        logicalLines = logicalLines(it.readText()),
                    )
                }
        }
        return files.sortedBy { it.path }
    }

    private fun scanCallSites(): List<CallSite> {
        val sites = ArrayList<CallSite>()
        for (file in scannedFiles()) {
            for ((index, line) in file.logicalLines.withIndex()) {
                if (LOG_CALL.containsMatchIn(line)) {
                    sites.add(CallSite(
                        path = file.path,
                        line = index + 1,
                        trimmedLine = line.trim(),
                        statement = extractStatement(file.logicalLines, index, "${file.path}:${index + 1}"),
                    ))
                }
            }
        }
        return sites
    }

    /**
     * The source with block comments blanked and `//` tails cut, one entry per source line.
     * Naive on purpose: string literals are not honored while tracking comments (none of the
     * scanned files embed a comment opener in a literal — rule 1 pins the consequence).
     */
    private fun logicalLines(text: String): List<String> {
        val result = ArrayList<String>()
        var inBlockComment = false
        for (raw in text.split("\n")) {
            val out = StringBuilder()
            var i = 0
            while (i < raw.length) {
                if (inBlockComment) {
                    val end = raw.indexOf("*/", i)
                    if (end < 0) {
                        i = raw.length
                    } else {
                        inBlockComment = false
                        i = end + 2
                    }
                } else {
                    val block = raw.indexOf("/*", i)
                    val line = raw.indexOf("//", i)
                    if (line >= 0 && (block < 0 || line < block)) {
                        out.append(raw, i, line)
                        break
                    }
                    if (block < 0) {
                        out.append(raw, i, raw.length)
                        break
                    }
                    out.append(raw, i, block)
                    inBlockComment = true
                    i = block + 2
                }
            }
            result.add(out.toString())
        }
        return result
    }

    /** String and char literals removed, so parens/semicolons inside them never confuse the scan. */
    private fun stripLiterals(line: String): String {
        val out = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val q = quote
            when {
                q != null -> {
                    if (c == '\\') {
                        i++ // skip the escaped char
                    } else if (c == q) {
                        quote = null
                    }
                }
                c == '"' || c == '\'' -> quote = c
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** The full Log statement starting at [startIndex]: lines until the `;` at paren depth zero
     * (Java) or until the call's parens close at end of line (Kotlin needs no `;`). */
    private fun extractStatement(lines: List<String>, startIndex: Int, where: String): String {
        val parts = ArrayList<String>()
        var depth = 0
        var sawParen = false
        for (j in startIndex until lines.size) {
            parts.add(lines[j])
            for (c in stripLiterals(lines[j])) {
                when (c) {
                    '(' -> {
                        depth++
                        sawParen = true
                    }
                    ')' -> depth--
                    ';' -> if (sawParen && depth == 0) return parts.joinToString("\n")
                }
            }
            if (sawParen && depth == 0) return parts.joinToString("\n")
        }
        error("unterminated Log statement at $where")
    }

    private fun sourceRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull(File::isDirectory)
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    private companion object {
        const val LATIN_ROOT = "rkr/simplekeyboard/inputmethod/latin"

        val SCAN_PACKAGES = listOf(
            "rkr/simplekeyboard/inputmethod/keyboard",
            LATIN_ROOT,
            "rkr/simplekeyboard/inputmethod/latin/dictionary",
            "rkr/simplekeyboard/inputmethod/latin/emoji",
            "rkr/simplekeyboard/inputmethod/latin/inputlogic",
            "rkr/simplekeyboard/inputmethod/latin/setup",
            "rkr/simplekeyboard/inputmethod/latin/suggestions",
            "rkr/simplekeyboard/inputmethod/latin/utils",
        )

        val LOG_CALL = Regex("""\bLog\s*\.\s*(?:d|e|i|v|w|wtf|println)\s*\(""")

        val DEBUG_FLAG_DECLARATION = Regex("""\bboolean\s+(DEBUG[A-Za-z0-9_]*)\s*=\s*([^;]+);""")

        const val EXPECTED_DEBUG_FLAG_COUNT = 14

        /**
         * Lowercase substrings that only ever travel with user text; a Log statement whose text
         * (literals included) contains any of them can carry composing/committed text. Checked by
         * [noLogStatementMentionsTextCarryingNames].
         */
        val TEXT_CARRIER_DENYLIST = listOf(
            "word", // mWord, mTypedWord, suggestedWords
            "typed", // typedWord, mTypedText
            "composing", // composing text state
            "commit", // commitText, committedText
            "suggestion", // suggestion string payloads
            "text", // outputText, surroundingText, extractedText, CharSequence text carriers
            "charsequence", // CharSequence-typed parameters
            "codepoint", // event.mCodePoint — the typed character
            "label", // key-label carriers
        )

        /**
         * The reviewed `android.util.Log` call-site set, `path::trimmed first line`, sorted by
         * (path, line). Justifications per file:
         *
         * KeyboardLayoutSet.java — keyboard-cache instrumentation behind `DEBUG_CACHE = false`;
         * arguments are the cache size and the KeyboardId (element id / geometry / locale).
         *
         * KeyboardSwitcher.java — the `Log.w` is reachable: a layout-load failure warning carrying
         * the KeyboardId and the exception cause. The `Log.d` sites sit behind the
         * SwitchActions interface constants `DEBUG_ACTION`/`DEBUG_TIMER_ACTION` (`false`) and log
         * shift/symbols state names only.
         *
         * KeyboardTheme.java — reachable warnings carrying the theme-id preference value (a
         * settings enum id) and the NumberFormatException; settings metadata.
         *
         * MainKeyboardView.java — reachable fixed-string warnings about a missing root/content view.
         *
         * MoreKeysKeyboard.java — reachable layout-geometry error (widths/columns ints).
         *
         * PointerTracker.java — all sites are behind `DEBUG_LISTENER`/`DEBUG_MODE`
         * (= `DEBUG_EVENT`)/`DEBUG_MOVE_EVENT`, all `false` and pinned by rule 3. The tracers pass
         * key position, key kind, and event flags only (never `Constants.printableCode(...)` or
         * `key.getOutputText()`), so an armed guard still cannot leak a keystroke.
         *
         * internal/AlphabetShiftState.java — behind `DEBUG = false`; shift-state enum names.
         *
         * internal/KeyStylesSet.java — behind `DEBUG = false`; style names from static layout XML.
         *
         * internal/KeyboardBuilder.java — the `Log.w` sites are reachable XML-parse-failure
         * warnings carrying only the caught exception. The `Log.d` sites are the
         * startTag/endTag/startEndTag trace helpers; every call to them sits behind
         * `DEBUG = false`, and their arguments are XML tag names / KeyboardIds / Key.toString()
         * (static layout labels from the APK's own XML — never user input).
         *
         * internal/KeyboardIconsSet.java — reachable warning carrying the missing drawable's
         * resource entry name.
         *
         * internal/KeyboardRow.java — reachable layout-geometry errors (floats).
         *
         * internal/KeyboardState.java — all sites are behind `DEBUG_EVENT`/
         * `DEBUG_INTERNAL_ACTION` (`false`). onPressKey/onReleaseKey/onEvent log only whether the
         * key is a functional key, never the typed character.
         *
         * internal/ModifierKeyState.java and internal/ShiftKeyState.java — behind `DEBUG = false`
         * (declared on ModifierKeyState, inherited by ShiftKeyState); `mName` is the
         * constructor-passed constant ("ShiftKey").
         *
         * internal/NonDistinctMultitouchHelper.java — reachable warning carrying pointer counts.
         *
         * internal/PointerTrackerQueue.java — the `Log.d` sites sit behind `DEBUG = false`;
         * the `Log.w` duplicate tripwires are reachable, and `pointer` renders as
         * `PointerTracker@<hash>` (PointerTracker has no toString override) — identity metadata.
         *
         * latin/InputAttributes.java — fixed-string field-type diagnostics; the format args are
         * the inputType/imeOptions bitmasks, never user text.
         *
         * latin/LatinIME.java — the null-EditorInfo error is a fixed string (reachable); the two
         * position traces sit behind TRACE = false (rule 4).
         *
         * latin/RichInputConnection.java — fixed-string consistency tripwires: the batch nest
         * level, the refused replace/delete guards, the reload staleness notices, the null-read
         * errors. Positions and flags only, never a payload.
         *
         * latin/SystemBroadcastReceiver.java — fixed-string locale-change notice (reachable).
         *
         * latin/setup/SetupActivity.kt — a fixed-string setup-wizard error with the caught
         * exception (reachable).
         *
         * latin/utils/ApplicationUtils.java — fixed-string errors with the caught exception
         * (reachable).
         *
         * latin/utils/SubtypePreferenceUtils.java — the subtype preference values (locale and
         * layout-set ids: settings metadata).
         */
        val EXPECTED_CALL_SITES = listOf(
            // KeyboardLayoutSet.java — cache instrumentation, DEBUG_CACHE = false; ids only.
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardLayoutSet.java::Log.d(TAG, \"keyboard cache size=\" + sKeyboardCache.size() + \": HIT  id=\" + id);",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardLayoutSet.java::Log.d(TAG, \"forcing caching of keyboard with id=\" + id);",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardLayoutSet.java::Log.d(TAG, \"keyboard cache size=\" + sKeyboardCache.size() + \": \"",
            // KeyboardSwitcher.java — layout-load failure warning (reachable, KeyboardId + cause).
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.w(TAG, \"loading keyboard failed: \" + e.mKeyboardId, e.getCause());",
            // KeyboardSwitcher.java — shift/symbols state names, DEBUG_ACTION/DEBUG_TIMER_ACTION = false.
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"setAlphabetKeyboard\");",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"setAlphabetManualShiftedKeyboard\");",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"setAlphabetAutomaticShiftedKeyboard\");",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"setAlphabetShiftLockedKeyboard\");",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"setSymbolsKeyboard\");",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"setSymbolsShiftedKeyboard\");",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"requestUpdatingShiftState: \"",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"startDoubleTapShiftKeyTimer\");",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"setAlphabetKeyboard\");",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardSwitcher.java::Log.d(TAG, \"isInDoubleTapShiftKeyTimeout\");",
            // KeyboardTheme.java — theme-id preference value (reachable, settings metadata).
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardTheme.java::Log.w(TAG, \"Unknown keyboard theme in preference: \" + themeIdString);",
            "rkr/simplekeyboard/inputmethod/keyboard/KeyboardTheme.java::Log.w(TAG, \"Illegal keyboard theme in preference: \" + themeIdString, e);",
            // MainKeyboardView.java — fixed-string warnings (reachable).
            "rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java::Log.w(TAG, \"Cannot find root view\");",
            "rkr/simplekeyboard/inputmethod/keyboard/MainKeyboardView.java::Log.w(TAG, \"Cannot find android.R.id.content view to add DrawingPreviewPlacerView\");",
            // MoreKeysKeyboard.java — layout-geometry error (reachable, ints).
            "rkr/simplekeyboard/inputmethod/keyboard/MoreKeysKeyboard.java::Log.e(TAG, \"Keyboard is too small to hold the requested more keys columns: \"",
            // PointerTracker.java — no typed text: format args carry key
            // position / key kind / flags only; dead behind DEBUG_LISTENER/DEBUG_MOVE_EVENT/
            // DEBUG_MODE = false.
            "rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java::Log.d(TAG, String.format(\"[%d] onPress    : %s%s%s\", mPointerId,",
            "rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java::Log.d(TAG, String.format(\"[%d] onCodeInput: %4d %4d %s%s%s\", mPointerId, x, y,",
            "rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java::Log.d(TAG, String.format(\"[%d] onRelease  : %s%s%s\", mPointerId,",
            "rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java::Log.d(TAG, String.format(\"[%d] onFinishSlidingInput\", mPointerId));",
            "rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java::Log.w(TAG, String.format(\"[%d] onDownEvent:\"",
            "rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java::Log.w(TAG, String.format(\"[%d] onMoveEvent:\"",
            "rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java::Log.d(TAG, String.format(\"[%d] isMajorEnoughMoveToBeOnNewKey:\"",
            "rkr/simplekeyboard/inputmethod/keyboard/PointerTracker.java::Log.d(TAG, String.format(\"[%d]%s%s %4d %4d %5d\", mPointerId,",
            // internal/AlphabetShiftState.java — shift-state enum names, DEBUG = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/AlphabetShiftState.java::Log.d(TAG, \"setShifted(\" + newShiftState + \"): \" + toString(oldState) + \" > \" + this);",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/AlphabetShiftState.java::Log.d(TAG, \"setShiftLocked(\" + newShiftLockState + \"): \" + toString(oldState)",
            // internal/KeyStylesSet.java — static XML style names, DEBUG = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyStylesSet.java::Log.d(TAG, String.format(\"<%s styleName=%s />\",",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyStylesSet.java::Log.d(TAG, KeyboardBuilder.TAG_KEY_STYLE + \" \" + styleName + \" is overridden at \"",
            // internal/KeyboardBuilder.java — XML-parse warnings (reachable, exception only).
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java::Log.w(BUILDER_TAG, \"keyboard XML parse error\", e);",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java::Log.w(BUILDER_TAG, \"keyboard XML parse error\", e);",
            // internal/KeyboardBuilder.java — XML trace helpers, every call behind DEBUG = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java::Log.d(BUILDER_TAG, String.format(spaces(++mIndent * 2) + format, args));",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java::Log.d(BUILDER_TAG, String.format(spaces(mIndent-- * 2) + format, args));",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardBuilder.java::Log.d(BUILDER_TAG, String.format(spaces(++mIndent * 2) + format, args));",
            // internal/KeyboardIconsSet.java — missing-drawable warning (reachable, resource name).
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardIconsSet.java::Log.w(TAG, \"Drawable resource for icon #\"",
            // internal/KeyboardRow.java — layout-geometry errors (reachable, floats).
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardRow.java::Log.e(TAG, \"The row is too tall to fit in the keyboard (\" + keyOverflow",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardRow.java::Log.e(TAG, \"The specified keyXPos (\" + keyXPos",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardRow.java::Log.e(TAG, \"The \" + (isSpacer ? \"spacer\" : \"key\")",
            // internal/KeyboardState.java — shift/switch state names, DEBUG_INTERNAL_ACTION = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"onLoadKeyboard: \" + stateToString(autoCapsFlags, recapitalizeMode));",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"setShifted: shiftMode=\" + shiftModeToString(shiftMode) + \" \" + this);",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"setShiftLocked: shiftLocked=\" + shiftLocked + \" \" + this);",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"toggleAlphabetAndSymbols: \"",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"resetKeyboardStateToAlphabet: \"",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"setAlphabetKeyboard: \" + stateToString(autoCapsFlags, recapitalizeMode));",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"setSymbolsKeyboard\");",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"setSymbolsShiftedKeyboard\");",
            // internal/KeyboardState.java — no typed text: functional-key
            // boolean instead of the typed character; dead behind DEBUG_EVENT = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"onPressKey: functional=\" + (code < 0)",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"onReleaseKey: functional=\" + (code < 0)",
            // internal/KeyboardState.java — shift/switch state names, DEBUG_EVENT = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"onUpdateShiftState: \" + stateToString(autoCapsFlags, recapitalizeMode));",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"onResetKeyboardStateToAlphabet: \"",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"onFinishSlidingInput: \" + stateToString(autoCapsFlags, recapitalizeMode));",
            // internal/KeyboardState.java — no typed text, same shape as onPressKey above.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/KeyboardState.java::Log.d(TAG, \"onEvent: functional=\" + event.isFunctionalKeyEvent()",
            // internal/ModifierKeyState.java / ShiftKeyState.java — mName is the constructor-passed
            // constant; DEBUG = false declared on ModifierKeyState.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/ModifierKeyState.java::Log.d(TAG, mName + \".onOtherKeyPressed: \" + toString(oldState) + \" > \" + this);",
            // internal/NonDistinctMultitouchHelper.java — pointer counts (reachable).
            "rkr/simplekeyboard/inputmethod/keyboard/internal/NonDistinctMultitouchHelper.java::Log.w(TAG, \"Unknown touch panel behavior: pointer count is \"",
            // internal/PointerTrackerQueue.java — queue tracing, DEBUG = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.d(TAG, \"add: \" + pointer + \" \" + this);",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.d(TAG, \"remove: \" + pointer + \" \" + this);",
            // internal/PointerTrackerQueue.java — duplicate tripwires (reachable);
            // PointerTracker has no toString override, so this is PointerTracker@<hash> identity.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.w(TAG, \"Found duplicated element in remove: \" + pointer);",
            // internal/PointerTrackerQueue.java — queue tracing, DEBUG = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.d(TAG, \"releaseAllPointerOlderThan: \" + pointer + \" \" + this);",
            // internal/PointerTrackerQueue.java — duplicate tripwire (reachable), identity only.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.w(TAG, \"Found duplicated element in releaseAllPointersOlderThan: \"",
            // internal/PointerTrackerQueue.java — queue tracing, DEBUG = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.d(TAG, \"releaseAllPointers: \" + this);",
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.d(TAG, \"releaseAllPointerExcept: \" + pointer + \" \" + this);",
            // internal/PointerTrackerQueue.java — duplicate tripwire (reachable), identity only.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.w(TAG, \"Found duplicated element in releaseAllPointersExcept: \"",
            // internal/PointerTrackerQueue.java — queue tracing, DEBUG = false.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/PointerTrackerQueue.java::Log.d(TAG, \"cancelAllPointerTracker: \" + this);",
            // internal/ShiftKeyState.java — inherited DEBUG = false; mName constant.
            "rkr/simplekeyboard/inputmethod/keyboard/internal/ShiftKeyState.java::Log.d(TAG, mName + \".onOtherKeyPressed: \" + toString(oldState) + \" > \" + this);",
            // latin/InputAttributes.java — fixed strings; the format args are the inputType /
            // imeOptions bitmasks.
            "rkr/simplekeyboard/inputmethod/latin/InputAttributes.java::Log.w(TAG, \"No editor info for this field. Bug?\");",
            "rkr/simplekeyboard/inputmethod/latin/InputAttributes.java::Log.i(TAG, \"InputType.TYPE_NULL is specified\");",
            "rkr/simplekeyboard/inputmethod/latin/InputAttributes.java::Log.w(TAG, String.format(\"Unexpected input class: inputType=0x%08x\"",
            // latin/LatinIME.java — fixed string (reachable).
            "rkr/simplekeyboard/inputmethod/latin/LatinIME.java::Log.e(TAG, \"Null EditorInfo in onStartInputView()\");",
            // latin/LatinIME.java — cursor-position traces, behind TRACE = false (rule 4).
            "rkr/simplekeyboard/inputmethod/latin/LatinIME.java::if (TRACE) Log.i(TAG, \"Starting input. Cursor position = \"",
            "rkr/simplekeyboard/inputmethod/latin/LatinIME.java::if (TRACE) Log.i(TAG, \"Update Selection. Cursor position = \" + newSelStart + \",\" + newSelEnd);",
            // latin/RichInputConnection.java — fixed-string tripwires: nest level and batch pairing.
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"Nest level too deep : \" + mNestLevel);",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::if (mNestLevel <= 0) Log.e(TAG, \"Batch edit not in progress!\");",
            // latin/RichInputConnection.java — fixed-string null-read errors (reachable).
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"Unable to read around the cursor.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"The read around the cursor carries an out-of-range selection.\");",
            // latin/RichInputConnection.java — reload staleness notices (reachable), no payload.
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.w(TAG, \"Selection range modified before the reload reached the editor.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.w(TAG, \"Selection range modified before thread completion.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.w(TAG, \"Selection start modified before thread completion.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"Unable to read before the cursor.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.w(TAG, \"Selection range modified before thread completion.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.w(TAG, \"Selection end modified before thread completion.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"Unable to read after the cursor.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.w(TAG, \"Selection range modified before thread completion.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"Unable to read the selection.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.w(TAG, \"Selection range modified before thread completion.\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.i(TAG, \"Clearing editor caches.\");",
            // latin/RichInputConnection.java — refused-edit tripwires, fixed strings (reachable).
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"replace refused: a selection is active\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"replace refused: the range does not start at the cursor\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"replace refused: the range runs past the cache\");",
            "rkr/simplekeyboard/inputmethod/latin/RichInputConnection.java::Log.e(TAG, \"selection delete refused: nothing is selected\");",
            // latin/SystemBroadcastReceiver.java — fixed string (reachable).
            "rkr/simplekeyboard/inputmethod/latin/SystemBroadcastReceiver.java::Log.i(TAG, \"System locale changed\");",
            // latin/setup/SetupActivity.kt — fixed string + the caught exception (reachable).
            "rkr/simplekeyboard/inputmethod/latin/setup/SetupActivity.kt::Log.e(TAG, \"Exception in check if input method is enabled\", e)",
            // latin/utils/ApplicationUtils.java — fixed strings + the caught exception (reachable).
            "rkr/simplekeyboard/inputmethod/latin/utils/ApplicationUtils.java::Log.e(TAG, \"Failed to get settings activity title res id.\", e);",
            "rkr/simplekeyboard/inputmethod/latin/utils/ApplicationUtils.java::Log.e(TAG, \"Could not find version info.\", e);",
            "rkr/simplekeyboard/inputmethod/latin/utils/ApplicationUtils.java::Log.e(TAG, \"Could not find version info.\", e);",
            // latin/utils/SubtypePreferenceUtils.java — locale / layout-set ids (settings metadata).
            "rkr/simplekeyboard/inputmethod/latin/utils/SubtypePreferenceUtils.java::Log.i(TAG, \"Loading subtypes: \" + prefSubtypes);",
            "rkr/simplekeyboard/inputmethod/latin/utils/SubtypePreferenceUtils.java::Log.w(TAG, \"Unknown subtype specified: \" + prefSubtype + \" in \"",
        )
    }
}

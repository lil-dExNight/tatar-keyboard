// Copyright (C) 2026 Tatar Keyboard contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//          http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.tatarkeyboard.baselineprofile;

import android.content.Intent;
import android.os.SystemClock;

import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.regex.Pattern;

import kotlin.Unit;

/**
 * Phase 4b: Baseline Profile generator for the IME (dev-only, never packaged).
 *
 * CUJ (the real user hot path, not a launcher activity): the IME process is started
 * BY THE SYSTEM when an editable field gains focus — so each iteration cold-kills the
 * app process, focuses the try-it field of the app's own SetupActivity, waits for the
 * keyboard, types a Tatar word with real key taps (PointerTracker -> InputLogic ->
 * dictionary suggestion engine -> suggestion strip), commits a suggestion by tapping
 * the strip, commits the word with SPACE (which triggers the bigram next-word
 * prediction), commits the emoji-suggest cell the strip grew for "сәлам" (👋, tail
 * slot — EmojiSuggestIndex load + onEmojiSuggestReady + emoji commit path), commits a
 * predicted word, glides "сәлам" as ONE continuous polyline over the five letter keys
 * (PointerTracker -> GlideGestureDecider -> GlidePath -> GlideDecoder on the engine
 * worker -> the strip's glide band; 2026-09-24 audit, finding 3), commits the glide
 * candidate, and opens the emoji panel (long-press comma) and commits an emoji.
 * This covers process start, onCreateInputView, layout XML parsing/inflation, first
 * frame render, the suggestion engine hot path (tdict unpack + mmap + binary search in
 * TdictPrefixIndex/MappedDictionaryEngine, bigram lookup in TatBigrPrefixIndex), the
 * emoji-suggest path (mission M4), the emoji panel, and the glide decode path (P7).
 *
 * Emoji suggestions are ON by default (mission M4b, PREF_EMOJI_SUGGESTIONS), so no
 * extra toggle is needed: the first eligible lookup starts the one-per-process table
 * load, and the band painted for "сәлам "+SPACE carries the 👋 tail cell once
 * onEmojiSuggestReady re-derives it.
 *
 * Suggestions are opt-in (PREF_TATAR_SUGGESTIONS, default OFF) and a CUJ that never
 * turns them on profiles nothing of the engine (P2 of docs/AUDIT-2026-08-31.md). The
 * release APK is not debuggable, so run-as seeding of the device-protected prefs (the
 * emulator-smoke.sh trick) is unavailable here; instead the first iteration toggles
 * the switch through the real settings UI — exactly how a user enables it. The pref
 * survives the force-stop that killProcess() performs, so later iterations start with
 * suggestions already on.
 *
 * Run: ./gradlew :app:generateReleaseBaselineProfile  (connected API 34 emulator).
 */
@RunWith(AndroidJUnit4.class)
public class ImeBaselineProfileGenerator {

    private static final String PACKAGE_NAME = "org.tatarkeyboard.ime";
    // NOTE: the manifest declares the service as ".latin.LatinIME", but relative
    // component names resolve against the *package*, not the source namespace —
    // "org.tatarkeyboard.ime/.latin.LatinIME" is NOT valid for `ime enable/set`.
    // The real id is what `dumpsys input_method` reports as mCurMethodId.
    private static final String IME_ID =
            PACKAGE_NAME + "/rkr.simplekeyboard.inputmethod.latin.LatinIME";
    private static final String SETUP_ACTIVITY =
            "rkr.simplekeyboard.inputmethod.latin.setup.SetupActivity";
    private static final String SETTINGS_ACTIVITY =
            "rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity";
    // Row labels in all three shipped locales (the AVD locale is not fixed):
    // settings_screen_preferences and tatar_suggestions.
    private static final Pattern PREFERENCES_ROW_LABEL =
            Pattern.compile("^(Preferences|Настройки|Көйләүләр)$");
    private static final Pattern SUGGESTIONS_ROW_LABEL =
            Pattern.compile("^(Word suggestions|Подсказки слов|Сүз тәкъдимнәре)$");

    @Rule
    public BaselineProfileRule baselineProfileRule = new BaselineProfileRule();

    // Iterations share this test instance, and the pref survives the force-stop
    // in killProcess() — enabling once per generation run is enough.
    private boolean mSuggestionsEnsured;

    @Test
    public void generate() {
        baselineProfileRule.collect(
                PACKAGE_NAME,
                /* maxIterations = */ 15,
                /* stableIterations = */ 3,
                /* outputFilePrefix = */ null,
                /* includeInStartupProfile = */ true,
                scope -> {
                    runCuj(scope);
                    return Unit.INSTANCE;
                });
    }

    private void runCuj(MacrobenchmarkScope scope) {
        UiDevice device = scope.getDevice();
        // Cold start: the IME process must be (re)started by the system below.
        // NOTE: Macrobenchmark's kill flushes ART profiles and FORCE-STOPS the
        // package, and force-stopping the *selected* IME resets
        // Settings.Secure.DEFAULT_INPUT_METHOD — so the selection must be
        // (re)applied AFTER the kill, never before it.
        scope.killProcess();
        enableAndSelectIme(device);
        ensureSuggestionsEnabled(scope, device);

        startSetupActivity(scope, device);

        UiObject2 field = device.wait(
                Until.findObject(By.clazz("android.widget.EditText")), 10_000);
        if (field == null) {
            // One retry: re-assert the selection and relaunch (the done-block
            // EditText only exists while this IME is the current one).
            enableAndSelectIme(device);
            startSetupActivity(scope, device);
            field = device.wait(
                    Until.findObject(By.clazz("android.widget.EditText")), 10_000);
        }
        if (field == null) {
            throw new IllegalStateException("Try-it EditText not found in SetupActivity");
        }
        // Focusing the field binds and cold-starts the IME.
        field.click();
        // Give the keyboard time to appear and render its first frame.
        SystemClock.sleep(2_500);

        // Type the Tatar word "сәлам" with real key taps (coordinates as screen
        // fractions, calibrated on the 1080x2280 API 34 AVD; see KeyGeom).
        tapKeyFraction(device, KeyGeom.KEY_S);
        tapKeyFraction(device, KeyGeom.KEY_AE);
        tapKeyFraction(device, KeyGeom.KEY_L);
        tapKeyFraction(device, KeyGeom.KEY_A);
        tapKeyFraction(device, KeyGeom.KEY_M);
        // Wait for the strip to render the prefix suggestions: the FIRST lookup is
        // the expensive one (zlib unpack + mmap + binary search), and the whole
        // point of this CUJ is to get it profiled.
        SystemClock.sleep(1_500);

        // Commit a prefix suggestion from the strip ("сәламәтлек" on the
        // calibration AVD) — SuggestionStripView touch + commit path.
        tapKeyFraction(device, KeyGeom.SUGGESTION_LEFT);
        SystemClock.sleep(800);

        // Type "сәлам" again and commit with SPACE: a word separator after a known
        // head triggers the bigram next-word prediction (TatBigrPrefixIndex) and,
        // because "сәлам" maps to 👋 in emoji_suggest_v1.txt, the emoji-suggest path
        // (EmojiSuggestIndex load + onEmojiSuggestReady re-deriving the band).
        tapKeyFraction(device, KeyGeom.KEY_S);
        tapKeyFraction(device, KeyGeom.KEY_AE);
        tapKeyFraction(device, KeyGeom.KEY_L);
        tapKeyFraction(device, KeyGeom.KEY_A);
        tapKeyFraction(device, KeyGeom.KEY_M);
        tapKeyFraction(device, KeyGeom.KEY_SPACE);
        // Longer settle than the prefix wait: the emoji table load is asynchronous
        // (one per process) and onEmojiSuggestReady must have re-painted the band
        // before the tail slot is tapped.
        SystemClock.sleep(2_500);

        // Commit the emoji-suggest tail cell (👋 in the right slot after "сәлам "+
        // on the calibration AVD: band is [биреп · белән · 👋]) — the strip tap path
        // treats the bound emoji cell exactly like a predicted word, and binding it
        // also exercises SharedEmojiSearchIndex through the spoken label lookup.
        tapKeyFraction(device, KeyGeom.SUGGESTION_RIGHT);
        SystemClock.sleep(800);

        // Once more "сәлам" + SPACE, then commit a next-word prediction from the
        // strip ("белән" in the middle slot after "сәлам" on the calibration AVD).
        tapKeyFraction(device, KeyGeom.KEY_S);
        tapKeyFraction(device, KeyGeom.KEY_AE);
        tapKeyFraction(device, KeyGeom.KEY_L);
        tapKeyFraction(device, KeyGeom.KEY_A);
        tapKeyFraction(device, KeyGeom.KEY_M);
        tapKeyFraction(device, KeyGeom.KEY_SPACE);
        SystemClock.sleep(1_500);
        tapKeyFraction(device, KeyGeom.PREDICTION_MIDDLE);
        SystemClock.sleep(800);

        // Glide (P7): one continuous polyline over the letter keys of "сәлам" — the
        // finger never lifts, which is what arms GlideGestureDecider; the decode runs on
        // the engine worker and the strip grows the glide band. Glide typing is on by
        // default (PREF_GLIDE_TYPING), so no toggle is needed beyond the suggestions one
        // above (the band the glide candidates ride is subordinate to it).
        device.swipe(new android.graphics.Point[]{
                point(device, KeyGeom.KEY_S),
                point(device, KeyGeom.KEY_AE),
                point(device, KeyGeom.KEY_L),
                point(device, KeyGeom.KEY_A),
                point(device, KeyGeom.KEY_M),
        }, /* segmentSteps = */ 25);
        // The decode is a worker round trip, like the first prefix lookup: give the band
        // time to paint the glide candidate before committing it.
        SystemClock.sleep(2_000);
        tapKeyFraction(device, KeyGeom.SUGGESTION_LEFT);
        SystemClock.sleep(800);

        // Emoji panel: long-press the comma key, commit the first emoji of the
        // grid, then leave the panel.
        device.swipe(
                KeyGeom.x(device, KeyGeom.KEY_COMMA),
                KeyGeom.y(device, KeyGeom.KEY_COMMA),
                KeyGeom.x(device, KeyGeom.KEY_COMMA),
                KeyGeom.y(device, KeyGeom.KEY_COMMA),
                /* steps = */ 60);
        SystemClock.sleep(1_500);
        tapKeyFraction(device, KeyGeom.EMOJI_FIRST_CELL);
        SystemClock.sleep(800);
        device.pressBack();
        SystemClock.sleep(500);

        device.pressHome();
    }

    /**
     * Turns on PREF_TATAR_SUGGESTIONS through the settings UI (SettingsActivity →
     * Preferences → "Word suggestions"), once per generation run. The whole row is
     * the tap target and reports itself to accessibility as the Switch it toggles
     * (SettingsHostActivity.switchRowRaw), so the checked state is read from the
     * checkable ancestor of the label and the row is only clicked when it is OFF —
     * never blind-toggled, because the pref survives force-stop and a reused
     * emulator may already have it on.
     */
    private void ensureSuggestionsEnabled(MacrobenchmarkScope scope, UiDevice device) {
        if (mSuggestionsEnsured) {
            return;
        }
        Intent intent = new Intent();
        intent.setClassName(PACKAGE_NAME, SETTINGS_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        scope.startActivityAndWait(intent);

        UiObject2 prefsRow = device.wait(
                Until.findObject(By.text(PREFERENCES_ROW_LABEL)), 10_000);
        if (prefsRow == null) {
            throw new IllegalStateException("Settings root: Preferences row not found");
        }
        prefsRow.click();

        UiObject2 label = device.wait(
                Until.findObject(By.text(SUGGESTIONS_ROW_LABEL)), 5_000);
        // The row is visible without scrolling on 1080x2280; the scroll loop is
        // only a fallback for smaller screens.
        for (int i = 0; label == null && i < 5; i++) {
            device.swipe(device.getDisplayWidth() / 2,
                    Math.round(device.getDisplayHeight() * 0.7f),
                    device.getDisplayWidth() / 2,
                    Math.round(device.getDisplayHeight() * 0.4f),
                    /* steps = */ 20);
            label = device.wait(
                    Until.findObject(By.text(SUGGESTIONS_ROW_LABEL)), 2_000);
        }
        if (label == null) {
            throw new IllegalStateException("Suggestions row not found in Preferences");
        }
        UiObject2 row = label;
        for (int i = 0; row != null && !row.isCheckable() && i < 4; i++) {
            row = row.getParent();
        }
        if (row == null || !row.isCheckable()) {
            throw new IllegalStateException(
                    "Checkable row above the suggestions label not found");
        }
        if (!row.isChecked()) {
            row.click();
            SystemClock.sleep(500);
        }
        mSuggestionsEnsured = true;
        device.pressHome();
        SystemClock.sleep(500);
    }

    private void startSetupActivity(MacrobenchmarkScope scope, UiDevice device) {
        Intent intent = new Intent();
        intent.setClassName(PACKAGE_NAME, SETUP_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        scope.startActivityAndWait(intent);
    }

    private void tapKeyFraction(UiDevice device, float[] fraction) {
        device.click(KeyGeom.x(device, fraction), KeyGeom.y(device, fraction));
        SystemClock.sleep(120);
    }

    private android.graphics.Point point(UiDevice device, float[] fraction) {
        return new android.graphics.Point(KeyGeom.x(device, fraction), KeyGeom.y(device, fraction));
    }

    private void enableAndSelectIme(UiDevice device) {
        try {
            device.executeShellCommand("ime enable " + IME_ID);
            device.executeShellCommand("ime set " + IME_ID);
            // Verify the selection stuck — a stale/unknown id fails silently in
            // executeShellCommand, and the done-block EditText only exists when
            // this IME is the current one.
            for (int attempt = 0; attempt < 10; attempt++) {
                String current = device.executeShellCommand(
                        "settings get secure default_input_method").trim();
                if (current.equals(IME_ID)) {
                    return;
                }
                SystemClock.sleep(300);
                device.executeShellCommand("ime set " + IME_ID);
            }
            throw new IllegalStateException("IME did not stay selected: " + IME_ID);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to enable/select the IME", e);
        }
    }

    /** Screen-fraction key geometry, calibrated on the tt_suggest_a14 AVD (1080x2280,
     *  Tatar layout): verified that the five taps commit exactly "сәлам" into the
     *  try-it field and that long-pressing comma opens the emoji panel.
     *  Strip geometry verified 2026-08-31 against screenshots of the same AVD: the
     *  suggestion strip sits at y≈0.60 with three slots (left/middle/right ≈
     *  0.167/0.5/0.833); tapping SUGGESTION_LEFT after "сәлам" commits "сәламәтлек",
     *  and after "сәлам"+SPACE the strip shows bigram predictions
     *  "биреп | белән | биру" with the emoji-suggest tail cell, so the band is
     *  [биреп · белән · 👋] (verified 2026-09-02 against the emoji_suggest_v1.txt
     *  mapping сәлам→👋). EMOJI_FIRST_CELL is the first cell of the emoji grid
     *  (same calibration as scripts/emulator-smoke.sh). */
    private static final class KeyGeom {
        // {xFraction, yFraction} of key centers on the Tatar layout.
        static final float[] KEY_S = {0.3324f, 0.8474f};
        static final float[] KEY_AE = {0.0833f, 0.6583f};
        static final float[] KEY_L = {0.6815f, 0.7851f};
        static final float[] KEY_A = {0.3176f, 0.7851f};
        static final float[] KEY_M = {0.4231f, 0.8474f};
        static final float[] KEY_COMMA = {0.2009f, 0.9075f};
        static final float[] KEY_SPACE = {0.55f, 0.9075f};
        static final float[] SUGGESTION_LEFT = {0.167f, 0.60f};
        static final float[] PREDICTION_MIDDLE = {0.5f, 0.60f};
        static final float[] SUGGESTION_RIGHT = {0.833f, 0.60f};
        static final float[] EMOJI_FIRST_CELL = {0.059f, 0.777f};

        static int x(UiDevice device, float[] fraction) {
            return Math.round(device.getDisplayWidth() * fraction[0]);
        }

        static int y(UiDevice device, float[] fraction) {
            return Math.round(device.getDisplayHeight() * fraction[1]);
        }
    }
}

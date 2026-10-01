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
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Locale;

import kotlin.Unit;

/**
 * Baseline Profile generator for the IME (dev-only, never packaged). The system starts the
 * IME process when an editable field gains focus, so each iteration cold-kills the app,
 * focuses the try-it field of SetupActivity and then runs these journeys with real taps:
 * <ul>
 *   <li>type "сәлам" and commit a word completion from the suggestion strip;</li>
 *   <li>type "сәлам" + SPACE and commit the emoji suggestion cell (👋);</li>
 *   <li>type "сәлам" + SPACE and commit a next-word prediction;</li>
 *   <li>glide "сәлам" in one stroke and commit the glide candidate;</li>
 *   <li>open the emoji panel (long-press comma) and commit an emoji.</li>
 * </ul>
 * Word suggestions are off by default, so the first iteration turns them on through the
 * settings UI (the release APK is not debuggable, so prefs cannot be seeded via run-as).
 * Emoji suggestions and glide typing are on by default.
 *
 * Run: ./gradlew :app:generateBaselineProfile on a connected API 34 emulator (device
 * pinning: see baselineprofile/build.gradle).
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
    // Generation is calibrated to one emulator (see KeyGeom) and useConnectedDevices may pick
    // any attached device, so non-emulators are refused unless this instrumentation argument
    // (`-e ttAllowPhysicalDevice true`) allows them.
    private static final String ARG_ALLOW_PHYSICAL_DEVICE = "ttAllowPhysicalDevice";

    @Rule
    public BaselineProfileRule baselineProfileRule = new BaselineProfileRule();

    // Iterations share this test instance, and the pref survives the force-stop
    // in killProcess() — enabling once per generation run is enough.
    private boolean mSuggestionsEnsured;

    @Test
    public void generate() {
        requireEmulatorDevice();
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

    /**
     * Fails fast on a non-emulator: the journeys are calibrated to the tt_suggest_a14 AVD
     * (KeyGeom), and a physical device would change a real user's IME settings.
     */
    private void requireEmulatorDevice() {
        Bundle args = InstrumentationRegistry.getArguments();
        if ("true".equals(args.getString(ARG_ALLOW_PHYSICAL_DEVICE))) {
            return;
        }
        if (isEmulator()) {
            return;
        }
        throw new IllegalStateException(
                "Refusing to generate a Baseline Profile on a physical device. Attach only"
                        + " the emulator or pin ANDROID_SERIAL=<emulator-serial> so generation"
                        + " targets it; to override, pass -e " + ARG_ALLOW_PHYSICAL_DEVICE
                        + " true to the instrumentation.");
    }

    private boolean isEmulator() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try {
            String qemu = device.executeShellCommand("getprop ro.kernel.qemu");
            if (qemu != null && qemu.trim().equals("1")) {
                return true;
            }
        } catch (IOException e) {
            // Fall through to the fingerprint heuristic.
        }
        String fingerprint = Build.FINGERPRINT == null
                ? "" : Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        return fingerprint.contains("generic")
                || fingerprint.contains("sdk")
                || fingerprint.contains("emulator");
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
                Until.findObject(By.res(PACKAGE_NAME, "setup_test_field")), 10_000);
        if (field == null) {
            // One retry: re-assert the selection and relaunch (the done-block
            // EditText only exists while this IME is the current one).
            enableAndSelectIme(device);
            startSetupActivity(scope, device);
            field = device.wait(
                    Until.findObject(By.res(PACKAGE_NAME, "setup_test_field")), 10_000);
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
        // Wait for the strip to render the word completions: the first lookup is the
        // expensive one (zlib unpack + mmap + binary search) and must be profiled.
        SystemClock.sleep(1_500);

        // Commit a word completion from the strip ("сәламәтлек" on the calibration AVD):
        // SuggestionStripView touch + commit path.
        tapKeyFraction(device, KeyGeom.SUGGESTION_LEFT);
        SystemClock.sleep(800);

        // Type "сәлам" again and commit with SPACE: a word separator after a known head
        // triggers the next-word prediction (TatBigrPrefixIndex) and, because "сәлам" maps
        // to 👋 in emoji_suggest_v1.txt, the emoji suggestion path (EmojiSuggestIndex load
        // + onEmojiSuggestReady updating the strip).
        tapKeyFraction(device, KeyGeom.KEY_S);
        tapKeyFraction(device, KeyGeom.KEY_AE);
        tapKeyFraction(device, KeyGeom.KEY_L);
        tapKeyFraction(device, KeyGeom.KEY_A);
        tapKeyFraction(device, KeyGeom.KEY_M);
        tapKeyFraction(device, KeyGeom.KEY_SPACE);
        // Longer wait than for completions: the emoji table loads asynchronously (once
        // per process) and the strip must be repainted before its right cell is tapped.
        SystemClock.sleep(2_500);

        // Commit the emoji suggestion cell (👋 in the right cell after "сәлам "; the strip
        // is [биреп · белән · 👋] on the calibration AVD). The tap path treats it like a
        // predicted word; binding it also loads SharedEmojiSearchIndex for its spoken label.
        tapKeyFraction(device, KeyGeom.SUGGESTION_RIGHT);
        SystemClock.sleep(800);

        // Once more "сәлам" + SPACE, then commit a next-word prediction from the
        // strip ("белән" in the middle cell after "сәлам" on the calibration AVD).
        tapKeyFraction(device, KeyGeom.KEY_S);
        tapKeyFraction(device, KeyGeom.KEY_AE);
        tapKeyFraction(device, KeyGeom.KEY_L);
        tapKeyFraction(device, KeyGeom.KEY_A);
        tapKeyFraction(device, KeyGeom.KEY_M);
        tapKeyFraction(device, KeyGeom.KEY_SPACE);
        SystemClock.sleep(1_500);
        tapKeyFraction(device, KeyGeom.PREDICTION_MIDDLE);
        SystemClock.sleep(800);

        // Glide: one continuous polyline over the letter keys of "сәлам". The finger never
        // lifts, which arms GlideGestureDecider; the decode runs on the engine worker and
        // the candidates appear in the strip. Glide typing is on by default; it only needs
        // word suggestions, enabled above.
        device.swipe(new android.graphics.Point[]{
                point(device, KeyGeom.KEY_S),
                point(device, KeyGeom.KEY_AE),
                point(device, KeyGeom.KEY_L),
                point(device, KeyGeom.KEY_A),
                point(device, KeyGeom.KEY_M),
        }, /* segmentSteps = */ 25);
        // The decode is a worker round trip, like the first completion lookup: give the
        // strip time to paint the glide candidate before committing it.
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
     * Turns on PREF_TATAR_SUGGESTIONS once per run through SettingsActivity → Preferences.
     * Rows are found by resource id (the UI is localized), and the row is clicked only when
     * its Switch is off: the pref survives force-stop and a reused emulator may have it on.
     */
    private void ensureSuggestionsEnabled(MacrobenchmarkScope scope, UiDevice device) {
        if (mSuggestionsEnsured) {
            return;
        }
        Intent intent = new Intent();
        intent.setClassName(PACKAGE_NAME, SETTINGS_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        scope.startActivityAndWait(intent);

        // SettingsActivity forwards to SettingsHostActivity and finishes, so wait on
        // the root view id, not on the activity.
        UiObject2 settingsRoot = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "settings_root")), 10_000);
        if (settingsRoot == null) {
            throw new IllegalStateException("Settings root screen not found");
        }
        UiObject2 prefsRow = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "row_link_preferences")), 10_000);
        if (prefsRow == null) {
            throw new IllegalStateException("Settings root: Preferences row not found");
        }
        prefsRow.click();

        UiObject2 row = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "row_switch_tatar_suggestions")), 5_000);
        // The row is visible without scrolling on 1080x2280; the scroll loop is
        // only a fallback for smaller screens.
        for (int i = 0; row == null && i < 5; i++) {
            device.swipe(device.getDisplayWidth() / 2,
                    Math.round(device.getDisplayHeight() * 0.7f),
                    device.getDisplayWidth() / 2,
                    Math.round(device.getDisplayHeight() * 0.4f),
                    /* steps = */ 20);
            row = device.wait(
                    Until.findObject(By.res(PACKAGE_NAME, "row_switch_tatar_suggestions")),
                    2_000);
        }
        if (row == null) {
            throw new IllegalStateException("Suggestions row not found in Preferences");
        }
        UiObject2 switchView = row.findObject(By.res(PACKAGE_NAME, "row_switch"));
        if (switchView == null) {
            throw new IllegalStateException("Switch not found inside the suggestions row");
        }
        if (!switchView.isChecked()) {
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

    /** Screen-fraction key geometry, calibrated on the tt_suggest_a14 AVD (1080x2280, Tatar
     *  layout). The five letter taps type "сәлам"; long-pressing comma opens the emoji panel.
     *  The suggestion strip sits at y≈0.60 with three cells (x ≈ 0.167/0.5/0.833): after
     *  "сәлам" the left cell is "сәламәтлек", after "сәлам "+SPACE the strip is
     *  [биреп · белән · 👋]. EMOJI_FIRST_CELL is grid row 0 with the suggestion strip
     *  visible (same calibration as GRID_CELL0_Y in scripts/emulator-smoke.sh). */
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
        static final float[] EMOJI_FIRST_CELL = {0.059f, 0.6877f};

        static int x(UiDevice device, float[] fraction) {
            return Math.round(device.getDisplayWidth() * fraction[0]);
        }

        static int y(UiDevice device, float[] fraction) {
            return Math.round(device.getDisplayHeight() * fraction[1]);
        }
    }
}

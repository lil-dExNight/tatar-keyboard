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

package rkr.simplekeyboard.inputmethod.latin.glide;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import junit.framework.AssertionFailedError;

/**
 * Measures the selected IME from a separate test process. Key centers come from visible virtual
 * accessibility nodes in the live input-method window; injected motion uses screen coordinates.
 * The endpoint is the TextWatcher callback that commits сәләм in this package's focused field.
 *
 * The target package comes from {@code ttPackage}. Its IME is enabled and selected with the
 * UiAutomation shell, and changed secure settings are restored. The field's TextWatcher owns the
 * commit timestamp, so no keyboard implementation class or preference is loaded in this process.
 */
public final class FirstGlidePerfDeviceTest extends InstrumentationTestCase {
    private String appPackage;
    private FirstGlidePerfActivity startedActivity;
    private EditText field;
    private boolean accessibilityFlagsSaved;
    private int originalAccessibilityFlags;

    public void testFirstVisibleGlideAfterIdleAndWarmDecode() throws Exception {
        final Instrumentation instrumentation = getInstrumentation();
        if (!(instrumentation instanceof FirstGlidePerfSelfRunner)) {
            Log.i(TAG, "skipped: runs only under FirstGlidePerfSelfRunner");
            return;
        }
        assertEquals("the runner must target its own test package",
                instrumentation.getContext().getPackageName(),
                instrumentation.getTargetContext().getPackageName());

        appPackage = appPackageArgument();
        final String targetIme = inputMethodId(appPackage);
        final String previousDefault = readSecureSetting(DEFAULT_INPUT_METHOD);
        final String previousEnabled = readSecureSetting(ENABLED_INPUT_METHODS);
        boolean restoreDefault = false;
        boolean restoreEnabled = false;
        try {
            if (!isEnabled(previousEnabled, targetIme)) {
                restoreEnabled = true;
                shell("ime enable " + targetIme);
            }
            if (!targetIme.equals(previousDefault)) {
                restoreDefault = true;
                shell("ime set " + targetIme);
            }
            awaitSelectedIme(targetIme);
            configureAccessibilityWindows();
            prepareField();

            // One complete decode prepares the live engine before either measured arm.
            measureFirstVisible(awaitKeyPath());
            final List<Double> cold = new ArrayList<>(LATENCY_RUNS);
            for (int i = 0; i < LATENCY_RUNS; i++) {
                clearField();
                hideKeyboard();
                SystemClock.sleep(IDLE_RELEASE_MS);
                showKeyboard();
                assertSelectedIme(targetIme);
                cold.add(measureFirstVisible(awaitKeyPath()));
            }

            final List<Double> warm = new ArrayList<>(LATENCY_RUNS);
            for (int i = 0; i < LATENCY_RUNS; i++) {
                clearField();
                assertSelectedIme(targetIme);
                warm.add(measureFirstVisible(awaitKeyPath()));
            }
            emitResult(statsLine("cold-first-glide", cold, COLD_FIRST_GLIDE_BUDGET_MS,
                    "protocol=lift-to-textwatcher-commit after_idle_ms=" + IDLE_RELEASE_MS));
            emitResult(statsLine("warm-decode", warm, WARM_DECODE_BUDGET_MS,
                    "protocol=lift-to-textwatcher-commit"));
        } finally {
            try {
                closeActivity();
            } finally {
                try {
                    restoreAccessibilityFlags();
                } finally {
                    try {
                        if (restoreDefault) {
                            restoreSecureSetting(DEFAULT_INPUT_METHOD, previousDefault);
                        }
                    } finally {
                        if (restoreEnabled) {
                            restoreSecureSetting(ENABLED_INPUT_METHODS, previousEnabled);
                        }
                    }
                }
            }
        }
    }

    private String appPackageArgument() {
        final Instrumentation instrumentation = getInstrumentation();
        if (instrumentation instanceof InstrumentationTestRunner) {
            final Bundle arguments = ((InstrumentationTestRunner) instrumentation).getArguments();
            final String value = arguments == null ? null : arguments.getString(PACKAGE_ARGUMENT);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return DEFAULT_APP_PACKAGE;
    }

    private String inputMethodId(String packageName) {
        final InputMethodManager manager = (InputMethodManager) getInstrumentation().getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        String match = null;
        for (InputMethodInfo info : manager.getInputMethodList()) {
            if (!packageName.equals(info.getPackageName())) {
                continue;
            }
            if (match != null) {
                throw new AssertionFailedError(
                        "the target package has more than one IME; selection is ambiguous: "
                                + packageName);
            }
            match = info.getId();
        }
        if (match == null) {
            throw new AssertionFailedError("no installed IME service for package " + packageName);
        }
        return match;
    }

    private static boolean isEnabled(String enabledList, String targetIme) {
        if (enabledList == null) {
            return false;
        }
        int start = 0;
        while (start <= enabledList.length()) {
            final int end = enabledList.indexOf(':', start);
            if (targetIme.equals(enabledList.substring(
                    start, end < 0 ? enabledList.length() : end))) {
                return true;
            }
            if (end < 0) {
                return false;
            }
            start = end + 1;
        }
        return false;
    }

    private void awaitSelectedIme(String targetIme) {
        final long deadline = SystemClock.elapsedRealtime() + SELECTION_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (targetIme.equals(readSecureSetting(DEFAULT_INPUT_METHOD))) {
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionFailedError("the system did not select " + targetIme);
    }

    private void assertSelectedIme(String targetIme) {
        assertEquals("the system-selected IME changed during the probe", targetIme,
                readSecureSetting(DEFAULT_INPUT_METHOD));
    }

    private void configureAccessibilityWindows() {
        final UiAutomation automation = getInstrumentation().getUiAutomation();
        final AccessibilityServiceInfo info = automation.getServiceInfo();
        assertNotNull("UiAutomation accessibility service information is unavailable", info);
        originalAccessibilityFlags = info.flags;
        accessibilityFlagsSaved = true;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        automation.setServiceInfo(info);
    }

    private void restoreAccessibilityFlags() {
        if (!accessibilityFlagsSaved) {
            return;
        }
        final UiAutomation automation = getInstrumentation().getUiAutomation();
        final AccessibilityServiceInfo info = automation.getServiceInfo();
        if (info != null) {
            info.flags = originalAccessibilityFlags;
            automation.setServiceInfo(info);
        }
        accessibilityFlagsSaved = false;
    }

    private void prepareField() {
        final Context context = getInstrumentation().getTargetContext();
        final Intent intent = new Intent()
                .setClassName(context, FirstGlidePerfActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final Activity activity = getInstrumentation().startActivitySync(intent);
        assertTrue("the probe activity has the wrong class",
                activity instanceof FirstGlidePerfActivity);
        startedActivity = (FirstGlidePerfActivity) activity;
        field = (EditText) activity.findViewById(FirstGlidePerfActivity.FIELD_ID);
        assertNotNull("the probe text field is missing", field);
        showKeyboard();
    }

    private void showKeyboard() {
        final InputMethodManager manager = inputMethodManager();
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                field.requestFocus();
                manager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        final long layoutDeadline = SystemClock.elapsedRealtime() + IME_TIMEOUT_MS;
        while ((field.getWidth() == 0 || field.getHeight() == 0)
                && SystemClock.elapsedRealtime() < layoutDeadline) {
            SystemClock.sleep(100);
        }
        assertTrue("the probe field was not laid out", field.getWidth() > 0 && field.getHeight() > 0);
        final int[] location = new int[2];
        field.getLocationOnScreen(location);
        tap(location[0] + field.getWidth() / 2.0f, location[1] + field.getHeight() / 2.0f);

        final long acceptingDeadline = SystemClock.elapsedRealtime() + IME_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < acceptingDeadline) {
            if (field.hasFocus() && manager.isAcceptingText()) {
                // Wait out window movement before sampling live key bounds and injecting down.
                SystemClock.sleep(KEYBOARD_SETTLE_MS);
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionFailedError("the selected IME never accepted the probe field");
    }

    private void hideKeyboard() {
        final boolean[] hidden = {false};
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                hidden[0] = inputMethodManager().hideSoftInputFromWindow(field.getWindowToken(), 0);
            }
        });
        assertTrue("the keyboard must hide before the idle window", hidden[0]);
    }

    private void clearField() {
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                field.getText().clear();
                field.setSelection(0);
            }
        });
        SystemClock.sleep(FIELD_SETTLE_MS);
    }

    private InputMethodManager inputMethodManager() {
        return (InputMethodManager) field.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private KeyPath awaitKeyPath() {
        final long deadline = SystemClock.elapsedRealtime() + IME_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            final KeyPath path = readLiveKeyPath();
            if (path != null) {
                return path;
            }
            SystemClock.sleep(200);
        }
        throw new AssertionFailedError(
                "visible live-key accessibility nodes were not found for " + appPackage);
    }

    private KeyPath readLiveKeyPath() {
        final List<AccessibilityWindowInfo> windows =
                getInstrumentation().getUiAutomation().getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    continue;
                }
                final AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                try {
                    final Rect[] keys = new Rect[REQUIRED_KEYS.length];
                    collectKeyBounds(root, keys);
                    boolean complete = true;
                    for (Rect key : keys) {
                        complete &= key != null;
                    }
                    if (complete) {
                        return makePath(keys);
                    }
                } finally {
                    root.recycle();
                }
            }
            return null;
        } finally {
            for (AccessibilityWindowInfo window : windows) {
                window.recycle();
            }
        }
    }

    private void collectKeyBounds(AccessibilityNodeInfo node, Rect[] keys) {
        if (node.isVisibleToUser() && node.isClickable()
                && TextUtils.equals(appPackage, node.getPackageName())) {
            final CharSequence description = node.getContentDescription();
            for (int i = 0; i < REQUIRED_KEYS.length; i++) {
                if (keys[i] == null && describesKey(REQUIRED_KEYS[i], description)) {
                    final Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) {
                        keys[i] = bounds;
                    }
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            final AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            try {
                collectKeyBounds(child, keys);
            } finally {
                child.recycle();
            }
        }
    }

    private static boolean describesKey(char key, CharSequence rawDescription) {
        if (rawDescription == null) {
            return false;
        }
        final String description = rawDescription.toString().trim().toLowerCase(Locale.ROOT);
        if (key == 'ә') {
            return description.equals("ә")
                    || description.endsWith("tatar schwa")
                    || description.endsWith("ә хәрефе")
                    || description.endsWith("татарская э");
        }
        final String letter = String.valueOf(key);
        return description.equals(letter)
                || description.equals("capital " + letter)
                || description.equals("баш " + letter)
                || description.equals("заглавная " + letter);
    }

    private static KeyPath makePath(Rect[] keys) {
        final Point[] points = new Point[GLIDE_LETTERS.length];
        for (int i = 0; i < GLIDE_LETTERS.length; i++) {
            final Rect bounds = keys[requiredKeyIndex(GLIDE_LETTERS[i])];
            points[i] = new Point((bounds.left + bounds.right) / 2.0f,
                    (bounds.top + bounds.bottom) / 2.0f);
        }
        return new KeyPath(points);
    }

    private static int requiredKeyIndex(char letter) {
        for (int i = 0; i < REQUIRED_KEYS.length; i++) {
            if (REQUIRED_KEYS[i] == letter) {
                return i;
            }
        }
        throw new IllegalArgumentException("not a required glide key");
    }

    /** Returns the timestamp immediately before the real ACTION_UP injection. */
    private double measureFirstVisible(KeyPath path) throws InterruptedException {
        assertEquals("each latency run must start with an empty field", "",
                field.getText().toString());
        final CommitWatcher watcher = new CommitWatcher();
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                field.addTextChangedListener(watcher);
            }
        });
        long liftedNanos = 0L;
        try {
            liftedNanos = drivePath(path);
            if (!watcher.awaitCommit()) {
                throw new AssertionFailedError("сәләм was not committed after the glide; field='"
                        + field.getText().toString() + "'");
            }
        } finally {
            getInstrumentation().runOnMainSync(new Runnable() {
                @Override public void run() {
                    field.removeTextChangedListener(watcher);
                }
            });
        }
        assertEquals("the glide committed unexpected text", EXPECTED_WORD,
                field.getText().toString());
        final long committedNanos = watcher.committedNanos.get();
        assertTrue("the TextWatcher commit timestamp is missing",
                liftedNanos > 0L && committedNanos >= liftedNanos);
        return (committedNanos - liftedNanos) / 1_000_000.0;
    }

    private long drivePath(KeyPath path) {
        final long downTime = SystemClock.uptimeMillis();
        inject(MotionEvent.ACTION_DOWN, path.points[0], downTime, downTime);
        for (int segment = 0; segment < path.points.length - 1; segment++) {
            final Point from = path.points[segment];
            final Point to = path.points[segment + 1];
            for (int step = 1; step <= INTERPOLATION_STEPS; step++) {
                SystemClock.sleep(STEP_DELAY_MS);
                final float fraction = step / (float) INTERPOLATION_STEPS;
                final Point point = new Point(from.x + (to.x - from.x) * fraction,
                        from.y + (to.y - from.y) * fraction);
                inject(MotionEvent.ACTION_MOVE, point, downTime, SystemClock.uptimeMillis());
            }
        }
        final long upTime = SystemClock.uptimeMillis();
        final long liftedNanos = SystemClock.elapsedRealtimeNanos();
        inject(MotionEvent.ACTION_UP, path.points[path.points.length - 1], downTime, upTime);
        return liftedNanos;
    }

    private void tap(float x, float y) {
        final Point point = new Point(x, y);
        final long downTime = SystemClock.uptimeMillis();
        inject(MotionEvent.ACTION_DOWN, point, downTime, downTime);
        SystemClock.sleep(TAP_HOLD_MS);
        inject(MotionEvent.ACTION_UP, point, downTime, SystemClock.uptimeMillis());
    }

    private void inject(int action, Point point, long downTime, long eventTime) {
        final MotionEvent event = MotionEvent.obtain(
                downTime, eventTime, action, point.x, point.y, 0);
        try {
            if (!getInstrumentation().getUiAutomation().injectInputEvent(event, true)) {
                throw new AssertionFailedError("UiAutomation rejected a glide input event");
            }
        } finally {
            event.recycle();
        }
    }

    private void closeActivity() {
        if (startedActivity == null) {
            return;
        }
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                if (field != null) {
                    inputMethodManager().hideSoftInputFromWindow(field.getWindowToken(), 0);
                }
                startedActivity.finish();
            }
        });
        startedActivity = null;
        field = null;
    }

    private String statsLine(
            String check, List<Double> samples, double budgetMs, String protocol) {
        final List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        final int n = sorted.size();
        final double median = n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        final double p95 = sorted.get((int) Math.ceil(n * 0.95) - 1);
        final StringBuilder values = new StringBuilder();
        for (Double value : samples) {
            if (values.length() > 0) {
                values.append(',');
            }
            values.append(fmt(value));
        }
        return "RESULT|INFO|" + check + "|runs=" + n
                + " median_ms=" + fmt(median) + " p95_ms=" + fmt(p95)
                + " min_ms=" + fmt(sorted.get(0)) + " max_ms=" + fmt(sorted.get(n - 1))
                + " values_ms=" + values + " budget_ms=" + fmt(budgetMs)
                + " over_budget=" + (p95 > budgetMs) + " build=" + buildLabel()
                + " " + protocol;
    }

    private String buildLabel() {
        final String dump = shell("dumpsys package " + appPackage);
        int start = 0;
        while (true) {
            final int end = dump.indexOf('\n', start);
            final String line = dump.substring(
                    start, end < 0 ? dump.length() : end).trim();
            if (line.startsWith("pkgFlags=[") || line.startsWith("flags=[")) {
                return line.contains("DEBUGGABLE") ? "debug" : "release";
            }
            if (end < 0) {
                return "unknown";
            }
            start = end + 1;
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private void emitResult(String line) {
        Log.i(TAG, line);
        final Bundle status = new Bundle();
        status.putString("tt_perf_result", line);
        getInstrumentation().sendStatus(0, status);
    }

    private String readSecureSetting(String key) {
        String value = shell("settings get secure " + key).trim();
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }
        return "null".equals(value) ? null : value;
    }

    private void restoreSecureSetting(String key, String value) {
        if (value == null) {
            shell("settings delete secure " + key);
        } else if (value.isEmpty()) {
            shell("content insert --uri content://settings/secure --bind name:s:" + key
                    + " --bind value:s:");
        } else {
            shell("settings put secure " + key + " " + value);
        }
    }

    private String shell(String command) {
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(
                             getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            final byte[] buffer = new byte[4096];
            final StringBuilder output = new StringBuilder();
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.append(new String(buffer, 0, read, "UTF-8"));
            }
            return output.toString();
        } catch (IOException error) {
            throw new AssertionFailedError("shell command failed: " + command);
        }
    }

    private static final class CommitWatcher implements TextWatcher {
        private final CountDownLatch committed = new CountDownLatch(1);
        final AtomicLong committedNanos = new AtomicLong();

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override public void afterTextChanged(Editable text) {
            if (TextUtils.equals(EXPECTED_WORD, text)
                    && committedNanos.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())) {
                committed.countDown();
            }
        }

        boolean awaitCommit() throws InterruptedException {
            try {
                return committed.await(COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            }
        }
    }

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class KeyPath {
        final Point[] points;

        KeyPath(Point[] points) {
            this.points = points;
        }
    }

    private static final String TAG = "FirstGlidePerf";
    private static final String PACKAGE_ARGUMENT = "ttPackage";
    private static final String DEFAULT_APP_PACKAGE = "org.tatarkeyboard.ime.debug";
    private static final String DEFAULT_INPUT_METHOD = "default_input_method";
    private static final String ENABLED_INPUT_METHODS = "enabled_input_methods";
    private static final String EXPECTED_WORD = "сәләм";
    private static final char[] REQUIRED_KEYS = {'с', 'ә', 'л', 'м'};
    private static final char[] GLIDE_LETTERS = {'с', 'ә', 'л', 'ә', 'м'};
    private static final int LATENCY_RUNS = 5;
    private static final long IDLE_RELEASE_MS = 12_000L;
    private static final long SELECTION_TIMEOUT_MS = 10_000L;
    private static final long IME_TIMEOUT_MS = 30_000L;
    private static final long COMMIT_TIMEOUT_MS = 30_000L;
    private static final long FIELD_SETTLE_MS = 500L;
    private static final long KEYBOARD_SETTLE_MS = 3_000L;
    private static final long TAP_HOLD_MS = 60L;
    private static final int INTERPOLATION_STEPS = 8;
    private static final long STEP_DELAY_MS = 20L;
    private static final double COLD_FIRST_GLIDE_BUDGET_MS = 500.0;
    private static final double WARM_DECODE_BUDGET_MS = 50.0;
}

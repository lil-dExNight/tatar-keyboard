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

package rkr.simplekeyboard.inputmethod.latin.spellcheck;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Trace;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Log;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import junit.framework.AssertionFailedError;

/**
 * Measures the exported spell checker through the platform client from the test package's own
 * process. The cold arm covers session creation, service binding and process creation up to the
 * first checked sentence callback; the warm arm times checked sentence round trips on one session.
 *
 * The host ritual kills the keyboard package before the cold method. Selection is made with the
 * three secure spell-checker settings and is restored afterwards, including deletion of keys
 * that were absent before. Sessions request tt_RU while following those settings. Paragraph text
 * is never logged. Java, because the test package carries no Kotlin runtime of its own.
 */
public final class SpellCheckPerfDeviceTest extends InstrumentationTestCase {

    public void testColdFirstAnswer() throws Exception {
        final String paragraph = loadParagraph();
        withSpellCheckerSelection(new Runnable() {
            @Override public void run() {
                try {
                    assertNoAppProcess();
                    final Callback callback = new Callback();
                    final TextInfo[] requests = {new TextInfo(paragraph)};
                    final SpellCheckerSession[] session = new SpellCheckerSession[1];
                    final long[] startedNanos = {0L};
                    final long deadline = SystemClock.elapsedRealtime() + COLD_TIMEOUT_MS;
                    try {
                        // Callbacks run on the main looper, so creation and every request run
                        // there. The clock starts immediately before session creation.
                        getInstrumentation().runOnMainSync(new Runnable() {
                            @Override public void run() {
                                startedNanos[0] = SystemClock.elapsedRealtimeNanos();
                                session[0] = textServicesManager().newSpellCheckerSession(
                                        null, TATAR_RU, callback, false);
                            }
                        });
                        assertNotNull("the tt_RU spell-checker session must be created", session[0]);
                        double firstCallbackMs = Double.NaN;
                        int requestCount = 0;
                        while (true) {
                            final RoundTrip round = new RoundTrip();
                            round.startedNanos = startedNanos[0];
                            callback.round = round;
                            getInstrumentation().runOnMainSync(new Runnable() {
                                @Override public void run() {
                                    session[0].getSentenceSuggestions(requests, SUGGESTIONS_LIMIT);
                                }
                            });
                            requestCount++;
                            final long remainingMs = Math.max(1L,
                                    deadline - SystemClock.elapsedRealtime());
                            final SentenceSuggestionsInfo[] answers = round.await(remainingMs);
                            if (Double.isNaN(firstCallbackMs)) {
                                firstCallbackMs = nanosToMs(round.elapsedNanos());
                            }
                            Log.i(TAG, "cold callback request=" + requestCount
                                    + " elapsed_ms=" + fmt(nanosToMs(round.elapsedNanos()))
                                    + " flagged_words=" + flaggedWordCount(answers));
                            if (isCheckedAnswer(answers)) {
                                final double checkedMs = nanosToMs(round.elapsedNanos());
                                emitResult(statsLine(
                                        "RESULT|INFO|spellcheck-cold|runs=1 words=" + WORDS
                                                + " requests=" + requestCount
                                                + " flagged_words=" + EXPECTED_FLAGGED_WORDS
                                                + " checked_index=" + EXPECTED_FLAG_INDEX
                                                + " first_callback_ms=" + fmt(firstCallbackMs),
                                        Collections.singletonList(checkedMs),
                                        COLD_BUDGET_MS,
                                        COLD_PROTOCOL));
                                return;
                            }
                            if (SystemClock.elapsedRealtime() >= deadline) {
                                throw new AssertionFailedError(
                                        "no checked sentence answer before the cold timeout");
                            }
                            // Pace unchecked retries instead of flooding the platform's request
                            // throttle while the source is being prepared.
                            SystemClock.sleep(COLD_RETRY_DELAY_MS);
                        }
                    } finally {
                        closeQuietly(session[0]);
                    }
                } catch (Exception error) {
                    throw new AssertionFailedError(error.toString());
                }
            }
        });
    }

    public void testWarmParagraph() throws Exception {
        final String paragraph = loadParagraph();
        final TextInfo[] requests = {new TextInfo(paragraph)};
        withSpellCheckerSelection(new Runnable() {
            @Override public void run() {
                final Callback callback = new Callback();
                SpellCheckerSession session = null;
                try {
                    session = newSession(callback);
                    int readyWarmups = 0;
                    final long readyDeadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS;
                    for (int i = 0; i < WARMUPS; i++) {
                        while (true) {
                            final RoundTrip round = new RoundTrip();
                            submit(session, requests, callback, round, false);
                            final SentenceSuggestionsInfo[] answers = round.await(WARM_TIMEOUT_MS);
                            if (isCheckedAnswer(answers)) {
                                break;
                            }
                            readyWarmups++;
                            if (SystemClock.elapsedRealtime() >= readyDeadline) {
                                throw new AssertionFailedError(
                                        "no checked sentence answer before the warm readiness timeout");
                            }
                        }
                        if (i == 0) {
                            awaitHostPssSample();
                        }
                    }

                    final List<Double> samples = new ArrayList<>(MEASURED_RUNS);
                    for (int i = 0; i < MEASURED_RUNS; i++) {
                        final RoundTrip round = new RoundTrip();
                        submit(session, requests, callback, round, true);
                        final SentenceSuggestionsInfo[] answers = round.await(WARM_TIMEOUT_MS);
                        assertTrue("a measured answer was not checked",
                                isCheckedAnswer(answers));
                        samples.add(nanosToMs(round.elapsedNanos()));
                    }

                    emitResult(statsLine(
                            "RESULT|INFO|spellcheck-warm|runs=" + MEASURED_RUNS
                                    + " warmups=" + WARMUPS + " ready_warmups=" + readyWarmups
                                    + " words=" + WORDS
                                    + " flagged_words=" + EXPECTED_FLAGGED_WORDS
                                    + " checked_index=" + EXPECTED_FLAG_INDEX,
                            samples,
                            WARM_BUDGET_MS,
                            WARM_PROTOCOL));
                } catch (Exception error) {
                    throw new AssertionFailedError(error.toString());
                } finally {
                    closeQuietly(session);
                }
            }
        });
    }

    private String loadParagraph() throws IOException {
        final StringBuilder text = new StringBuilder();
        try (InputStream input = getInstrumentation().getContext().getAssets()
                .open(ASSET_NAME)) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                text.append(new String(buffer, 0, read, "UTF-8"));
            }
        }
        final String paragraph = text.toString().trim();
        int wordCount = 0;
        boolean inWord = false;
        for (int i = 0; i < paragraph.length(); i++) {
            if (Character.isWhitespace(paragraph.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                wordCount++;
                inWord = true;
            }
        }
        assertEquals(WORDS, wordCount);
        return paragraph;
    }

    private SpellCheckerSession newSession(Callback callback) {
        final SpellCheckerSession[] session = new SpellCheckerSession[1];
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                session[0] = textServicesManager().newSpellCheckerSession(
                        null, TATAR_RU, callback, false);
            }
        });
        assertNotNull("the tt_RU spell-checker session must be created", session[0]);
        return session[0];
    }

    private void submit(
            SpellCheckerSession session,
            TextInfo[] requests,
            Callback callback,
            RoundTrip round,
            boolean measured) {
        callback.round = round;
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override public void run() {
                if (measured) {
                    round.startedNanos = SystemClock.elapsedRealtimeNanos();
                }
                session.getSentenceSuggestions(requests, SUGGESTIONS_LIMIT);
            }
        });
    }

    private void assertNoAppProcess() {
        assertTrue(
                "the app process must be stopped before the cold spell-checker start",
                shell("pidof " + appPackage()).trim().isEmpty());
    }

    private void withSpellCheckerSelection(Runnable block) {
        final Map<String, String> prior = new LinkedHashMap<>();
        for (String key : SETTINGS_KEYS) {
            prior.put(key, readSecureSetting(key));
        }
        try {
            final String checker =
                    new ComponentName(appPackage(), SERVICE_CLASS).flattenToString();
            // Repeating the current selection is unnecessary and, on newer releases, may cause
            // the framework to bind the service before the cold measurement starts.
            if (!checker.equals(prior.get("selected_spell_checker"))
                    || !"0".equals(prior.get("selected_spell_checker_subtype"))
                    || !"1".equals(prior.get("spell_checker_enabled"))) {
                shell("settings put secure selected_spell_checker " + checker);
                shell("settings put secure selected_spell_checker_subtype 0");
                shell("settings put secure spell_checker_enabled 1");
            }
            block.run();
        } finally {
            for (String key : SETTINGS_KEYS) {
                restoreSecureSetting(key, prior.get(key));
            }
        }
    }

    private String appPackage() {
        final android.app.Instrumentation instrumentation = getInstrumentation();
        if (instrumentation instanceof InstrumentationTestRunner) {
            final Bundle arguments = ((InstrumentationTestRunner) instrumentation).getArguments();
            if (arguments != null) {
                final String value = arguments.getString(APP_PACKAGE_ARGUMENT);
                if (value != null) {
                    return value;
                }
            }
        }
        return DEFAULT_APP_PACKAGE;
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
            // The settings CLI cannot pass an empty argument through UiAutomation, but the
            // content command keeps the empty value after the binding colon.
            shell("content insert --uri content://settings/secure --bind name:s:" + key
                    + " --bind value:s:");
        } else {
            shell("settings put secure " + key + " " + value);
        }
    }

    private String shell(String command) {
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(
                             getInstrumentation().getUiAutomation()
                                     .executeShellCommand(command))) {
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

    private TextServicesManager textServicesManager() {
        return (TextServicesManager) getInstrumentation().getContext()
                .getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
    }

    private static void assertSentenceAnswer(SentenceSuggestionsInfo[] answers) {
        assertNotNull("the callback must carry a sentence answer", answers);
        assertEquals("one sentence answer per request", 1, answers.length);
        assertEquals("one SuggestionsInfo per paragraph word", WORDS,
                answers[0].getSuggestionsCount());
    }

    /** The paragraph's known typo proves that the source was loaded before the answer. */
    private static boolean isCheckedAnswer(SentenceSuggestionsInfo[] answers) {
        final SuggestionsInfo expected = answers[0].getSuggestionsInfoAt(EXPECTED_FLAG_INDEX);
        return flaggedWordCount(answers) == EXPECTED_FLAGGED_WORDS
                && (expected.getSuggestionsAttributes()
                        & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0;
    }

    private static int flaggedWordCount(SentenceSuggestionsInfo[] answers) {
        assertSentenceAnswer(answers);
        int flagged = 0;
        for (int i = 0; i < WORDS; i++) {
            final SuggestionsInfo word = answers[0].getSuggestionsInfoAt(i);
            if ((word.getSuggestionsAttributes()
                    & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0) {
                flagged++;
            }
        }
        return flagged;
    }

    private static void closeQuietly(SpellCheckerSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException error) {
            Log.i(TAG, "spell-checker session close failed");
        }
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private void emitResult(String line) {
        Log.i(TAG, line);
        final Bundle status = new Bundle();
        status.putString("tt_perf_result", line);
        getInstrumentation().sendStatus(0, status);
    }

    /** Pauses the warm run while the host samples PSS after the first answer. */
    private void awaitHostPssSample() {
        final Bundle status = new Bundle();
        status.putInt("tt_perf_first_checked_answer", 1);
        getInstrumentation().sendStatus(0, status);
        final long deadline = SystemClock.elapsedRealtime() + PSS_WAIT_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if ("done".equals(shell("cat " + PSS_MARKER_PATH).trim())) {
                return;
            }
            SystemClock.sleep(50);
        }
        throw new AssertionFailedError("timed out waiting for the host PSS sample");
    }

    private String statsLine(
            String header,
            List<Double> samples,
            double budgetMs,
            String protocol) {
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
        return header + " median_ms=" + fmt(median) + " p95_ms=" + fmt(p95)
                + " min_ms=" + fmt(sorted.get(0)) + " max_ms=" + fmt(sorted.get(n - 1))
                + " values_ms=" + values + " budget_ms=" + fmt(budgetMs)
                + " over_budget=" + (p95 >= budgetMs) + " build=" + buildLabel() + " " + protocol;
    }

    /**
     * Build type of the package under test, read from the package manager rather than assumed:
     * a release-like bench build is not debuggable even though it carries the debug key.
     */
    private String buildLabel() {
        final String dump = shell("dumpsys package " + appPackage());
        for (String line : dump.split("\n")) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("pkgFlags=[") || trimmed.startsWith("flags=[")) {
                return trimmed.contains("DEBUGGABLE") ? "debug" : "release";
            }
        }
        return "unknown";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Routes each session callback to the request currently awaited by the test thread. */
    private static final class Callback
            implements SpellCheckerSession.SpellCheckerSessionListener {
        volatile RoundTrip round;

        @Override public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
            final RoundTrip current = round;
            if (current != null) {
                current.complete(results);
            }
        }

        @Override public void onGetSuggestions(SuggestionsInfo[] results) {}
    }

    private static final class RoundTrip {
        private final CountDownLatch done = new CountDownLatch(1);

        volatile long startedNanos;
        private volatile long finishedNanos;
        private volatile SentenceSuggestionsInfo[] answer;

        void complete(SentenceSuggestionsInfo[] results) {
            if (finishedNanos != 0L) {
                return;
            }
            Trace.beginSection("TT#spellClientCallback");
            try {
                finishedNanos = SystemClock.elapsedRealtimeNanos();
                answer = results;
                done.countDown();
            } finally {
                Trace.endSection();
            }
        }

        SentenceSuggestionsInfo[] await(long timeoutMs) {
            final boolean completed;
            try {
                completed = done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionFailedError("interrupted while waiting for the callback");
            }
            if (!completed) {
                throw new AssertionFailedError(
                        "the sentence callback timed out after " + timeoutMs + " ms");
            }
            return answer;
        }

        long elapsedNanos() {
            if (finishedNanos == 0L) {
                throw new AssertionFailedError("the measured callback timestamp is missing");
            }
            return finishedNanos - startedNanos;
        }
    }

    private static final String TAG = "SpellCheckPerfTest";
    private static final String ASSET_NAME = "tt_spellcheck_warm_paragraph.txt";
    private static final String APP_PACKAGE_ARGUMENT = "ttPackage";
    private static final String DEFAULT_APP_PACKAGE = "org.tatarkeyboard.ime.debug";
    private static final String SERVICE_CLASS =
            "rkr.simplekeyboard.inputmethod.latin.spellcheck.TatarSpellCheckerService";
    private static final Locale TATAR_RU = new Locale("tt", "RU");
    private static final String[] SETTINGS_KEYS = {
            "selected_spell_checker",
            "selected_spell_checker_subtype",
            "spell_checker_enabled",
    };

    private static final int WORDS = 200;
    private static final int EXPECTED_FLAG_INDEX = 38;
    private static final int EXPECTED_FLAGGED_WORDS = 1;
    private static final int SUGGESTIONS_LIMIT = 5;
    private static final int WARMUPS = 5;
    private static final int MEASURED_RUNS = 31;
    private static final long WARM_TIMEOUT_MS = 15_000L;
    private static final long READY_TIMEOUT_MS = 30_000L;
    private static final long COLD_TIMEOUT_MS = 30_000L;
    private static final long COLD_RETRY_DELAY_MS = 50L;
    private static final long PSS_WAIT_TIMEOUT_MS = 60_000L;
    private static final String PSS_MARKER_PATH = "/data/local/tmp/tt-spellcheck-after-pss";
    private static final double WARM_BUDGET_MS = 100.0;
    private static final double COLD_BUDGET_MS = 500.0;
    private static final String WARM_PROTOCOL = "protocol=warm-sentence-request-to-callback";
    private static final String COLD_PROTOCOL = "protocol=cold-session-request-to-first-checked-callback";
}

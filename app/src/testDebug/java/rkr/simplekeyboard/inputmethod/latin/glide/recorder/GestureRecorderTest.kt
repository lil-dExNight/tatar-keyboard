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

package rkr.simplekeyboard.inputmethod.latin.glide.recorder

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.glide.GlidePath

/**
 * [GestureRecorder]: discard by default, arming requires a dictionary-backed prompt pool, the
 * top-1 match is computed against the prompt (never stored), and the prompt advances per
 * gesture.
 */
class GestureRecorderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun recorder(): GestureRecorder =
        GestureRecorder(GestureRingBuffer(temporaryFolder.newFolder()))

    private fun path(): GlidePath {
        val path = GlidePath()
        for (i in 0..10) path.addPoint(i * 10f, i * 3f, i * 20f)
        return path
    }

    @Test
    fun aGestureIsDiscardedWhileDisarmed() {
        val recorder = recorder()
        recorder.setPromptSource(GesturePromptSource(listOf("word"), Random(1)))
        val outcome = recorder.onGesture(path(), 50f, "tt", 480, "word")
        assertEquals(GestureRecorder.Outcome.DISCARDED, outcome)
        assertEquals(0, recorder.recordCount())
        assertNull(recorder.currentPrompt())
    }

    @Test
    fun armingWithoutAPromptPoolFails() {
        val recorder = recorder()
        assertFalse(recorder.setArmed(true))
        assertFalse(recorder.armed)
        recorder.setPromptSource(GesturePromptSource(emptyList()))
        assertFalse("an empty pool never arms the recorder", recorder.setArmed(true))
        assertFalse(recorder.armed)
    }

    @Test
    fun anArmedRecorderRecordsAndAdvancesThePrompt() {
        val recorder = recorder()
        // A fixed seed makes the prompt sequence deterministic: the first draw is the pool's
        // only word.
        recorder.setPromptSource(GesturePromptSource(listOf("word"), Random(1)))
        assertTrue(recorder.setArmed(true))
        assertEquals("word", recorder.currentPrompt())

        val outcome = recorder.onGesture(path(), 50f, "tt", 480, "WORD")
        assertEquals(GestureRecorder.Outcome.RECORDED, outcome)
        assertEquals(1, recorder.recordCount())
        val line = recorder.snapshot().single()
        assertTrue(line.contains("\"prompt\":\"word\""))
        assertTrue("the match compare ignores case", line.contains("\"top1_match\":true"))
        assertTrue(line.contains("\"layout_id\":\"tt\""))
        assertTrue(line.contains("\"density_bucket\":480"))
    }

    @Test
    fun aMismatchingDecodeRecordsTop1MatchFalse() {
        val recorder = recorder()
        recorder.setPromptSource(GesturePromptSource(listOf("word"), Random(1)))
        assertTrue(recorder.setArmed(true))
        assertEquals(GestureRecorder.Outcome.RECORDED,
            recorder.onGesture(path(), 50f, "tt", 480, "other"))
        assertTrue(recorder.snapshot().single().contains("\"top1_match\":false"))
        // A null decode (empty strip) is a mismatch, not a crash.
        assertEquals(GestureRecorder.Outcome.RECORDED,
            recorder.onGesture(path(), 50f, "tt", 480, null))
        assertTrue(recorder.snapshot()[1].contains("\"top1_match\":false"))
    }

    @Test
    fun aMalformedGestureIsRejectedNotRecorded() {
        val recorder = recorder()
        recorder.setPromptSource(GesturePromptSource(listOf("word"), Random(1)))
        assertTrue(recorder.setArmed(true))
        assertEquals(GestureRecorder.Outcome.REJECTED,
            recorder.onGesture(GlidePath(), 50f, "tt", 480, "word"))
        assertEquals(0, recorder.recordCount())
        // The recorder stays armed and holds a prompt for the next try.
        assertNotNull(recorder.currentPrompt())
    }

    @Test
    fun discardAllEmptiesTheBuffer() {
        val recorder = recorder()
        recorder.setPromptSource(GesturePromptSource(listOf("word"), Random(1)))
        assertTrue(recorder.setArmed(true))
        recorder.onGesture(path(), 50f, "tt", 480, "word")
        assertEquals(1, recorder.recordCount())
        assertTrue(recorder.discardAll())
        assertEquals(0, recorder.recordCount())
    }

    @Test
    fun theDensityBucketIsTheNearestStandardBucket() {
        assertEquals(120, GestureRecorder.densityBucket(120))
        assertEquals(160, GestureRecorder.densityBucket(170))
        assertEquals(213, GestureRecorder.densityBucket(213))
        assertEquals(240, GestureRecorder.densityBucket(250))
        // An exact tie (280 sits between 240 and 320) breaks toward the lower bucket.
        assertEquals(240, GestureRecorder.densityBucket(280))
        assertEquals(480, GestureRecorder.densityBucket(420))
        assertEquals(640, GestureRecorder.densityBucket(700))
        assertEquals(120, GestureRecorder.densityBucket(0))
    }
}

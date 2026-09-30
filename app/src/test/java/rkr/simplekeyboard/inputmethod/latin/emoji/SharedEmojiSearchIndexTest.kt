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

package rkr.simplekeyboard.inputmethod.latin.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One parsed [EmojiSearchIndex] per process, however many consumers ask.
 * The two production consumers (the suggestion strip's spoken names and the panel search) live on
 * different background executors, so the tests drive [SharedEmojiSearchIndex] through its
 * injectable opener — the production one only adds `assets.open` — and assert identity, not just
 * equality: a second parsed copy would defeat the sharing.
 */
class SharedEmojiSearchIndexTest {

    private val oneEntry = "😀\tсмайлик\tgrinning smile\n"

    @Test
    fun bothConsumersGetTheIdenticalInstance() {
        val opens = AtomicInteger(0)
        val holder = SharedEmojiSearchIndex {
            opens.incrementAndGet()
            oneEntry.byteInputStream()
        }

        val suggestSide = holder.get()
        val panelSide = holder.get()

        assertSame("the second consumer must not parse a copy of its own", suggestSide, panelSide)
        assertEquals(1, opens.get())
        assertEquals(1, suggestSide.entryCount)
    }

    @Test
    fun aFailedLoadIsSharedAndTerminal() {
        val opens = AtomicInteger(0)
        val holder = SharedEmojiSearchIndex {
            opens.incrementAndGet()
            null   // the asset cannot be opened at all
        }

        val first = holder.get()
        val second = holder.get()

        assertTrue(first.isEmpty)
        assertSame(EmojiSearchIndex.EMPTY, first)
        // Both consumers already treat an unusable asset as terminal for the process; the holder
        // caches that verdict instead of re-reading an asset that cannot get better.
        assertSame(first, second)
        assertEquals(1, opens.get())
    }

    @Test
    fun racingFirstCallsParseExactlyOnce() {
        val opens = AtomicInteger(0)
        val parseStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = SharedEmojiSearchIndex {
            opens.incrementAndGet()
            parseStarted.countDown()
            // Hold the parse open so the second caller reaches the monitor while the first is
            // still loading — the race both lazy consumers can hit on their separate executors.
            release.await(5, TimeUnit.SECONDS)
            oneEntry.byteInputStream()
        }
        val first = AtomicReference<EmojiSearchIndex?>()
        val second = AtomicReference<EmojiSearchIndex?>()
        val threadA = Thread { first.set(holder.get()) }
        val threadB = Thread { second.set(holder.get()) }

        threadA.start()
        assertTrue(parseStarted.await(5, TimeUnit.SECONDS))
        threadB.start()
        Thread.sleep(150)
        release.countDown()
        threadA.join(5000)
        threadB.join(5000)

        assertSame(first.get(), second.get())
        assertEquals("a raced first load must still parse once", 1, opens.get())
    }
}

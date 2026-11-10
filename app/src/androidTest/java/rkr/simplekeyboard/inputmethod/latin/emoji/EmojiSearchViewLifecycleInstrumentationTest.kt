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

import android.test.InstrumentationTestCase
import android.view.View

/** View-side query cleanup, exercised on a real Android View without an editor. */
class EmojiSearchViewLifecycleInstrumentationTest : InstrumentationTestCase() {

    fun testClosingSearchClearsTheViewQuery() = onMain {
        val view = queryView()
        try {
            val dispatch = EmojiSearchView::class.java.getDeclaredMethod(
                "dispatchTarget",
                Integer.TYPE,
            ).apply { isAccessible = true }
            dispatch.invoke(view, CLOSE_TARGET)
            assertEquals("", queryText(view))
        } finally {
            view.release()
        }
    }

    fun testInputWindowHideClearsTheViewQuery() = onMain {
        val view = queryView()
        try {
            val hide = EmojiSearchView::class.java.getDeclaredMethod(
                "onWindowVisibilityChanged",
                Integer.TYPE,
            ).apply { isAccessible = true }
            hide.invoke(view, View.GONE)
            assertEquals("", queryText(view))
        } finally {
            view.release()
        }
    }

    fun testVisibilityLossClearsTheViewQuery() = onMain {
        val view = queryView()
        try {
            val hide = EmojiSearchView::class.java.getDeclaredMethod(
                "onVisibilityChanged",
                View::class.java,
                Integer.TYPE,
            ).apply { isAccessible = true }
            hide.invoke(view, view, View.GONE)
            assertEquals("", queryText(view))
        } finally {
            view.release()
        }
    }

    private fun queryView(): EmojiSearchView =
        EmojiSearchView(instrumentation.targetContext).apply {
            setIndex(EmojiSearchIndex.EMPTY)
            setQuery("smile")
            assertEquals("smile", queryText(this))
        }

    private fun queryText(view: EmojiSearchView): String {
        val field = EmojiSearchView::class.java.getDeclaredField("queryText")
            .apply { isAccessible = true }
        return field.get(view) as String
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        const val CLOSE_TARGET = -2
    }
}

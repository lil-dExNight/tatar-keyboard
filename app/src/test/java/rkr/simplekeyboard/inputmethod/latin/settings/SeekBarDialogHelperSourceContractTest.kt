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

package rkr.simplekeyboard.inputmethod.latin.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for multi-slider dialogs on the View-based settings screens. Android views are
 * unavailable on the plain JVM, so this pins the shared binding, per-entry persistence and the
 * compatibility overload without introducing a runtime test dependency.
 */
class SeekBarDialogHelperSourceContractTest {

    private val source by lazy {
        val relative =
            "src/main/java/rkr/simplekeyboard/inputmethod/latin/settings/SeekBarDialogHelper.kt"
        listOf(File(relative), File("app/$relative")).firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
    }

    private val normalized by lazy { source.replace(Regex("\\s+"), " ") }

    private fun section(start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue("missing section start: $start", startIndex >= 0)
        val remainder = source.substring(startIndex + start.length)
        val endIndex = remainder.indexOf(end)
        assertTrue("missing section end after $start: $end", endIndex >= 0)
        return remainder.substring(0, endIndex)
    }

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun multipleEntriesGetIndependentRowsThroughOneSharedBinding() {
        assertTrue(normalized.contains(
            "data class Entry( val title: CharSequence, val key: String, val minValue: Int, " +
                "val maxValue: Int, val stepValue: Int, val proxy: ValueProxy, " +
                "val enabled: Boolean = true )"))
        assertTrue(normalized.contains(
            "fun show( activity: Activity, dialogTitle: CharSequence, entries: List<Entry>, " +
                "onValueChanged: () -> Unit ): AlertDialog {"))

        val setup = section(
            "        dialogTitle: CharSequence,",
            "        val dialog = AlertDialog.Builder(activity)")
        assertTrue(setup.contains("orientation = LinearLayout.VERTICAL"))
        assertTrue(setup.contains("entries.map { entry ->"))
        assertTrue(setup.contains("EntryBinding(entry, view)"))
        assertTrue(setup.contains("TextView(activity).apply { text = entry.title }"))
        assertEquals("every entry must reuse the existing slider layout",
            1, source.occurrencesOf("R.layout.seek_bar_dialog"))

        val binding = section("    private class EntryBinding(", "\n    fun show(")
        assertTrue(binding.contains("private val entry: Entry"))
        assertTrue(binding.contains("entry.proxy.readValue(entry.key)"))
        assertEquals(1, binding.occurrencesOf("setOnSeekBarChangeListener"))
    }

    @Test
    fun oneOkAndOneDefaultCommitAllEntriesWhileCancelWritesNothing() {
        val ok = section(
            ".setPositiveButton(android.R.string.ok)",
            ".setNegativeButton(android.R.string.cancel, null)")
        assertTrue(ok.contains("bindings.forEach { it.writeValue() }"))
        assertEquals(1, ok.occurrencesOf("onValueChanged()"))

        val cancel = section(
            ".setNegativeButton(android.R.string.cancel, null)",
            ".setNeutralButton(R.string.button_default)")
        assertFalse(cancel.contains("writeValue"))
        assertFalse(cancel.contains("writeDefaultValue"))
        assertFalse(cancel.contains("onValueChanged"))

        val reset = section(".setNeutralButton(R.string.button_default)", ".create()")
        assertTrue(reset.contains("bindings.forEach { it.writeDefaultValue() }"))
        assertEquals(1, reset.occurrencesOf("onValueChanged()"))
        assertEquals(1, source.occurrencesOf(".setPositiveButton("))
        assertEquals(1, source.occurrencesOf(".setNeutralButton("))

        val write = section("        fun writeValue() {", "\n        }")
        assertTrue(write.contains(
            "entry.proxy.writeValue(clipValue(seekBar.progress + entry.minValue), entry.key)"))
        val writeDefault = section("        fun writeDefaultValue() {", "\n        }")
        assertTrue(writeDefault.contains("entry.proxy.writeDefaultValue(entry.key)"))
    }

    @Test
    fun sharedBindingKeepsMappingFeedbackStepClippingAndAccessibilityIdentity() {
        val binding = section("    private class EntryBinding(", "\n    fun show(")
        assertTrue(binding.contains("seekBar.max = entry.maxValue - entry.minValue"))
        assertTrue(binding.contains("value.coerceIn(entry.minValue, entry.maxValue)"))
        assertTrue(binding.contains("clipped - clipped % entry.stepValue"))
        assertTrue(binding.contains("clipValue(progress + entry.minValue)"))
        assertTrue(binding.contains(
            "entry.proxy.feedbackValue(clipValue(bar.progress + entry.minValue))"))
        assertTrue(binding.contains("seekBar.progress = clipValue(value) - entry.minValue"))
        assertTrue(binding.contains("seekBar.tag = entry.key"))
        assertTrue(binding.contains("seekBar.contentDescription = entry.title"))
        assertTrue(binding.contains("seekBar.isEnabled = entry.enabled"))
    }

    @Test
    fun legacySingleSliderShowDelegatesWithoutChangingItsApi() {
        val legacySignature =
            "fun show( activity: Activity, title: CharSequence, key: String, minValue: Int, " +
                "maxValue: Int, stepValue: Int, proxy: ValueProxy, " +
                "onValueChanged: () -> Unit ): AlertDialog ="
        assertTrue(normalized.contains(legacySignature))
        val delegation = normalized.substringAfter(legacySignature)
        assertTrue(delegation.startsWith(
            " show( activity, title, listOf( Entry( title = title, key = key, " +
                "minValue = minValue, maxValue = maxValue, stepValue = stepValue, " +
                "proxy = proxy ) ), onValueChanged )"))
        assertEquals("both overloads must share one dialog implementation",
            1, source.occurrencesOf("AlertDialog.Builder(activity)"))
        assertEquals(1, source.occurrencesOf("DialogUtils.filterObscuredTouches(dialog)"))
    }
}

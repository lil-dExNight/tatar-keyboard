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

package rkr.simplekeyboard.inputmethod.latin.lab

import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lab session log is content-free by construction: the writer and its Android-side owner
 * accept numbers, booleans and files only, so no typed text, word or suggestion can pass through
 * them. Pinned in code because the file leaves the device through adb, outside every in-app gate.
 */
class LabSessionLogContractTest {

    @Test
    fun writerPublicApiTakesNoText() {
        assertNoTextParameters(LabSessionLogWriter::class.java)
    }

    @Test
    fun singletonPublicApiTakesNoText() {
        assertNoTextParameters(LabSessionLog::class.java)
    }

    private fun assertNoTextParameters(type: Class<*>) {
        val members = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) } +
            type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }
        assertTrue("the API under test must exist", members.isNotEmpty())
        for (member in members) {
            for (parameter in member.parameterTypes) {
                assertFalse(
                    "$member takes text ($parameter)",
                    parameter == String::class.java
                        || parameter == CharArray::class.java
                        || parameter == Character.TYPE
                        || parameter == java.lang.Character::class.java
                        || CharSequence::class.java.isAssignableFrom(parameter),
                )
            }
        }
    }
}

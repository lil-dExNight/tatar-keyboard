package rkr.simplekeyboard.inputmethod.latin.dictionary.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreValidationTokenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun matchesOnlyTheIssuedContent() {
        val token = StoreValidationToken.issue("a".repeat(64), 100, 1_000)

        assertTrue(StoreValidationToken.matches(token, "a".repeat(64), 100))
        assertFalse(StoreValidationToken.matches(token, "b".repeat(64), 100))
        assertFalse(StoreValidationToken.matches(token, "a".repeat(64), 101))
        assertFalse(StoreValidationToken.matches(null, "a".repeat(64), 100))
    }

    @Test
    fun twoTokensForTheSameContentBothMatch() {
        val first = StoreValidationToken.issue("a".repeat(64), 100, 1_000)
        val second = StoreValidationToken.issue("a".repeat(64), 100, 2_000)

        assertTrue(StoreValidationToken.matches(first, "a".repeat(64), 100))
        assertTrue(StoreValidationToken.matches(second, "a".repeat(64), 100))
    }

    @Test
    fun matchesFileFollowsLengthAndModificationTime() {
        val file = temporaryFolder.newFile("file.bin")
        file.writeBytes(ByteArray(100) { it.toByte() })
        val token = StoreValidationToken.issue("a".repeat(64), 100, file.lastModified())
        assertTrue(token.matchesFile(file))

        assertTrue(file.setLastModified(file.lastModified() + 2_000))
        assertFalse(token.matchesFile(file))
    }

    @Test
    fun matchesFileRejectsALengthChange() {
        val file = temporaryFolder.newFile("grown.bin")
        file.writeBytes(ByteArray(100))
        val token = StoreValidationToken.issue("a".repeat(64), 100, file.lastModified())

        file.writeBytes(ByteArray(101))

        assertFalse(token.matchesFile(file))
    }

    @Test
    fun matchesFileRejectsAZeroRecordedTime() {
        val file = temporaryFolder.newFile("zero.bin")
        file.writeBytes(ByteArray(100))

        assertFalse(StoreValidationToken.issue("a".repeat(64), 100, 0).matchesFile(file))
    }
}

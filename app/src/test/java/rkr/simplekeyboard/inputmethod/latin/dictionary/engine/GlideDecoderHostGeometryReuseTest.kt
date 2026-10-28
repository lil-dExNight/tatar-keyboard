package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidate
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.SnapshotPersonalCandidateSource
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalOutputOpener
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlideTestFixtures
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordIndex
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordInventory
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordVisitor
import rkr.simplekeyboard.inputmethod.latin.glide.ListGlideInventory
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executor

/**
 * The glide word index is built once per geometry instance, so keeping the instance while the
 * layout is unchanged (the rule in `LatinIME.updateKeyNeighbors`) saves a full index build per
 * keyboard id change. Also prints the build time of the index on the real Tatar dictionary.
 */
class GlideDecoderHostGeometryReuseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class CountingInventory(private val inner: GlideWordInventory) : GlideWordInventory {
        var walks = 0

        override val entryCount: Int get() = inner.entryCount

        override fun wordAt(index: Int): String = inner.wordAt(index)

        override fun forEachWord(visitor: GlideWordVisitor) {
            walks++
            inner.forEachWord(visitor)
        }
    }

    /** Shifted, unshifted and search-action keyboards of one layout: three separate builds. */
    private fun sameLayoutBuilds(): List<GlideKeyGeometry> {
        val keys = GlideTestFixtures.tatarRawKeys()
        val shifted = keys.map { key ->
            GlideKeyGeometry.RawKey(
                Character.toUpperCase(key.codePoint), key.left, key.top, key.right, key.bottom,
                IntArray(key.moreKeyCodePoints.size) { Character.toUpperCase(key.moreKeyCodePoints[it]) },
            )
        }
        return listOf(
            GlideKeyGeometry.build(shifted),
            GlideKeyGeometry.build(keys),
            GlideKeyGeometry.build(keys),
        )
    }

    private fun decodeSequence(keepSameLayout: Boolean): Pair<Int, List<List<String>>> {
        val inventory = CountingInventory(
            ListGlideInventory(listOf("сәләм" to 36L, "китап" to 200L, "алма" to 60L)),
        )
        val host = GlideDecoderHost(inventory)
        val path = requireNotNull(GlideTestFixtures.idealPath("китап", GlideTestFixtures.tatarGeometry()))
        var kept: GlideKeyGeometry? = null
        val results = ArrayList<List<String>>()
        for (rebuilt in sameLayoutBuilds()) {
            val previous = kept
            kept = if (keepSameLayout && previous != null && previous.sameLayoutAs(rebuilt)) {
                previous
            } else {
                rebuilt
            }
            host.updateGlideGeometry(kept)
            results += host.decodeGlide(path, null)
        }
        return inventory.walks to results
    }

    @Test
    fun keepingTheGeometryBuildsTheWordIndexOnce() {
        val (kept, keptResults) = decodeSequence(keepSameLayout = true)
        val (rebuilt, rebuiltResults) = decodeSequence(keepSameLayout = false)

        println("GLIDE_INDEX_BUILDS|kept=$kept|new_instance_each_time=$rebuilt")
        assertEquals(1, kept)
        assertEquals(3, rebuilt)
        assertEquals(rebuiltResults, keptResults)
        assertEquals("китап", keptResults.first().first())
    }

    @Test
    fun printsTheWordIndexBuildTimeOnTheTatarDictionary() {
        val real = openTatarIndex()
        val inventory = TdictGlideInventory(real.index)
        val geometry = GlideTestFixtures.tatarGeometry()
        GlideWordIndex.build(inventory, geometry)
        val nanos = LongArray(5) {
            val start = System.nanoTime()
            GlideWordIndex.build(inventory, geometry)
            System.nanoTime() - start
        }
        nanos.sort()
        val medianMillis = nanos[2] / 1_000_000.0
        println("GLIDE_INDEX_BUILD|tt|entries=${real.entryCount}|median_ms=$medianMillis")
        assertTrue(medianMillis > 0.0)
    }

    /**
     * The rebuild key is the snapshot's structure version, not its identity: a republished
     * snapshot with the same word set (a counter bump) keeps the index; a version bump rebuilds
     * it, even when the words themselves are unchanged.
     */
    @Test
    fun theRebuildKeysOnTheStructureVersionNotTheSnapshotIdentity() {
        val inventory = CountingInventory(
            ListGlideInventory(listOf("сәләм" to 36L, "китап" to 200L)),
        )
        val source = MutablePersonalSource()
        source.snapshot = GlideTestFixtures.personalDictionary("сәлинә" to 1).withStructureVersion(1)
        val host = GlideDecoderHost(inventory, source)
        val geometry = GlideTestFixtures.tatarGeometry()
        host.updateGlideGeometry(geometry)
        val path = requireNotNull(GlideTestFixtures.idealPath("сәлинә", geometry))

        val first = host.decodeGlide(path, null)
        assertEquals("сәлинә", first.first())
        assertEquals(1, inventory.walks)

        // A counter bump republishes a new instance with the SAME version: no rebuild.
        source.snapshot = GlideTestFixtures.personalDictionary("сәлинә" to 2).withStructureVersion(1)
        assertEquals(first, host.decodeGlide(path, null))
        assertEquals(1, inventory.walks)

        // A word-set change bumps the version: the next decode rebuilds over it.
        source.snapshot = GlideTestFixtures.personalDictionary("сәлинә" to 1).withStructureVersion(2)
        assertEquals(first, host.decodeGlide(path, null))
        assertEquals(2, inventory.walks)

        // Forgetting the word is another bump; the same gesture decodes empty.
        source.snapshot = PersonalDictionary.EMPTY.withStructureVersion(3)
        assertTrue(host.decodeGlide(path, null).isEmpty())
        assertEquals(3, inventory.walks)
    }

    /**
     * The churn bench: alternating accept-learned-word / glide cycles over the real Tatar
     * dictionary with a real personal store. An accepted suggestion republishes the snapshot with
     * the same word set, so the word index must not rebuild; the budget is rebuilds per cycle
     * <= 0.02, validated at integration. Every cycle's decode must be identical — the debounce
     * changes the rebuild cadence, never the output.
     */
    @Test
    fun acceptedSuggestionsDoNotChurnTheWordIndex() {
        val real = openTatarIndex()
        assertFalse(
            "the learned word must be personal-only, or the bench measures nothing",
            real.index.containsWordCold("сәлинә"),
        )
        val inventory = CountingInventory(TdictGlideInventory(real.index))
        val directory = File(temporaryFolder.newFolder(), "personal").also { assertTrue(it.mkdirs()) }
        val store = PersonalDictionaryStore(
            subtypeId = PersonalSubtypes.TATAR_RU,
            directoryProvider = { directory },
            fileOps = PassthroughOps,
            outputOpener = PersonalOutputOpener { FileOutputStream(it) },
            spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L },
            executor = Executor { it.run() },
        )
        store.addManually("сәлинә")
        val host = GlideDecoderHost(
            inventory, SnapshotPersonalCandidateSource { store.snapshot }, real.index::containsWordCold,
        )
        val geometry = GlideTestFixtures.tatarGeometry()
        host.updateGlideGeometry(geometry)
        val path = requireNotNull(GlideTestFixtures.idealPath("сәләм", geometry))

        val cycles = 50
        var reference: List<String>? = null
        repeat(cycles) {
            store.noteAcceptedSuggestion("сәлинә")
            val decoded = host.decodeGlide(path, null)
            if (reference == null) reference = decoded
            assertEquals("the debounce changes the rebuild cadence, never the output", reference, decoded)
        }
        assertEquals("сәләм", reference!!.first())

        // The first decode builds the index once; everything past that is a rebuild.
        val rebuilds = inventory.walks - 1
        val perCycle = rebuilds / cycles.toDouble()
        println("GLIDE_CHURN|tt|cycles=$cycles|rebuilds=$rebuilds|digest=${digest(reference!!)}")
        assertTrue("rebuilds per cycle $perCycle exceed the budget", perCycle <= 0.02)
    }

    /** The bundled Tatar dictionary, inflated and validated, held in memory. */
    private class RealTatarIndex(val index: TdictPrefixIndex, val entryCount: Int)

    /** Opens the real bundled Tatar dictionary in memory; the inflated temp file is deleted. */
    private fun openTatarIndex(): RealTatarIndex {
        val spec = DictionaryArtifactSpec.TATAR_TOP100K_V1
        val asset = listOf(File("src/main/assets/${spec.assetPath}"), File("app/src/main/assets/${spec.assetPath}"))
            .first(File::isFile)
        val rawFile = File.createTempFile("glide-index-", ".tdict")
        try {
            rawFile.outputStream().use { TdictValidator().inflateAsset(asset.inputStream(), it, spec) }
            val validated = TdictValidator().validateRaw(rawFile, spec)
            val identity = DictionaryIdentity(
                spec.generation, validated.schemaId, validated.formatVersion, validated.rawSha256,
            )
            return RealTatarIndex(
                requireNotNull(
                    TdictPrefixIndex.open(
                        ByteBuffer.wrap(rawFile.readBytes()), identity, validated.entryCount, validated.rawSize,
                        autocorrectMinCandidateFrequency = AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                    ),
                ),
                validated.entryCount.toInt(),
            )
        } finally {
            rawFile.delete()
        }
    }

    /** A personal source with a caller-swappable snapshot, like the store's `@Volatile` republish. */
    private class MutablePersonalSource : PersonalCandidateSource {
        @Volatile
        var snapshot: PersonalDictionary = PersonalDictionary.EMPTY

        override fun candidatesFor(normalizedPrefix: String): List<PersonalCandidate> =
            snapshot.lookupCandidates(normalizedPrefix)

        override fun glideSnapshot(): PersonalDictionary = snapshot
    }

    /** Plain durable ops over the real filesystem; the churn bench injects no faults. */
    private object PassthroughOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()
        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()
        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }
        override fun syncDirectory(directory: File) {}
        override fun delete(file: File): Boolean = file.delete()
    }

    private fun digest(words: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (word in words) md.update(word.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}

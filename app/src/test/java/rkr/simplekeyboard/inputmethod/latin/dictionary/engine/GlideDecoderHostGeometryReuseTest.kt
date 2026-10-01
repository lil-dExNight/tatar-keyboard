package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.glide.GlideKeyGeometry
import rkr.simplekeyboard.inputmethod.latin.glide.GlideTestFixtures
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordIndex
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordInventory
import rkr.simplekeyboard.inputmethod.latin.glide.GlideWordVisitor
import rkr.simplekeyboard.inputmethod.latin.glide.ListGlideInventory
import java.io.File
import java.nio.ByteBuffer

/**
 * The glide word index is built once per geometry instance, so keeping the instance while the
 * layout is unchanged (the rule in `LatinIME.updateKeyNeighbors`) saves a full index build per
 * keyboard id change. Also prints the build time of the index on the real Tatar dictionary.
 */
class GlideDecoderHostGeometryReuseTest {
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
            results += host.decodeGlide(path)
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
            val index = requireNotNull(
                TdictPrefixIndex.open(
                    ByteBuffer.wrap(rawFile.readBytes()), identity, validated.entryCount, validated.rawSize,
                ),
            )
            val inventory = TdictGlideInventory(index)
            val geometry = GlideTestFixtures.tatarGeometry()
            GlideWordIndex.build(inventory, geometry)
            val nanos = LongArray(5) {
                val start = System.nanoTime()
                GlideWordIndex.build(inventory, geometry)
                System.nanoTime() - start
            }
            nanos.sort()
            val medianMillis = nanos[2] / 1_000_000.0
            println("GLIDE_INDEX_BUILD|tt|entries=${validated.entryCount}|median_ms=$medianMillis")
            assertTrue(medianMillis > 0.0)
        } finally {
            rawFile.delete()
        }
    }
}

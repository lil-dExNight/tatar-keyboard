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

package rkr.simplekeyboard.inputmethod.latin.spellcheck

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.AutocorrectPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.DictionaryIdentity
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.FuzzyEditPolicy
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.ImmutableUtf8Prefix
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.InflectedSuffixTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.KeyNeighborTable
import rkr.simplekeyboard.inputmethod.latin.dictionary.engine.TdictPrefixIndex
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryArtifactSpec
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TdictValidator
import rkr.simplekeyboard.inputmethod.latin.morphology.TatarMorphologicalAcceptor
import rkr.simplekeyboard.inputmethod.latin.suggestions.ProperNameIndex
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarSuffixRules
import rkr.simplekeyboard.inputmethod.latin.suggestions.TatarWordUtils

/** Supplies immutable personal-dictionary snapshots; it has no mutation surface. */
internal fun interface PersonalDictionarySource {
    fun snapshot(subtypeId: String): PersonalDictionary
}

/**
 * The validated bundled dictionaries and their read-only adjuncts. The two index monitors cover
 * lookup scratch as well as membership; the acceptor synchronizes its own fixed scratch.
 */
internal class SpellCheckerDictionaries(
    private val tatar: TdictPrefixIndex,
    private val russian: TdictPrefixIndex,
    private val acceptor: () -> TatarMorphologicalAcceptor?,
    private val properNames: ProperNameIndex,
    private val personal: PersonalDictionarySource,
) : SpellCheckerWordSource {
    private val tatarMonitor = Any()
    private val russianMonitor = Any()

    override fun tatarBaseWord(normalizedWord: String): Boolean =
        synchronized(tatarMonitor) { tatar.containsWordCold(normalizedWord) } ||
            acceptor()?.accepts(normalizedWord) == true ||
            properNames.displayFor(normalizedWord) != null

    override fun russianBaseWord(normalizedWord: String): Boolean =
        synchronized(russianMonitor) { russian.containsWordCold(normalizedWord) }

    override fun personalWord(subtypeId: String, normalizedWord: String): Boolean =
        personal.snapshot(subtypeId).indexOfNormalized(normalizedWord) >= 0

    override fun typoCandidates(normalizedWord: String): SpellCheckerTypoCandidates =
        synchronized(tatarMonitor) {
            val words = tatar.lookup(
                ImmutableUtf8Prefix.copyOf(TatarWordUtils.toLookupBytes(normalizedWord)),
            )
            SpellCheckerTypoCandidates(words, tatar.lastExactCount)
        }
}

/** An opener for one read-only APK asset. */
internal fun interface SpellCheckerAssetOpener {
    fun open(path: String): InputStream
}

/** Loads the pinned Tatar and Russian dictionaries from APK assets without a storage catalog. */
internal class SpellCheckerDictionaryLoader(
    private val opener: SpellCheckerAssetOpener,
    private val acceptor: () -> TatarMorphologicalAcceptor?,
    private val personal: PersonalDictionarySource,
    private val neighbors: KeyNeighborTable,
) {
    fun load(): SpellCheckerDictionaries? = try {
        val tatar = openIndex(
            DictionaryArtifactSpec.TATAR_TOP100K_V1,
            TatarSuffixRules,
            FuzzyEditPolicy.TATAR,
            AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
        ).also { it.updateKeyNeighbors(neighbors) }
        val russian = openIndex(
            DictionaryArtifactSpec.RUSSIAN_TOP100K_V1,
            null,
            FuzzyEditPolicy.RUSSIAN,
            AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_RU,
        )
        SpellCheckerDictionaries(
            requireNotNull(tatar),
            requireNotNull(russian),
            acceptor,
            loadProperNames(),
            personal,
        )
    } catch (_: Exception) {
        null
    }

    private fun openIndex(
        spec: DictionaryArtifactSpec,
        suffixTable: InflectedSuffixTable?,
        policy: FuzzyEditPolicy,
        autocorrectFloor: Long,
    ): TdictPrefixIndex {
        val output = ByteArrayOutputStream()
        opener.open(spec.assetPath).use { source ->
            TdictValidator().inflateAsset(source, output, spec)
        }
        val identity = DictionaryIdentity(
            spec.generation,
            spec.schemaId,
            spec.formatVersion,
            spec.expectedRawSha256,
        )
        val index = requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(output.toByteArray()).asReadOnlyBuffer(),
                identity,
                spec.expectedEntryCount,
                spec.expectedRawSize,
                suffixTable,
                policy,
                autocorrectFloor,
            ),
        )
        index.setAutocorrectAdviceEnabled(false)
        return index
    }

    private fun loadProperNames(): ProperNameIndex = try {
        val path = DictionaryArtifactSpec.TATAR_TOP100K_V1.properNameAssetPath
        if (path == null) ProperNameIndex.EMPTY
        else opener.open(path).use { ProperNameIndex.parse(it) }
    } catch (_: Exception) {
        ProperNameIndex.EMPTY
    }
}

/** Reads one personal file into an immutable snapshot. */
internal fun interface PersonalSnapshotReader {
    fun read(file: File, subtypeId: String): PersonalDictionary
}

/** Reads the active personal files after unlock, with one immutable cached snapshot per language. */
internal class FilePersonalDictionarySource(
    private val unlocked: () -> Boolean,
    private val directory: () -> File,
    private val reader: PersonalSnapshotReader = PersonalSnapshotReader { file, subtypeId ->
        try {
            PersonalDictionary.of(TpersValidator().validate(file, subtypeId))
        } catch (_: Exception) {
            PersonalDictionary.EMPTY
        }
    },
) : PersonalDictionarySource {
    private val lock = Any()
    private val cached = HashMap<String, CachedSnapshot>()

    override fun snapshot(subtypeId: String): PersonalDictionary {
        val isUnlocked = try {
            unlocked()
        } catch (_: Exception) {
            false
        }
        if (!isUnlocked) return PersonalDictionary.EMPTY
        val file = try {
            File(directory(), TpersFormat.personalFileName(subtypeId))
        } catch (_: Exception) {
            return PersonalDictionary.EMPTY
        }
        synchronized(lock) {
            if (!file.isFile) {
                cached.remove(subtypeId)
                return PersonalDictionary.EMPTY
            }
            val length = file.length()
            val modified = file.lastModified()
            cached[subtypeId]?.let {
                if (it.path == file.path && it.length == length && it.modified == modified) {
                    return it.dictionary
                }
            }
            val snapshot = try {
                reader.read(file, subtypeId)
            } catch (_: Exception) {
                PersonalDictionary.EMPTY
            }
            cached[subtypeId] = CachedSnapshot(file.path, length, modified, snapshot)
            return snapshot
        }
    }

    private class CachedSnapshot(
        val path: String,
        val length: Long,
        val modified: Long,
        val dictionary: PersonalDictionary,
    )
}

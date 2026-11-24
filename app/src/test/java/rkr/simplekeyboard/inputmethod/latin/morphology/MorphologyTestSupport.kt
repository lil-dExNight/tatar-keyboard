package rkr.simplekeyboard.inputmethod.latin.morphology

import java.io.File
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StemLexiconTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.StemTestEntry
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatStemFormat
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.TatStemValidator

internal object MorphologyTestSupport {
    fun locate(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.isFile) return direct
        val fromRepository = File("app", relativePath)
        if (fromRepository.isFile) return fromRepository
        return File("..", relativePath)
    }

    fun realAcceptor(): TatarMorphologicalAcceptor {
        val file = locate("src/main/assets/dictionaries/tatar_stems_v1.tstem.zlib")
        check(file.isFile) { "Missing stem asset: $file" }
        return file.inputStream().use { TatarMorphologicalAcceptor.open(it) }
    }

    fun entry(
        word: String,
        noun: Boolean = true,
        verb: Boolean = true,
        nounChain: Boolean = noun,
        verbChain: Boolean = verb,
        forceBack: Boolean = false,
    ): StemTestEntry {
        var flags = (if (noun) TatStemFormat.FLAG_NOUN else 0) or
            (if (verb) TatStemFormat.FLAG_VERB else 0) or
            (if (nounChain) TatStemFormat.FLAG_NOUN_CHAIN else 0) or
            (if (verbChain) TatStemFormat.FLAG_VERB_CHAIN else 0)
        val back = forceBack || naturalBack(word)
        flags = flags or if (back) TatStemFormat.FLAG_BACK else TatStemFormat.FLAG_FRONT
        if (forceBack) flags = flags or TatStemFormat.FLAG_FORCE_BACK
        return StemTestEntry(word, flags)
    }

    fun acceptor(
        entries: List<StemTestEntry>,
        exceptions: Array<LexicalException> = TatarWordformExceptions.STEMS,
    ): TatarMorphologicalAcceptor {
        val artifact = StemLexiconTestFixtures.artifact(entries.sortedBy { it.word })
        val validated = TatStemValidator().inflateAsset(artifact.compressed.inputStream(), artifact.spec)
        return TatarMorphologicalAcceptor(validated, artifact.spec, exceptions)
    }

    private fun naturalBack(word: String): Boolean {
        if (word.endsWith('ь')) return false
        val vowels = "аыоуюяәеиөүэ"
        for (index in word.lastIndex downTo 0) {
            val char = word[index]
            if (char in "уүюя" && index > 0 && word[index - 1] in vowels) continue
            if (char in "аыоуюя") return true
            if (char in "әеиөүэ") return false
        }
        error("Fixture has no harmony vowel: $word")
    }
}

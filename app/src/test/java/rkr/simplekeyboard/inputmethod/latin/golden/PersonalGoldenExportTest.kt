package rkr.simplekeyboard.inputmethod.latin.golden

import org.junit.Assume.assumeTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalBigramDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalDictionary
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.PersonalSubtypes
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.personal.TpersbValidator
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PendingCounters
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramEntries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramQuarantineSalvage
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalBigramWordFilter
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalDictionaryStore
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalEntries
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalOutputOpener
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalQuarantineSalvage
import rkr.simplekeyboard.inputmethod.latin.dictionary.personalstore.PersonalWordFilter
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DurableFileOps
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.SpaceProbe
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executor

/**
 * Personal-dictionary golden-vector exporter (`personal.jsonl`). Inert unless PERSONAL_GOLDEN_OUT
 * names an existing directory; its output feeds the iOS port's parity suite (exact byte
 * equality). Nothing here reaches the APK.
 *
 *   PERSONAL_GOLDEN_OUT=/path/to/dir ./gradlew :app:testDebugUnitTest --tests '*PersonalGoldenExportTest*'
 *
 * Deterministic scenarios over the real production classes (PersonalEntries /
 * PersonalBigramEntries / PendingCounters serialization, the two validators, the two quarantine
 * salvage readers, the snapshot lookups, the word filters, and two end-to-end store runs whose
 * on-disk files are dumped after every event). The words are fixed test words, never user text.
 */
class PersonalGoldenExportTest {

    @Test
    fun export() {
        val outName = System.getenv("PERSONAL_GOLDEN_OUT")
        assumeTrue("PERSONAL_GOLDEN_OUT not set: exporter skipped", !outName.isNullOrEmpty())
        val out = File(outName!!)
        require(out.isDirectory) { "PERSONAL_GOLDEN_OUT must be an existing directory" }
        File(out, "personal.jsonl").bufferedWriter(Charsets.UTF_8).use { w ->
            exportSha(w)
            exportFilters(w)
            val wordsImage = exportWordEntries(w)
            val pairsImage = exportPairEntries(w)
            exportPending(w)
            exportValidation(w, "tpers", PersonalSubtypes.TATAR_RU, wordsImage)
            exportValidation(w, "tpersb", PersonalSubtypes.TATAR_RU, pairsImage)
            exportCrafted(w)
            exportLookups(w, wordsImage, pairsImage)
            exportWordStore(w)
            exportPairStore(w)
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun exportSha(w: Writer) {
        val inputs = listOf(ByteArray(0), "abc".toByteArray(), ByteArray(55) { it.toByte() },
            ByteArray(56) { it.toByte() }, ByteArray(64) { it.toByte() }, ByteArray(1000) { (it * 7).toByte() })
        for (input in inputs) {
            w.line("""{"kind":"sha","input":${json(hex(input))},"digest":${json(hex(MessageDigest.getInstance("SHA-256").digest(input)))}}""")
        }
    }

    private val filterWords = listOf(
        "абыйлар", "Гүзәл", "ГҮЗӘЛ", "гҮзӘл", "ab", "аб", "а", "абв", "Казан2", "Казан-",
        "мәктәпләребездәгеләрнеңмы", "мәктәпләребездәгеләрнеңм", "ёлка", "Ёлка", "ещё",
        "у\u0308ка", "а\u0301бв", "Hello", "сүз ", "", "Щ", "һәм", "ҺӘМ", "киләчәктә",
    )

    private fun exportFilters(w: Writer) {
        for (subtype in listOf(PersonalSubtypes.TATAR_RU, PersonalSubtypes.RUSSIAN)) {
            val alphabet = PersonalSubtypes.alphabetFor(subtype)!!
            for (word in filterWords) {
                val words = PersonalWordFilter.acceptedNormalizedForm(word, alphabet)
                val pairs = PersonalBigramWordFilter.acceptedNormalizedForm(word, alphabet)
                w.line("""{"kind":"filter","subtype":${json(subtype)},"word":${json(word)},"words":${jsonOrNull(words)},"pairs":${jsonOrNull(pairs)}}""")
            }
        }
    }

    /** Returns the final image of the main scenario (the base of the corruption sweep). */
    private fun exportWordEntries(w: Writer): ByteArray {
        val subtype = PersonalSubtypes.TATAR_RU
        val ops = listOf(
            listOf("upsert", "абыйлар"), listOf("upsert", "Гүзәл"), listOf("upsert", "сүзлек"),
            listOf("upsert", "гүзәл"), listOf("use", "сүзлек"), listOf("upsert", "китап"),
            listOf("upsert", "Мәктәп"), listOf("remove", "китап"), listOf("remove", "юк"),
            listOf("use", "юк"), listOf("upsert", "әнкәй"), listOf("use", "абыйлар"),
        )
        var entries = PersonalEntries.empty(4)
        val steps = ArrayList<String>()
        for (op in ops) {
            entries = applyWordOp(entries, op)
            steps.add(hex(entries.serialize(subtype)))
        }
        w.line("""{"kind":"entries","subtype":${json(subtype)},"max":4,"ops":${jsonOps(ops)},"steps":${jsonList(steps)}}""")

        // Saturation of the u16 usage counter (65 540 upserts of one word).
        var saturated = PersonalEntries.empty()
        repeat(65_540) { saturated = saturated.upsert("уку", "уку") }
        w.line("""{"kind":"entriesSaturation","subtype":${json(subtype)},"repeat":65540,"word":"уку","bytes":${json(hex(saturated.serialize(subtype)))}}""")

        // Russian: its own tag and alphabet.
        var ru = PersonalEntries.empty()
        val ruOps = listOf(listOf("upsert", "Москва"), listOf("upsert", "ещё"), listOf("upsert", "привет"), listOf("use", "ещё"))
        val ruSteps = ArrayList<String>()
        for (op in ruOps) {
            ru = applyWordOp(ru, op)
            ruSteps.add(hex(ru.serialize(PersonalSubtypes.RUSSIAN)))
        }
        w.line("""{"kind":"entries","subtype":"ru","max":2000,"ops":${jsonOps(ruOps)},"steps":${jsonList(ruSteps)}}""")
        return entries.serialize(subtype)
    }

    private fun applyWordOp(entries: PersonalEntries, op: List<String>): PersonalEntries = when (op[0]) {
        "upsert" -> entries.upsert(op[1], PersonalWordFilter.normalize(op[1]))
        "use" -> entries.noteUse(op[1]) ?: entries
        "remove" -> entries.remove(op[1])
        else -> error("op")
    }

    private fun exportPairEntries(w: Writer): ByteArray {
        val subtype = PersonalSubtypes.TATAR_RU
        val ops = listOf(
            listOf("upsert", "мин", "Гүзәл", "2"), listOf("upsert", "мин", "китап", "2"),
            listOf("upsert", "аб", "вг", "2"), listOf("upsert", "абв", "г", "2"),
            listOf("observe", "мин", "китап"), listOf("use", "мин", "гүзәл"),
            listOf("upsert", "мин", "гүзәл", "2"), listOf("use", "юк", "юк"), listOf("observe", "юк", "юк"),
            listOf("remove", "аб", "вг"), listOf("remove", "юк", "юк"), listOf("upsert", "һәм", "Казан", "65540"),
            listOf("upsert", "без", "анда", "2"),
        )
        var entries = PersonalBigramEntries.empty(4)
        val steps = ArrayList<String>()
        for (op in ops) {
            entries = when (op[0]) {
                "upsert" -> entries.upsert(op[1], op[2], PersonalBigramWordFilter.normalize(op[2]), op[3].toInt())
                "observe" -> entries.noteObservation(op[1], op[2]) ?: entries
                "use" -> entries.noteUse(op[1], op[2]) ?: entries
                "remove" -> entries.remove(op[1], op[2])
                else -> error("op")
            }
            steps.add(hex(entries.serialize(subtype)))
        }
        w.line("""{"kind":"pairEntries","subtype":${json(subtype)},"max":4,"ops":${jsonOps(ops)},"steps":${jsonList(steps)}}""")
        return entries.serialize(subtype)
    }

    private fun exportPending(w: Writer) {
        val salt = ByteArray(16) { it.toByte() }
        for (word in listOf("абыйлар", "гүзәл", "а", "")) {
            w.line("""{"kind":"key","salt":${json(hex(salt))},"word":${json(word)},"key":"${PendingCounters.keyOf(salt, word)}"}""")
        }
        for ((c, s) in listOf("аб" to "вг", "абв" to "г", "мин" to "гүзәл")) {
            w.line("""{"kind":"pairKey","salt":${json(hex(salt))},"context":${json(c)},"successor":${json(s)},"key":"${PendingCounters.keyOfPair(salt, c, s)}"}""")
        }
        val ops = listOf(
            listOf("note", "абыйлар"), listOf("note", "абыйлар"), listOf("note", "гүзәл"),
            listOf("without", "абыйлар"), listOf("without", "юк"), listOf("prune"), listOf("note", "гүзәл"),
        )
        var counters = PendingCounters.EMPTY
        val steps = ArrayList<String>()
        for (op in ops) {
            counters = when (op[0]) {
                "note" -> counters.note(PendingCounters.keyOf(salt, op[1]))
                "without" -> counters.without(PendingCounters.keyOf(salt, op[1]))
                "prune" -> counters.prunedForFlush()
                else -> error("op")
            }
            steps.add(hex(counters.serialize()))
        }
        w.line("""{"kind":"pending","salt":${json(hex(salt))},"ops":${jsonOps(ops)},"steps":${jsonList(steps)}}""")

        // Cap: 260 distinct keys → the 4 oldest are evicted.
        var capped = PendingCounters.EMPTY
        for (i in 0 until 260) capped = capped.note(PendingCounters.keyOf(salt, "w$i"))
        w.line("""{"kind":"pendingCap","salt":${json(hex(salt))},"count":260,"bytes":${json(hex(capped.serialize()))}}""")

        // Lifetime: one old entry, then 510 notes of another → prune drops the old one.
        var aged = PendingCounters.EMPTY.note(PendingCounters.keyOf(salt, "old"))
        repeat(510) { aged = aged.note(PendingCounters.keyOf(salt, "x")) }
        w.line("""{"kind":"pendingLifetime","salt":${json(hex(salt))},"repeat":510,"before":${json(hex(aged.serialize()))},"after":${json(hex(aged.prunedForFlush().serialize()))}}""")

        // Parse: a round trip and a corruption sweep of the round-tripped image.
        val image = counters.serialize()
        val variants = ArrayList<ByteArray>()
        variants.add(image)
        for (i in image.indices) variants.add(image.copyOf().also { it[i] = (it[i].toInt() xor 0x5a).toByte() })
        for (n in 0 until image.size) variants.add(image.copyOf(n))
        for (v in variants) {
            w.line("""{"kind":"pendingParse","bytes":${json(hex(v))},"parsed":${json(hex(PendingCounters.parse(v).serialize()))}}""")
        }
    }

    private fun exportValidation(w: Writer, format: String, subtype: String, image: ByteArray) {
        val variants = LinkedHashSet<String>()
        variants.add(hex(image))
        for (i in image.indices) variants.add(hex(image.copyOf().also { it[i] = (it[i].toInt() xor 0x5a).toByte() }))
        for (n in 0 until image.size) variants.add(hex(image.copyOf(n)))
        // A resealed flip keeps the checksum valid, so the record checks are reached too.
        for (i in 72 until image.size) {
            val flipped = image.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            variants.add(hex(reseal(flipped)))
        }
        variants.add(hex(image + byteArrayOf(0)))
        for (v in variants) emitVerdicts(w, format, subtype, unhex(v))
        // The other subtype asked of the same bytes.
        emitVerdicts(w, format, PersonalSubtypes.RUSSIAN, image)
        emitVerdicts(w, format, "en_US", image)
    }

    private fun emitVerdicts(w: Writer, format: String, subtype: String, bytes: ByteArray) {
        val file = Files.createTempFile("golden", ".bin").toFile()
        try {
            file.writeBytes(bytes)
            val verdict = if (format == "tpers") {
                try {
                    val v = TpersValidator().validate(file, subtype)
                    val rows = (0 until v.entryCount).joinToString(",") {
                        "[${json(v.rawForms[it])},${json(v.normalizedForms[it])},${v.usageCounts[it]},${v.lastUseSerials[it]}]"
                    }
                    """"ok":true,"rows":[$rows]"""
                } catch (e: Exception) {
                    """"ok":false,"error":${json(e.message ?: "")}"""
                }
            } else {
                try {
                    val v = TpersbValidator().validate(file, subtype)
                    val rows = (0 until v.pairCount).joinToString(",") {
                        "[${json(v.contexts[it])},${json(v.successorRawForms[it])},${json(v.successorNormalizedForms[it])},${v.usageCounts[it]},${v.frequencyCounts[it]},${v.lastUseSerials[it]}]"
                    }
                    """"ok":true,"rows":[$rows]"""
                } catch (e: Exception) {
                    """"ok":false,"error":${json(e.message ?: "")}"""
                }
            }
            val salvage = if (format == "tpers") {
                val s = PersonalQuarantineSalvage.read(file, subtype)
                if (s == null) "null" else """{"count":${s.wordCount},"readToEnd":${s.readToEnd},"raws":${jsonList(s.rawForms)},"norms":${jsonList(s.normalizedForms)}}"""
            } else {
                val s = PersonalBigramQuarantineSalvage.read(file, subtype)
                if (s == null) "null" else """{"count":${s.pairCount},"readToEnd":${s.readToEnd},"contexts":${jsonList(s.contexts)},"raws":${jsonList(s.successorRawForms)},"norms":${jsonList(s.successorNormalizedForms)}}"""
            }
            w.line("""{"kind":"validate","format":${json(format)},"subtype":${json(subtype)},"bytes":${json(hex(bytes))},$verdict,"salvage":$salvage}""")
        } finally {
            file.delete()
        }
    }

    /** Hand-built images reaching every per-record rule (sealed with a correct checksum). */
    private fun exportCrafted(w: Writer) {
        val tt = PersonalSubtypes.TATAR_RU
        fun words(vararg raw: Any): List<Triple<ByteArray, Int, Long>> = raw.map {
            when (it) {
                is String -> Triple(it.toByteArray(StandardCharsets.UTF_8), 1, 1L)
                is ByteArray -> Triple(it, 1, 1L)
                else -> error("x")
            }
        }
        val crafted = listOf(
            tpers(words("бв", "аб"), tt), tpers(words("абв", "абв"), tt), tpers(words("Абв", "абв"), tt),
            tpers(words("ГүЗәл"), tt), tpers(words("ab c"), tt), tpers(words("аб"), tt),
            tpers(words("а".repeat(25)), tt), tpers(words("а".repeat(24)), tt),
            tpers(words("а\u0301бв"), tt), tpers(words(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0x61)), tt),
            tpers(listOf(Triple("абв".toByteArray(), 0, 1L)), tt), tpers(listOf(Triple("абв".toByteArray(), 65535, 4294967295L)), tt),
            tpers(words("абв"), "ru"), tpers(words("абв"), "tt_RU\u0001"), tpers(words(), tt),
            tpers(words("ГҮЗӘЛ", "Гүзәл"), tt), tpers(words("у\u0308ка"), tt),
            tpers(words("абв"), tt, countOverride = 2001), tpers(words("абв", "где"), tt, countOverride = 1),
            tpers(words("абв", "где"), tt, countOverride = 3),
            tpers(words(ByteArray(0)), tt),
        )
        for (image in crafted) emitVerdicts(w, "tpers", tt, image)
        fun pairs(vararg p: Pair<String, String>) = p.map { Triple(it.first.toByteArray(), it.second.toByteArray(), intArrayOf(0, 1)) }
        val craftedPairs = listOf(
            tpersb(pairs("а" to "б"), tt), tpersb(pairs("аб" to "вг", "абв" to "г"), tt),
            tpersb(pairs("абв" to "г", "аб" to "вг"), tt), tpersb(pairs("аб" to "вг", "аб" to "вг"), tt),
            tpersb(pairs("Мин" to "китап"), tt), tpersb(pairs("мин" to "КиТап"), tt), tpersb(pairs("мин" to "КИТАП"), tt),
            tpersb(pairs("мин" to "ab"), tt), tpersb(pairs("мин" to "а".repeat(25)), tt),
            tpersb(listOf(Triple("мин".toByteArray(), "китап".toByteArray(), intArrayOf(0, 0))), tt),
            tpersb(listOf(Triple("мин".toByteArray(), "китап".toByteArray(), intArrayOf(65535, 65535))), tt),
            tpersb(listOf(Triple(ByteArray(0), "китап".toByteArray(), intArrayOf(0, 1))), tt),
            tpersb(listOf(Triple("у\u0308".toByteArray(), "китап".toByteArray(), intArrayOf(0, 1))), tt),
            tpersb(pairs("мин" to "китап"), "ru"), tpersb(pairs(), tt),
            tpersb(pairs("мин" to "китап"), tt, countOverride = 1001),
        )
        for (image in craftedPairs) emitVerdicts(w, "tpersb", tt, image)
        // Oversized (past the cap): rejected before parsing; the salvage reads up to the cap.
        val big = tpers(words(*Array(2000) { i -> "а" + "бвгдеж"[i % 6] + wordOf(i) }), tt)
        emitVerdicts(w, "tpers", tt, big + ByteArray(131_072))
    }

    private fun wordOf(i: Int): String {
        val letters = "абвгдежзийклмнопрстуфхцчшщ"
        var n = i
        val sb = StringBuilder()
        repeat(3) { sb.append(letters[n % letters.length]); n /= letters.length }
        return sb.toString()
    }

    private fun exportLookups(w: Writer, wordsImage: ByteArray, pairsImage: ByteArray) {
        val tt = PersonalSubtypes.TATAR_RU
        val file = Files.createTempFile("golden", ".tpers").toFile()
        file.writeBytes(wordsImage)
        val v = TpersValidator().validate(file, tt)
        val entries = PersonalEntries.fromValidated(v)
        val snapshot: PersonalDictionary = entries.toSnapshot(tt)
        for (prefix in listOf("а", "г", "гү", "гүзәл", "с", "к", "м", "", "я", "ә", "әнкәй", "абыйлар")) {
            val result = snapshot.lookupCandidates(prefix).joinToString(",") { "[${json(it.rawForm)},${json(it.normalizedForm)}]" }
            w.line("""{"kind":"lookup","bytes":${json(hex(wordsImage))},"prefix":${json(prefix)},"result":[$result],"index":${snapshot.indexOfNormalized(prefix)}}""")
        }
        file.writeBytes(pairsImage)
        val pv = TpersbValidator().validate(file, tt)
        val pairSnapshot: PersonalBigramDictionary = PersonalBigramEntries.fromValidated(pv).toSnapshot(tt)
        for (context in listOf("мин", "абв", "һәм", "без", "юк", "")) {
            val result = pairSnapshot.successorsFor(context).joinToString(",") { "[${json(it.rawForm)},${json(it.normalizedForm)}]" }
            w.line("""{"kind":"successors","bytes":${json(hex(pairsImage))},"context":${json(context)},"result":[$result],"index":${pairSnapshot.indexOfPair(context, "китап")}}""")
        }
        file.delete()
    }

    // ---- end-to-end store runs --------------------------------------------------------------

    private val directExecutor = Executor { it.run() }

    private object RealOps : DurableFileOps {
        override fun createNewFile(file: File): Boolean = file.createNewFile()
        override fun syncFile(fileDescriptor: FileDescriptor) = fileDescriptor.sync()
        override fun atomicRename(source: File, destination: File) {
            if (destination.exists() || !source.renameTo(destination)) throw IOException("rename failed")
        }
        override fun atomicReplace(source: File, destination: File) {
            if (!source.renameTo(destination)) {
                destination.delete()
                if (!source.renameTo(destination)) throw IOException("replace failed")
            }
        }
        override fun syncDirectory(directory: File) {}
        override fun delete(file: File): Boolean = file.delete()
    }

    private fun exportWordStore(w: Writer) {
        val dir = Files.createTempDirectory("golden-personal").toFile()
        val store = PersonalDictionaryStore(
            subtypeId = PersonalSubtypes.TATAR_RU, directoryProvider = { dir }, fileOps = RealOps,
            outputOpener = PersonalOutputOpener { FileOutputStream(it) }, spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L }, executor = directExecutor,
        )
        val ops = listOf(
            listOf("complete", "Гүзәл"), listOf("complete", "гүзәл"), listOf("flush"), listOf("complete", "ГҮЗӘЛ"),
            listOf("complete", "китап"), listOf("complete", "китап"), listOf("complete", "ab"), listOf("flush"),
            listOf("accept", "гүзәл"), listOf("accept", "юк"), listOf("flush"), listOf("add", "Мәктәп"),
            listOf("add", "12"), listOf("complete", "китап"), listOf("forget", "ГҮЗӘЛ"), listOf("flush"),
            listOf("forget", "китап"), listOf("forget", "мәктәп"), listOf("add", "әнкәй"), listOf("clear"),
        )
        val steps = ArrayList<String>()
        var salt = ""
        for (op in ops) {
            var outcome = "none"
            when (op[0]) {
                "complete" -> store.noteCompletion(op[1])
                "flush" -> store.flush()
                "accept" -> store.noteAcceptedSuggestion(op[1])
                "add" -> store.addManually(op[1]) { outcome = it.toString() }
                "forget" -> store.forget(op[1]) { outcome = it.toString() }
                "clear" -> store.clearAll { outcome = it.toString() }
            }
            File(dir, "salt.bin").takeIf { it.isFile }?.let { salt = hex(it.readBytes()) }
            steps.add("""{"outcome":"$outcome","files":${dumpDir(dir)},"snapshot":${jsonList(snapshotWords(store.snapshot))}}""")
        }
        w.line("""{"kind":"wordStore","subtype":"tt_RU","salt":${json(salt)},"ops":${jsonOps(ops)},"steps":[${steps.joinToString(",")}]}""")
        dir.deleteRecursively()
    }

    private fun exportPairStore(w: Writer) {
        val dir = Files.createTempDirectory("golden-personal").toFile()
        val known = setOf("мин", "без", "һәм")
        val store = PersonalBigramStore(
            subtypeId = PersonalSubtypes.TATAR_RU, directoryProvider = { dir }, fileOps = RealOps,
            outputOpener = PersonalOutputOpener { FileOutputStream(it) }, spaceProbe = SpaceProbe { Long.MAX_VALUE },
            clock = { 1000L }, executor = directExecutor,
            contextMembership = { _, context -> context in known },
        )
        val ops = listOf(
            listOf("note", "Мин", "Гүзәл"), listOf("flush"), listOf("note", "мин", "гүзәл"), listOf("note", "мин", "Гүзәл"),
            listOf("note", "юкъ", "китап"), listOf("note", "юкъ", "китап"), listOf("note", "без", "Казан"),
            listOf("note", "без", "казан"), listOf("accept", "мин", "ГҮЗӘЛ"), listOf("accept", "мин", "юк"), listOf("flush"),
            listOf("note", "мин", "ab"), listOf("forget", "без", "Казан"), listOf("forget", "юк", "юк"), listOf("flush"),
            listOf("forget", "мин", "гүзәл"), listOf("note", "һәм", "без"), listOf("clear"),
        )
        val steps = ArrayList<String>()
        var salt = ""
        for (op in ops) {
            var outcome = "none"
            when (op[0]) {
                "note" -> store.notePair(op[1], op[2])
                "flush" -> store.flush()
                "accept" -> store.noteAcceptedPrediction(op[1], op[2])
                "forget" -> store.forget(op[1], op[2]) { outcome = it.toString() }
                "clear" -> store.clearAll { outcome = it.toString() }
            }
            File(dir, "salt-bigrams.bin").takeIf { it.isFile }?.let { salt = hex(it.readBytes()) }
            val snap = store.snapshot
            val rows = (0 until snap.size).map { "${snap.contextAt(it)}|${snap.successorRawFormAt(it)}" }
            steps.add("""{"outcome":"$outcome","files":${dumpDir(dir)},"snapshot":${jsonList(rows)}}""")
        }
        w.line("""{"kind":"pairStore","subtype":"tt_RU","known":${jsonList(known.toList())},"salt":${json(salt)},"ops":${jsonOps(ops)},"steps":[${steps.joinToString(",")}]}""")
        dir.deleteRecursively()
    }

    private fun snapshotWords(s: PersonalDictionary): List<String> =
        (0 until s.size).map { "${s.rawFormAt(it)}|${s.usageCountAt(it)}" }

    private fun dumpDir(dir: File): String {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        return "{" + files.joinToString(",") { "${json(it.name)}:${json(hex(it.readBytes()))}" } + "}"
    }

    // ---- image builders -----------------------------------------------------------------------

    private fun tpers(records: List<Triple<ByteArray, Int, Long>>, tag: String, countOverride: Int? = null): ByteArray {
        val payload = records.sumOf { 7 + it.first.size }
        val b = ByteBuffer.allocate(72 + payload).order(ByteOrder.LITTLE_ENDIAN)
        b.put("TATPERS\u0000".toByteArray(StandardCharsets.US_ASCII))
        b.putShort(1).putShort(1).putShort(72).putShort(1)
        b.putInt(countOverride ?: records.size).putInt(payload)
        val t = ByteArray(16); tag.toByteArray(StandardCharsets.US_ASCII).copyInto(t, 0, 0, minOf(16, tag.length)); b.put(t)
        b.put(ByteArray(32))
        for ((bytes, usage, serial) in records) {
            b.put(bytes.size.toByte()); b.putShort(usage.toShort()); b.putInt(serial.toInt()); b.put(bytes)
        }
        return reseal(b.array())
    }

    private fun tpersb(records: List<Triple<ByteArray, ByteArray, IntArray>>, tag: String, countOverride: Int? = null): ByteArray {
        val payload = records.sumOf { 10 + it.first.size + it.second.size }
        val b = ByteBuffer.allocate(72 + payload).order(ByteOrder.LITTLE_ENDIAN)
        b.put("TATPERSB".toByteArray(StandardCharsets.US_ASCII))
        b.putShort(1).putShort(1).putShort(72).putShort(1)
        b.putInt(countOverride ?: records.size).putInt(payload)
        val t = ByteArray(16); tag.toByteArray(StandardCharsets.US_ASCII).copyInto(t, 0, 0, minOf(16, tag.length)); b.put(t)
        b.put(ByteArray(32))
        for ((c, s, counters) in records) {
            b.put(c.size.toByte()); b.put(s.size.toByte()); b.putShort(counters[0].toShort()); b.putShort(counters[1].toShort())
            b.putInt(1); b.put(c); b.put(s)
        }
        return reseal(b.array())
    }

    private fun reseal(image: ByteArray): ByteArray {
        if (image.size < 72) return image
        val copy = image.copyOf()
        copy.fill(0, 40, 72)
        MessageDigest.getInstance("SHA-256").digest(copy).copyInto(copy, 40)
        return copy
    }

    // ---- JSON / hex -----------------------------------------------------------------------------

    private fun Writer.line(s: String) { write(s); write("\n") }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun jsonOrNull(s: String?): String = if (s == null) "null" else json(s)

    private fun jsonList(items: List<String>): String = items.joinToString(",", "[", "]") { json(it) }

    private fun jsonOps(ops: List<List<String>>): String = ops.joinToString(",", "[", "]") { jsonList(it) }

    private fun json(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}

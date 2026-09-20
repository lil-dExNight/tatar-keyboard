package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.BigramTestFixtures
import rkr.simplekeyboard.inputmethod.latin.dictionary.storage.DictionaryTestFixtures
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

internal object EngineTestFixtures {
    val identity = DictionaryIdentity(1, 2, 1, "a".repeat(64))
    val bigramIdentity = BigramTableIdentity(1, "tt", 3, 1, "b".repeat(64))

    fun index(
        entries: List<Pair<String, Long>>,
        suffixTable: InflectedSuffixTable? = null,
    ): TdictPrefixIndex {
        val raw = DictionaryTestFixtures.raw(entries)
        return requireNotNull(
            TdictPrefixIndex.open(
                ByteBuffer.wrap(raw),
                identity,
                entries.size.toLong(),
                raw.size.toLong(),
                suffixTable,
            ),
        )
    }

    fun bigramIndex(headsToSuccesses: List<Pair<String, List<String>>>): TatBigrPrefixIndex {
        // Schema 3 resolves words through the linked dictionary: the fixture dictionary is
        // exactly the heads and successes involved, and the table header names this fixture's
        // identity hash (BigramTestFixtures.DEFAULT_DICTIONARY_SHA == identity.rawSha256).
        val dictionaryWords = BigramTestFixtures.defaultDictionaryWords(headsToSuccesses)
        val dictionary = index(dictionaryWords.map { it to 1L })
        val raw = BigramTestFixtures.raw(headsToSuccesses, dictionaryWords)
        return requireNotNull(
            TatBigrPrefixIndex.open(
                ByteBuffer.wrap(raw),
                bigramIdentity,
                dictionary,
                headsToSuccesses.size.toLong(),
                raw.size.toLong(),
            ),
        )
    }
}

internal class ManualEngineExecutor(
    private val reject: Boolean = false,
) : EngineExecutor {
    private val tasks = ArrayDeque<Runnable>()
    private var shutdown = false
    private var running = false
    var maxQueueDepth = 0
        private set

    val queueDepth: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        if (reject || shutdown) throw RejectedExecutionException("test rejection")
        tasks.addLast(command)
        maxQueueDepth = maxOf(maxQueueDepth, tasks.size)
    }

    fun runNext(): Boolean {
        val task = tasks.pollFirst() ?: return false
        running = true
        try {
            task.run()
        } finally {
            running = false
        }
        return true
    }

    fun runAll() {
        while (runNext()) Unit
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        shutdown && !running && tasks.isEmpty()
}

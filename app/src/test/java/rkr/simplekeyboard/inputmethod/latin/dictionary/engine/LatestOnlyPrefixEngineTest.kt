package rkr.simplekeyboard.inputmethod.latin.dictionary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rkr.simplekeyboard.inputmethod.latin.glide.GlideIndexPrewarmer
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

class LatestOnlyPrefixEngineTest {
    @Test
    fun burstKeepsOneRunningAndReplacesSinglePendingSlot() {
        val executor = ManualEngineExecutor()
        val computed = mutableListOf<String>()
        val published = mutableListOf<LookupResult>()
        val engine = engine(executor, computed, published)

        engine.request(1, "tt", utf8("а"))
        engine.request(1, "tt", utf8("аб"))
        engine.request(1, "tt", utf8("аба"))
        val latest = requireNotNull(engine.request(1, "tt", utf8("абв")))

        assertEquals(1, executor.queueDepth)
        assertEquals(1, executor.maxQueueDepth)
        assertEquals(1, engine.readerCountForTest())
        assertTrue(engine.hasPendingForTest())

        executor.runAll()

        assertEquals(listOf("а", "абв"), computed)
        assertEquals(1, published.size)
        assertEquals(latest, published.single().token)
        assertEquals(listOf("result:абв"), published.single().suggestions)
        assertEquals(0, engine.readerCountForTest())
        assertFalse(engine.hasPendingForTest())
        assertEquals(1L, engine.suppressedStaleResultCount)
        assertEquals(1L, engine.handoffCount)
    }

    @Test
    fun glidePrewarmDoesNotChangeLatestOnlyTokensOrResults() {
        val executor = ManualEngineExecutor()
        val events = mutableListOf<String>()
        val published = mutableListOf<LookupResult>()
        val computer = object : PrefixComputer, GlideIndexPrewarmer {
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
                val prefix = normalizedPrefixUtf8.decodeUtf8()
                events += "lookup:$prefix"
                return listOf(prefix)
            }

            override fun prewarmGlideIndex() {
                events += "prewarm"
            }
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity, computer, executor, ResultHandoff { published += it },
        )

        engine.request(1, "tt", utf8("old"))
        engine.prewarmGlideIndex()
        val latest = requireNotNull(engine.request(1, "tt", utf8("latest")))

        assertTrue(engine.isCurrent(latest))
        assertEquals(1, engine.readerCountForTest())
        assertTrue(engine.hasPendingForTest())
        assertEquals(0L, engine.handoffCount)
        executor.runAll()

        assertEquals(listOf("lookup:old", "lookup:latest", "prewarm"), events)
        assertEquals(listOf(latest), published.map { it.token })
        assertEquals(listOf("latest"), published.single().suggestions)
        assertTrue(engine.isCurrent(latest))
        assertEquals(1L, engine.handoffCount)
    }

    @Test
    fun workerBoundariesCarryTheCookieStampedAtEnqueue() {
        val executor = ManualEngineExecutor()
        val events = mutableListOf<String>()
        val hook = object : LookupTraceHook {
            var issued = 42
            override fun issuedCookie(): Int = issued
            override fun workerStart(cookie: Int) {
                events += "workerStart:$cookie"
            }

            override fun workerHandoff(cookie: Int) {
                events += "workerHandoff:$cookie"
            }
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { events += "compute:${it.decodeUtf8()}"; listOf("result") },
            executor,
            ResultHandoff { events += "deliver" },
            traceHook = hook,
        )

        engine.request(1, "tt", utf8("а"))
        executor.runAll()

        assertEquals(
            listOf("workerStart:42", "compute:а", "workerHandoff:42", "deliver"),
            events,
        )
    }

    @Test
    fun staleRequestKeepsItsOwnCookieAndSkipsTheDeliveryBoundary() {
        val executor = ManualEngineExecutor()
        val events = mutableListOf<String>()
        var issued = 7
        val hook = object : LookupTraceHook {
            override fun issuedCookie(): Int = issued
            override fun workerStart(cookie: Int) {
                events += "workerStart:$cookie"
            }

            override fun workerHandoff(cookie: Int) {
                events += "workerHandoff:$cookie"
            }
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { events += "compute:${it.decodeUtf8()}"; listOf("result") },
            executor,
            ResultHandoff { events += "deliver" },
            traceHook = hook,
        )

        engine.request(1, "tt", utf8("а"))
        issued = 8
        engine.request(1, "tt", utf8("аб"))
        executor.runAll()

        // The stale "а" was stamped 7 at its own enqueue and reports only its worker start:
        // its result is suppressed, so the delivery boundary never fires for it.
        assertEquals(
            listOf(
                "workerStart:7",
                "compute:а",
                "workerStart:8",
                "compute:аб",
                "workerHandoff:8",
                "deliver",
            ),
            events,
        )
    }

    @Test
    fun editorSubtypeAndPrefixChangesGuardRunningResult() {
        val changes = listOf(
            Triple(2L, "tt", "а"),
            Triple(1L, "ru", "а"),
            Triple(1L, "tt", "аб"),
        )
        for ((editor, subtype, prefix) in changes) {
            val executor = ManualEngineExecutor()
            val published = mutableListOf<LookupResult>()
            val engine = engine(executor, mutableListOf(), published)
            val old = requireNotNull(engine.request(1, "tt", utf8("а")))
            val current = requireNotNull(engine.request(editor, subtype, utf8(prefix)))

            executor.runAll()

            assertNotEquals(old, current)
            assertEquals(listOf(current), published.map { it.token })
            assertEquals(1L, engine.suppressedStaleResultCount)
        }
    }

    @Test
    fun queuedOwnerDropsAWhenBStartsBeforeFinalGuardedApply() {
        val executor = ManualEngineExecutor()
        val ownerQueue = ArrayDeque<() -> Unit>()
        val handedOff = mutableListOf<LookupResult>()
        val applied = mutableListOf<LookupResult>()
        lateinit var engine: LatestOnlyPrefixEngine
        engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { listOf("result:${it.decodeUtf8()}") },
            executor,
            ResultHandoff { result ->
                handedOff += result
                ownerQueue.addLast {
                    if (engine.isCurrent(result.token)) applied += result
                }
            },
        )
        val tokenA = requireNotNull(engine.request(1, "tt", utf8("a")))
        executor.runAll()
        assertEquals(listOf(tokenA), handedOff.map { it.token })
        assertEquals(1, ownerQueue.size)

        engine.finishInput()
        val tokenB = requireNotNull(engine.request(2, "tt", utf8("b")))
        ownerQueue.removeFirst().invoke()
        assertTrue("stale A reached final apply", applied.isEmpty())

        executor.runAll()
        assertEquals(listOf(tokenA, tokenB), handedOff.map { it.token })
        ownerQueue.removeFirst().invoke()
        assertEquals(listOf(tokenB), applied.map { it.token })
    }

    @Test
    fun finishInputInvalidatesRunningAndClearsPending() {
        val executor = ManualEngineExecutor()
        val computed = mutableListOf<String>()
        val published = mutableListOf<LookupResult>()
        val engine = engine(executor, computed, published)
        engine.request(1, "tt", utf8("а"))
        engine.request(1, "tt", utf8("аб"))

        engine.finishInput()
        assertFalse(engine.hasPendingForTest())
        executor.runAll()

        assertEquals(listOf("а"), computed)
        assertTrue(published.isEmpty())
        assertEquals(1L, engine.suppressedStaleResultCount)
    }

    @Test
    fun autocorrectAdviceIsClearedByFinishNewLookupAndTeardown() {
        val executor = ManualEngineExecutor()
        val computer = object : ClassifiedPrefixComputer {
            override val lastExactCount: Int = 0
            @Volatile
            override var lastAutocorrectAdvice: AutocorrectAdvice? = null
                private set

            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
                val typed = normalizedPrefixUtf8.decodeUtf8()
                lastAutocorrectAdvice = AutocorrectAdvice(
                    typed, "китап", 5_000L, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                )
                return listOf("китап")
            }
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity, computer, executor, ResultHandoff {},
        )
        engine.request(1, "tt", utf8("китәп"))
        executor.runAll()
        assertEquals("китап", engine.autocorrectAdvice()?.replacement)

        engine.finishInput()
        assertNull(engine.autocorrectAdvice())
        engine.request(2, "ru", utf8("китәп"))
        assertNull("a new token cannot read the previous lookup's verdict", engine.autocorrectAdvice())
        executor.runAll()
        assertEquals("китап", engine.autocorrectAdvice()?.replacement)

        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertNull(engine.autocorrectAdvice())
    }

    @Test
    fun aWorkerFinishingDuringTeardownCannotPublishAutocorrectAdvice() {
        val executor = ManualEngineExecutor()
        val computer = object : ClassifiedPrefixComputer {
            override val lastExactCount: Int = 0
            @Volatile
            override var lastAutocorrectAdvice: AutocorrectAdvice? = null
                private set

            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
                lastAutocorrectAdvice = AutocorrectAdvice(
                    normalizedPrefixUtf8.decodeUtf8(), "китап",
                    5_000L, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                )
                return emptyList()
            }
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity, computer, executor, ResultHandoff {},
        )
        engine.request(1, "tt", utf8("китәп"))

        assertFalse(engine.destroy(0, TimeUnit.MILLISECONDS))
        executor.runAll()

        assertNull(engine.autocorrectAdvice())
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertNull(engine.autocorrectAdvice())
    }

    @Test
    fun repeatedIdenticalRequestsHaveDistinctSerials() {
        val executor = ManualEngineExecutor()
        val engine = engine(executor, mutableListOf(), mutableListOf())

        val first = requireNotNull(engine.request(5, "tt", utf8("ә")))
        val second = requireNotNull(engine.request(5, "tt", utf8("ә")))

        assertNotEquals(first.requestSerial, second.requestSerial)
        assertEquals(first.normalizedQuery, second.normalizedQuery)
        executor.runAll()
    }

    @Test
    fun prefixRequestCarriesTheContextWordIntoTheTokenAndTheArmedLookup() {
        val executor = ManualEngineExecutor()
        val armedCalls = mutableListOf<Pair<String, String>>()
        val plainCalls = mutableListOf<String>()
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            object : ContextAwarePrefixComputer {
                override fun lookup(prefix: ImmutableUtf8Prefix): List<String> {
                    plainCalls += prefix.decodeUtf8()
                    return emptyList()
                }

                override fun lookup(
                    prefix: ImmutableUtf8Prefix,
                    context: ImmutableUtf8Prefix,
                ): List<String> {
                    armedCalls += prefix.decodeUtf8() to context.decodeUtf8()
                    return emptyList()
                }
            },
            executor,
            ResultHandoff {},
        )

        val token = requireNotNull(engine.request(1, "tt", utf8("ка"), utf8("дан")))
        assertEquals("дан", token.normalizedContext.decodeUtf8())
        // NEXT_WORD and GLIDE requests carry no prefix-path context.
        val nextWord = requireNotNull(engine.requestNextWord(1, "tt", utf8("дан")))
        assertEquals(0, nextWord.normalizedContext.byteCount)
        executor.runAll()

        assertEquals(listOf("ка" to "дан"), armedCalls)
        assertTrue(plainCalls.isEmpty())
    }

    @Test
    fun emptyContextKeepsTheUnarmedLookupByteIdentical() {
        val executor = ManualEngineExecutor()
        val armedCalls = mutableListOf<Pair<String, String>>()
        val plainCalls = mutableListOf<String>()
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            object : ContextAwarePrefixComputer {
                override fun lookup(prefix: ImmutableUtf8Prefix): List<String> {
                    plainCalls += prefix.decodeUtf8()
                    return emptyList()
                }

                override fun lookup(
                    prefix: ImmutableUtf8Prefix,
                    context: ImmutableUtf8Prefix,
                ): List<String> {
                    armedCalls += prefix.decodeUtf8() to context.decodeUtf8()
                    return emptyList()
                }
            },
            executor,
            ResultHandoff {},
        )

        val token = requireNotNull(engine.request(1, "tt", utf8("ка")))
        assertEquals(0, token.normalizedContext.byteCount)
        executor.runAll()

        assertEquals(listOf("ка"), plainCalls)
        assertTrue(armedCalls.isEmpty())
    }

    @Test
    fun anUnusableContextIsDroppedNeverTheRequest() {
        val executor = ManualEngineExecutor()
        val plainCalls = mutableListOf<String>()
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            object : ContextAwarePrefixComputer {
                override fun lookup(prefix: ImmutableUtf8Prefix): List<String> {
                    plainCalls += prefix.decodeUtf8()
                    return emptyList()
                }

                override fun lookup(
                    prefix: ImmutableUtf8Prefix,
                    context: ImmutableUtf8Prefix,
                ): List<String> = throw AssertionError("a dropped context never arms a lookup")
            },
            executor,
            ResultHandoff {},
        )

        // Over-long and invalid UTF-8 contexts are clamped to none; the request stands.
        val overLong = requireNotNull(
            engine.request(1, "tt", utf8("ка"), ByteArray(TatBigrPrefixIndex.MAX_WORD_BYTES + 1))
        )
        val invalid = requireNotNull(
            engine.request(1, "tt", utf8("ка"), byteArrayOf(0xd0.toByte()))
        )
        assertEquals(0, overLong.normalizedContext.byteCount)
        assertEquals(0, invalid.normalizedContext.byteCount)
        executor.runAll()
        assertEquals(listOf("ка", "ка"), plainCalls)
    }

    @Test
    fun aContextChangeInvalidatesTheRunningAnswerLikeAnyOtherChange() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { listOf("result:${it.decodeUtf8()}") },
            executor,
            ResultHandoff { published += it },
        )

        val old = requireNotNull(engine.request(1, "tt", utf8("ка"), utf8("дан")))
        val current = requireNotNull(engine.request(1, "tt", utf8("ка"), utf8("сүз")))
        executor.runAll()

        assertNotEquals(old, current)
        assertNotEquals(old.normalizedContext, current.normalizedContext)
        assertEquals(listOf(current), published.map { it.token })
        assertEquals(1L, engine.suppressedStaleResultCount)
    }

    @Test
    fun aNewerRequestSuppressesAnOldVerdictBeforePublishingItsOwn() {
        val oldEntered = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val currentPublished = CountDownLatch(1)
        val computer = object : ContextAwarePrefixComputer, ClassifiedPrefixComputer {
            override val lastExactCount: Int = 0
            @Volatile
            override var lastAutocorrectAdvice: AutocorrectAdvice? = null
                private set

            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> =
                throw AssertionError("both requests carry a context")

            override fun lookup(
                normalizedPrefixUtf8: ImmutableUtf8Prefix,
                normalizedContextWordUtf8: ImmutableUtf8Prefix,
            ): List<String> {
                val replacement = when (normalizedContextWordUtf8.decodeUtf8()) {
                    "иске" -> {
                        oldEntered.countDown()
                        check(releaseOld.await(5, TimeUnit.SECONDS))
                        "китап"
                    }
                    "яңа" -> {
                        newEntered.countDown()
                        check(releaseNew.await(5, TimeUnit.SECONDS))
                        "китәб"
                    }
                    else -> throw AssertionError("unexpected context")
                }
                lastAutocorrectAdvice = AutocorrectAdvice(
                    normalizedPrefixUtf8.decodeUtf8(), replacement,
                    5_000L, AutocorrectPolicy.MIN_CANDIDATE_FREQUENCY_TT,
                )
                return listOf(replacement)
            }
        }
        val executor = ExecutorServiceEngineExecutor.singleThread()
        val published = Collections.synchronizedList(mutableListOf<LookupResult>())
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            computer,
            executor,
            ResultHandoff {
                published += it
                currentPublished.countDown()
            },
        )
        try {
            val old = requireNotNull(engine.request(1, "tt", utf8("китәп"), utf8("иске")))
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            val current = requireNotNull(engine.request(2, "tt", utf8("китәп"), utf8("яңа")))

            releaseOld.countDown()
            assertTrue(newEntered.await(5, TimeUnit.SECONDS))
            // The old worker has completed; the newer one has not. Only worker-local old advice
            // exists at this point, and the generation guard must keep it invisible.
            assertNull(engine.autocorrectAdvice())

            releaseNew.countDown()
            assertTrue(currentPublished.await(5, TimeUnit.SECONDS))
            assertEquals("китәб", engine.autocorrectAdvice()?.replacement)
            assertEquals(listOf(current), published.map { it.token })
            assertFalse(engine.isCurrent(old))
            assertTrue(engine.isCurrent(current))
            assertEquals("китәб", engine.autocorrectAdvice()?.replacement)
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aComputerWithoutTheBoostAnswersTheUnarmedLookup() {
        val executor = ManualEngineExecutor()
        val computed = mutableListOf<String>()
        val engine = engine(executor, computed, mutableListOf())

        engine.request(1, "tt", utf8("ка"), utf8("дан"))
        executor.runAll()

        assertEquals(listOf("ка"), computed)
    }

    @Test
    fun computerCanRetainImmutablePrefixButCannotMutateOrObserveCallerChanges() {
        val executor = ManualEngineExecutor()
        val computed = mutableListOf<String>()
        val retained = mutableListOf<ImmutableUtf8Prefix>()
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { prefix ->
                retained += prefix
                prefix.decodeUtf8().also { computed += it }.let { listOf(it) }
            },
            executor,
            ResultHandoff {},
        )
        val prefix = utf8("аб")

        val token = requireNotNull(engine.request(1, "tt", prefix))
        prefix.fill(0)
        executor.runAll()

        assertEquals(listOf("аб"), computed)
        assertEquals(ImmutableUtf8Prefix.copyOf(utf8("аб")), token.normalizedQuery)
        assertEquals("аб", retained.single().decodeUtf8())
        assertTrue(
            ImmutableUtf8Prefix::class.java.methods.none { it.returnType == ByteArray::class.java },
        )
    }

    @Test
    fun invalidPrefixesInvalidateRunningWorkBeforeExecutorOrComputer() {
        val executor = ManualEngineExecutor()
        var computes = 0
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer {
                computes++
                emptyList()
            },
            executor,
            ResultHandoff {},
        )

        engine.request(1, "tt", utf8("old"))
        assertNull(engine.request(1, "tt", ByteArray(TdictPrefixIndex.MAX_PREFIX_BYTES + 1)))
        assertNull(engine.request(1, "tt", byteArrayOf(0xd0.toByte())))
        assertNull(engine.request(1, "tt", ByteArray(0)))
        executor.runAll()

        assertEquals(0, executor.queueDepth)
        assertEquals(1, computes)
        assertEquals(0L, engine.handoffCount)
        assertEquals(1L, engine.suppressedStaleResultCount)
    }

    @Test
    fun glidePrewarmAndLookupShareTheSerializedWorker() {
        val executor = ExecutorServiceEngineExecutor.singleThread()
        val prewarmEntered = CountDownLatch(1)
        val releasePrewarm = CountDownLatch(1)
        val lookupEntered = CountDownLatch(1)
        val activeComputers = AtomicInteger()
        val maximumActiveComputers = AtomicInteger()
        val computer = object : PrefixComputer, GlideIndexPrewarmer {
            private fun enter() {
                val active = activeComputers.incrementAndGet()
                maximumActiveComputers.set(maxOf(maximumActiveComputers.get(), active))
            }

            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
                enter()
                lookupEntered.countDown()
                activeComputers.decrementAndGet()
                return emptyList()
            }

            override fun prewarmGlideIndex() {
                enter()
                prewarmEntered.countDown()
                try {
                    check(releasePrewarm.await(2, TimeUnit.SECONDS))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    activeComputers.decrementAndGet()
                }
            }
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity, computer, executor, ResultHandoff {},
        )

        try {
            engine.prewarmGlideIndex()
            assertTrue(prewarmEntered.await(2, TimeUnit.SECONDS))
            engine.request(1, "tt", utf8("a"))
            assertFalse("lookup overlapped index preparation", lookupEntered.await(100, TimeUnit.MILLISECONDS))
            releasePrewarm.countDown()
            assertTrue(lookupEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1, maximumActiveComputers.get())
        } finally {
            releasePrewarm.countDown()
            assertTrue(engine.destroy(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun realSingleThreadExecutorCannotStrandRequestAtPendingToIdleBoundary() {
        val executor = ExecutorServiceEngineExecutor.singleThread()
        val published = Collections.synchronizedList(mutableListOf<LookupResult>())
        val secondPublished = CountDownLatch(1)
        val requester = AtomicReference<Thread>()
        val launchBoundaryRequest = AtomicBoolean(true)
        lateinit var engine: LatestOnlyPrefixEngine
        engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { prefix -> listOf("result:${prefix.decodeUtf8()}") },
            executor,
            ResultHandoff { result ->
                published += result
                if (launchBoundaryRequest.compareAndSet(true, false)) {
                    val thread = Thread({
                        engine.request(1, "tt", utf8("boundary"))
                    }, "boundary-request")
                    requester.set(thread)
                    thread.start()
                    thread.join(1_000)
                    assertFalse("result handoff ran under the engine monitor", thread.isAlive)
                } else {
                    secondPublished.countDown()
                }
            },
        )

        engine.request(1, "tt", utf8("first"))

        assertTrue("boundary request was stranded", secondPublished.await(2, TimeUnit.SECONDS))
        requester.get().join(1_000)
        assertFalse(requester.get().isAlive)
        assertEquals(listOf("result:first", "result:boundary"), published.map { it.suggestions.single() })
        assertTrue(engine.destroy(2, TimeUnit.SECONDS))
    }

    @Test
    fun concurrentBurstHasOneReaderAndOnlyLatestPendingComputation() {
        val executor = ExecutorServiceEngineExecutor.singleThread()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val latestPublished = CountDownLatch(1)
        val activeComputers = AtomicInteger()
        val maximumActiveComputers = AtomicInteger()
        val computed = Collections.synchronizedList(mutableListOf<String>())
        val published = Collections.synchronizedList(mutableListOf<LookupResult>())
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { prefix ->
                val active = activeComputers.incrementAndGet()
                maximumActiveComputers.accumulateAndGet(active, ::maxOf)
                try {
                    val value = prefix.decodeUtf8()
                    computed += value
                    if (value == "first") {
                        firstEntered.countDown()
                        assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    }
                    listOf("result:$value")
                } finally {
                    activeComputers.decrementAndGet()
                }
            },
            executor,
            ResultHandoff {
                published += it
                if (it.suggestions == listOf("result:latest")) latestPublished.countDown()
            },
        )
        engine.request(1, "tt", utf8("first"))
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val callers = (0 until 8).map { caller ->
            Thread({
                repeat(250) { request ->
                    engine.request(caller.toLong(), "tt-$caller", utf8("$caller-$request"))
                }
            }, "burst-$caller").apply(Thread::start)
        }
        callers.forEach { it.join(2_000) }
        assertTrue(callers.none(Thread::isAlive))
        val latest = requireNotNull(engine.request(99, "tt", utf8("latest")))
        assertEquals(1, engine.readerCountForTest())
        assertTrue(engine.hasPendingForTest())

        releaseFirst.countDown()
        assertTrue(latestPublished.await(2, TimeUnit.SECONDS))

        assertEquals(listOf("first", "latest"), computed)
        assertEquals(listOf(latest), published.map { it.token })
        assertEquals(1, maximumActiveComputers.get())
        assertEquals(1L, engine.suppressedStaleResultCount)
        assertTrue(engine.destroy(2, TimeUnit.SECONDS))
    }

    @Test
    fun directExecutorAndReentrantHandoffDoNotDeadlockOrStrandReader() {
        val executor = DirectEngineExecutor()
        val published = mutableListOf<String>()
        val reenter = AtomicBoolean(true)
        lateinit var engine: LatestOnlyPrefixEngine
        engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { listOf(it.decodeUtf8()) },
            executor,
            ResultHandoff { result ->
                published += result.suggestions.single()
                if (reenter.compareAndSet(true, false)) {
                    engine.request(2, "tt", utf8("nested"))
                    engine.finishInput()
                }
            },
        )

        engine.request(1, "tt", utf8("outer"))

        assertEquals(listOf("outer", "nested"), published)
        assertEquals(0, engine.readerCountForTest())
        assertFalse(engine.hasPendingForTest())
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
    }

    @Test
    fun throwableFromComputerHandoffAndExecutorCannotStrandLifecycle() {
        val computerExecutor = ManualEngineExecutor()
        val computerPublished = mutableListOf<LookupResult>()
        val computerFailure = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { throw AssertionError("computer") },
            computerExecutor,
            ResultHandoff { computerPublished += it },
        )
        computerFailure.request(1, "tt", utf8("а"))
        computerExecutor.runAll()
        assertTrue(computerPublished.single().suggestions.isEmpty())
        assertEquals(0, computerFailure.readerCountForTest())
        assertTrue(computerFailure.destroy(1, TimeUnit.SECONDS))

        val handoffExecutor = ManualEngineExecutor()
        val handoffFailure = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { listOf("safe") },
            handoffExecutor,
            ResultHandoff { throw AssertionError("handoff") },
        )
        handoffFailure.request(1, "tt", utf8("а"))
        handoffExecutor.runAll()
        assertEquals(0, handoffFailure.readerCountForTest())
        assertTrue(handoffFailure.destroy(1, TimeUnit.SECONDS))

        val executorFailure = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { listOf("never") },
            ThrowingEngineExecutor(),
            ResultHandoff {},
        )
        assertNotNull(executorFailure.request(1, "tt", utf8("а")))
        assertEquals(0, executorFailure.readerCountForTest())
    }

    @Test
    fun interruptIsPreservedAndConcurrentDestroyReleasesExactlyOnce() {
        val interrupting = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { emptyList() },
            InterruptingEngineExecutor(),
            ResultHandoff {},
        )
        assertFalse(interrupting.destroy(1, TimeUnit.SECONDS))
        assertTrue(Thread.currentThread().isInterrupted)
        Thread.interrupted()

        val executor = ExecutorServiceEngineExecutor.singleThread()
        val releases = AtomicInteger()
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { emptyList() },
            executor,
            ResultHandoff {},
            releaseResources = { releases.incrementAndGet() },
        )
        val outcomes = Collections.synchronizedList(mutableListOf<Boolean>())
        val destroyers = (0 until 8).map { index ->
            Thread({ outcomes += engine.destroy(2, TimeUnit.SECONDS) }, "destroy-$index")
                .apply { start() }
        }
        destroyers.forEach { it.join(3_000) }

        assertTrue(destroyers.none { it.isAlive })
        assertEquals(List(8) { true }, outcomes)
        assertEquals(1, releases.get())
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertEquals(1, releases.get())
    }

    @Test
    fun separateEngineInstancesNeverProduceCollidingTokensForSameGeneration() {
        val firstExecutor = ManualEngineExecutor()
        val secondExecutor = ManualEngineExecutor()
        val first = engine(firstExecutor, mutableListOf(), mutableListOf())
        val second = engine(secondExecutor, mutableListOf(), mutableListOf())

        val firstToken = requireNotNull(first.request(7, "tt", utf8("аб")))
        val secondToken = requireNotNull(second.request(7, "tt", utf8("аб")))

        assertNotEquals(firstToken, secondToken)
        assertNotEquals(firstToken.engineInstanceId, secondToken.engineInstanceId)
        assertEquals(firstToken.dictionary, secondToken.dictionary)
        assertTrue(first.isCurrent(firstToken))
        assertFalse(first.isCurrent(secondToken))
        first.finishInput()
        assertFalse(first.isCurrent(firstToken))
        firstExecutor.runAll()
        secondExecutor.runAll()
    }

    @Test
    fun fullDictionaryIdentityGuardChecksSchemaFormatAndShaIndependently() {
        val executor = ManualEngineExecutor()
        val engine = engine(executor, mutableListOf(), mutableListOf())
        val token = requireNotNull(engine.request(7, "tt", utf8("аб")))
        val base = token.dictionary
        val variants = listOf(
            base.copy(schemaId = base.schemaId + 1),
            base.copy(formatVersion = base.formatVersion + 1),
            base.copy(rawSha256 = "b".repeat(64)),
        )

        assertTrue(engine.isCurrent(token))
        for (identity in variants) {
            assertEquals(base.generation, identity.generation)
            assertFalse(engine.isCurrent(token.copy(dictionary = identity)))
        }
        executor.runAll()
    }

    @Test
    fun rejectedExecutorHandsOffGuardedEmptyResultAndDoesNotThrow() {
        val executor = ManualEngineExecutor(reject = true)
        val published = mutableListOf<LookupResult>()
        val engine = engine(executor, mutableListOf(), published)

        val token = requireNotNull(engine.request(1, "tt", utf8("а")))

        assertEquals(token, published.single().token)
        assertTrue(published.single().suggestions.isEmpty())
        assertEquals(0, engine.readerCountForTest())
    }

    @Test
    fun lookupAndHandoffFailuresStayInsideEngine() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val lookupFailure = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { throw IllegalStateException("lookup failed") },
            executor,
            ResultHandoff { published += it },
        )
        lookupFailure.request(1, "tt", utf8("а"))
        executor.runAll()
        assertTrue(published.single().suggestions.isEmpty())

        val handoffExecutor = ManualEngineExecutor()
        val handoffFailure = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { listOf("safe") },
            handoffExecutor,
            ResultHandoff { throw IllegalStateException("handoff failed") },
        )
        handoffFailure.request(1, "tt", utf8("а"))
        handoffExecutor.runAll()
        assertEquals(0, handoffFailure.readerCountForTest())
    }

    @Test
    fun unfinishedWorkerRetainsResourcesUntilLaterSuccessfulDestroy() {
        val executor = ManualEngineExecutor()
        var releases = 0
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { emptyList() },
            executor,
            ResultHandoff {},
            releaseResources = { releases++ },
        )
        engine.request(1, "tt", utf8("а"))

        assertFalse(engine.destroy(0, TimeUnit.MILLISECONDS))
        assertEquals(0, releases)
        assertEquals(1, engine.readerCountForTest())
        assertNull(engine.request(2, "tt", utf8("б")))

        executor.runAll()
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertEquals(1, releases)
        assertEquals(0, engine.readerCountForTest())
        assertTrue(engine.destroy(1, TimeUnit.SECONDS))
        assertEquals(1, releases)
    }

    @Test
    fun dictionaryGenerationIsPartOfTokenAndOldGenerationNeverHandsOffAfterDestroy() {
        val oldExecutor = ManualEngineExecutor()
        val oldPublished = mutableListOf<LookupResult>()
        val oldEngine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { listOf("old") },
            oldExecutor,
            ResultHandoff { oldPublished += it },
        )
        oldEngine.request(1, "tt", utf8("а"))
        assertFalse(oldEngine.destroy(0, TimeUnit.MILLISECONDS))
        oldExecutor.runAll()
        assertTrue(oldEngine.destroy(1, TimeUnit.SECONDS))

        val newIdentity = EngineTestFixtures.identity.copy(generation = 2, rawSha256 = "b".repeat(64))
        val newExecutor = ManualEngineExecutor()
        val newPublished = mutableListOf<LookupResult>()
        val newEngine = LatestOnlyPrefixEngine(
            newIdentity,
            PrefixComputer { listOf("new") },
            newExecutor,
            ResultHandoff { newPublished += it },
        )
        val newToken = requireNotNull(newEngine.request(2, "tt", utf8("а")))
        newExecutor.runAll()

        assertTrue(oldPublished.isEmpty())
        assertEquals(newIdentity, newToken.dictionary)
        assertEquals(listOf("new"), newPublished.single().suggestions)
    }

    // --- NEXT_WORD requests share the same engine, token and executor as PREFIX ---------------

    @Test
    fun requestNextWordInvokesNextWordComputerNotPrefixComputerLookup() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        var prefixLookups = 0
        var nextWordPredicts = 0
        val computer = object : PrefixComputer, NextWordComputer {
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
                prefixLookups++
                return listOf("prefix:${normalizedPrefixUtf8.decodeUtf8()}")
            }

            override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> {
                nextWordPredicts++
                return listOf("next:${normalizedContextWordUtf8.decodeUtf8()}")
            }
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity, computer, executor, ResultHandoff { published += it },
        )

        val token = requireNotNull(engine.requestNextWord(1, "tt", utf8("өй")))
        executor.runAll()

        assertEquals(LookupKind.NEXT_WORD, token.kind)
        assertEquals(0, prefixLookups)
        assertEquals(1, nextWordPredicts)
        assertEquals(listOf("next:өй"), published.single().suggestions)
        assertEquals(LookupKind.NEXT_WORD, published.single().kind)
        assertEquals(LookupKind.NEXT_WORD, published.single().token.kind)
    }

    @Test
    fun requestNextWordAgainstAPureComputerWithoutNextWordComputerYieldsEmpty() {
        val executor = ManualEngineExecutor()
        val published = mutableListOf<LookupResult>()
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            PrefixComputer { listOf("should-not-be-used") },
            executor,
            ResultHandoff { published += it },
        )

        engine.requestNextWord(1, "tt", utf8("өй"))
        executor.runAll()

        // A corrupt, missing or non-activatable bigram file yields 0 predictions; a computer with
        // no bigram source attached is exactly that state.
        assertTrue(published.single().suggestions.isEmpty())
    }

    @Test
    fun requestNextWordRejectsEmptyOversizedAndInvalidUtf8ContextWords() {
        val executor = ManualEngineExecutor()
        var predicts = 0
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            object : PrefixComputer, NextWordComputer {
                override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix) = emptyList<String>()
                override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> {
                    predicts++
                    return emptyList()
                }
            },
            executor,
            ResultHandoff {},
        )

        engine.requestNextWord(1, "tt", utf8("old"))
        assertNull(engine.requestNextWord(1, "tt", ByteArray(0)))
        assertNull(engine.requestNextWord(1, "tt", ByteArray(TatBigrPrefixIndex.MAX_WORD_BYTES + 1)))
        assertNull(engine.requestNextWord(1, "tt", byteArrayOf(0xd0.toByte())))
        executor.runAll()

        assertEquals(1, predicts)
        assertEquals(1L, engine.suppressedStaleResultCount)
    }

    @Test
    fun burstOfMixedPrefixAndNextWordKeepsSingleFlightAndOnePendingSlot() {
        val executor = ManualEngineExecutor()
        val computed = mutableListOf<Pair<LookupKind, String>>()
        val published = mutableListOf<LookupResult>()
        val computer = object : PrefixComputer, NextWordComputer {
            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> {
                computed += LookupKind.PREFIX to normalizedPrefixUtf8.decodeUtf8()
                return listOf("prefix:${normalizedPrefixUtf8.decodeUtf8()}")
            }

            override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> {
                computed += LookupKind.NEXT_WORD to normalizedContextWordUtf8.decodeUtf8()
                return listOf("next:${normalizedContextWordUtf8.decodeUtf8()}")
            }
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity, computer, executor, ResultHandoff { published += it },
        )

        engine.request(1, "tt", utf8("а"))
        engine.requestNextWord(1, "tt", utf8("өй"))
        engine.request(1, "tt", utf8("аб"))
        val latest = requireNotNull(engine.requestNextWord(1, "tt", utf8("юл")))

        // Any interleaving of PREFIX and NEXT_WORD still yields at most one running and one
        // pending request — the single-flight/coalescing invariant does not depend on kind.
        assertEquals(1, executor.queueDepth)
        assertEquals(1, executor.maxQueueDepth)
        assertEquals(1, engine.readerCountForTest())
        assertTrue(engine.hasPendingForTest())

        executor.runAll()

        assertEquals(listOf(LookupKind.PREFIX to "а", LookupKind.NEXT_WORD to "юл"), computed)
        assertEquals(1, published.size)
        assertEquals(latest, published.single().token)
        assertEquals(listOf("next:юл"), published.single().suggestions)
        // Only "а" and "юл" ever reach a worker at all — "өй" and "аб" are overwritten in the
        // pending slot before being drained, exactly like the PREFIX-only burst test above. Of the
        // two drained requests, only "а" is stale by the time it finishes (the current token has
        // already moved on to "юл"), so suppressedStaleResultCount is 1, not the request count.
        assertEquals(1L, engine.suppressedStaleResultCount)
    }

    @Test
    fun concurrentBurstOfMixedKindsHasOneReaderAndOnlyLatestPendingComputation() {
        // The sequential test above shows the coalescing invariant does not depend on kind; this
        // is the same proof concurrentBurstHasOneReaderAndOnlyLatestPendingComputation gives for
        // PREFIX alone, but with real threads racing both request() and requestNextWord() against
        // each other — the interleaving a burst test is meant to catch cannot be forced by a
        // single caller issuing calls one at a time.
        val executor = ExecutorServiceEngineExecutor.singleThread()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val latestPublished = CountDownLatch(1)
        val activeComputers = AtomicInteger()
        val maximumActiveComputers = AtomicInteger()
        val computed = Collections.synchronizedList(mutableListOf<Pair<LookupKind, String>>())
        val published = Collections.synchronizedList(mutableListOf<LookupResult>())
        val computer = object : PrefixComputer, NextWordComputer {
            private fun compute(kind: LookupKind, value: String): List<String> {
                val active = activeComputers.incrementAndGet()
                maximumActiveComputers.accumulateAndGet(active, ::maxOf)
                try {
                    computed += kind to value
                    if (value == "first") {
                        firstEntered.countDown()
                        assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    }
                    return listOf("result:$value")
                } finally {
                    activeComputers.decrementAndGet()
                }
            }

            override fun lookup(normalizedPrefixUtf8: ImmutableUtf8Prefix): List<String> =
                compute(LookupKind.PREFIX, normalizedPrefixUtf8.decodeUtf8())

            override fun predict(normalizedContextWordUtf8: ImmutableUtf8Prefix): List<String> =
                compute(LookupKind.NEXT_WORD, normalizedContextWordUtf8.decodeUtf8())
        }
        val engine = LatestOnlyPrefixEngine(
            EngineTestFixtures.identity,
            computer,
            executor,
            ResultHandoff {
                published += it
                if (it.suggestions == listOf("result:latest")) latestPublished.countDown()
            },
        )
        engine.request(1, "tt", utf8("first"))
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val callers = (0 until 8).map { caller ->
            Thread({
                repeat(250) { request ->
                    // Even-numbered callers hammer PREFIX, odd-numbered hammer NEXT_WORD — both
                    // kinds racing the same currentToken/pendingRequest slot concurrently.
                    if (caller % 2 == 0) {
                        engine.request(caller.toLong(), "tt-$caller", utf8("$caller-$request"))
                    } else {
                        engine.requestNextWord(caller.toLong(), "tt-$caller", utf8("$caller-$request"))
                    }
                }
            }, "mixed-burst-$caller").apply(Thread::start)
        }
        callers.forEach { it.join(2_000) }
        assertTrue(callers.none(Thread::isAlive))
        val latest = requireNotNull(engine.requestNextWord(99, "tt", utf8("latest")))
        assertEquals(1, engine.readerCountForTest())
        assertTrue(engine.hasPendingForTest())

        releaseFirst.countDown()
        assertTrue(latestPublished.await(2, TimeUnit.SECONDS))

        assertEquals(listOf(LookupKind.PREFIX to "first", LookupKind.NEXT_WORD to "latest"), computed)
        assertEquals(listOf(latest), published.map { it.token })
        assertEquals(LookupKind.NEXT_WORD, published.single().kind)
        assertEquals(1, maximumActiveComputers.get())
        assertEquals(1L, engine.suppressedStaleResultCount)
        assertTrue(engine.destroy(2, TimeUnit.SECONDS))
    }

    private fun engine(
        executor: ManualEngineExecutor,
        computed: MutableList<String>,
        published: MutableList<LookupResult>,
    ) = LatestOnlyPrefixEngine(
        EngineTestFixtures.identity,
        PrefixComputer { prefix ->
            prefix.decodeUtf8().also { computed += it }.let { listOf("result:$it") }
        },
        executor,
        ResultHandoff { published += it },
    )

    private fun utf8(value: String) = value.toByteArray(Charsets.UTF_8)

    private class DirectEngineExecutor : EngineExecutor {
        private var shutdown = false

        override fun execute(command: Runnable) {
            check(!shutdown)
            command.run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
    }

    private class ThrowingEngineExecutor : EngineExecutor {
        override fun execute(command: Runnable) = throw AssertionError("execute")
        override fun shutdown() = Unit
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class InterruptingEngineExecutor : EngineExecutor {
        override fun execute(command: Runnable) = Unit
        override fun shutdown() = Unit

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            throw InterruptedException("test interrupt")
        }
    }
}

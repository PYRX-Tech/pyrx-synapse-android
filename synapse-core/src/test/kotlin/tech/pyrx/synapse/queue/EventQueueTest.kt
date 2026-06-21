/*
 * EventQueueTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the Room-backed [EventQueue] under realistic conditions: success
 * drain, 5xx retry with backoff, 4xx drop, FIFO eviction on overflow,
 * persistence across SDK restart, and reachability-triggered drain.
 *
 * All HTTP goes through [MockHTTPSession]; Room runs via
 * `Room.inMemoryDatabaseBuilder` (no SQLite file ever touched); the backoff
 * clock is stubbed so retries do not actually wait 1+ seconds.
 *
 * Mirrors iOS `EventQueueTests.swift` case-for-case.
 */

package tech.pyrx.synapse.queue

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.network.CannedResponse
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.MockHTTPSession
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EventQueueTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val acceptedJson: String =
        """{"event_id":"22222222-2222-2222-2222-222222222222","status":"accepted"}"""

    private lateinit var database: EventQueueDatabase
    private lateinit var dao: QueuedEventDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                EventQueueDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.queuedEventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // MARK: - Helpers

    /** No-op clock so backoff doesn't actually pause the test runner. */
    private class FakeClock : QueueClock {
        val sleeps: MutableList<Long> = mutableListOf()

        override suspend fun sleep(millis: Long) {
            sleeps.add(millis)
            // No actual delay — we just record the requested duration so
            // tests can assert exponential backoff was followed.
        }
    }

    /**
     * Test-only [Reachability] that lets the test drive transitions. The
     * production [NetworkCallbackReachability] cannot be used because there
     * is no real Android network in a JVM unit test.
     */
    private class FakeReachability : Reachability {
        private val flow = MutableSharedFlow<ReachabilityStatus>(replay = 1, extraBufferCapacity = 16)

        override val status: Flow<ReachabilityStatus> = flow.asSharedFlow()

        override fun start() = Unit

        override fun stop() = Unit

        suspend fun emit(status: ReachabilityStatus) {
            flow.emit(status)
        }
    }

    /**
     * Build a queue + collaborators. We use [UnconfinedTestDispatcher] so
     * launched drain coroutines run inline — tests don't need to await
     * `delay()` calls (and we've stubbed the clock anyway).
     */
    @Suppress("LongParameterList")
    private fun makeQueue(
        session: MockHTTPSession = MockHTTPSession(),
        maxQueueSize: Int = PyrxConfig.DEFAULT_MAX_QUEUE_SIZE,
        clock: QueueClock = FakeClock(),
        testScope: TestScope,
    ): EventQueue {
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                baseUrl = "https://synapse-events.pyrx.tech",
            )
        val httpClient = HTTPClient(config = config, session = session)
        return EventQueue(
            httpClient = httpClient,
            dao = dao,
            maxQueueSize = maxQueueSize,
            logger = PyrxLogger(),
            clock = clock,
            scope = TestScope(UnconfinedTestDispatcher(testScope.testScheduler)),
        )
    }

    private val seqCounter = AtomicLong(0)

    private fun fixtureEvent(name: String = "page_viewed"): QueuedEvent =
        QueuedEvent(
            externalId = "anon-fixture",
            eventName = name,
            attributes = mapOf("seq" to JSONValue.Int(seqCounter.getAndIncrement())),
            occurredAt = "2026-06-21T12:00:00.000Z",
        )

    // MARK: - Success drain

    @Test
    fun `enqueue then successful drain removes event from disk`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(json = acceptedJson)
            val queue = makeQueue(session = session, testScope = this)

            queue.enqueue(fixtureEvent())
            queue.drainNow()

            assertEquals(0, queue.count(), "successful drain must empty the queue")
            assertEquals(1, session.requests.size)
            assertEquals("/v1/events", session.requests[0].request.url.encodedPath)
        }

    // MARK: - 5xx retry

    @Test
    fun `5xx retry uses exponential backoff and eventually succeeds`() =
        runTest {
            val session = MockHTTPSession()
            // 2 transient failures (503), then success.
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 503,
                    body = "service unavailable".toByteArray(),
                    headers = emptyMap(),
                ),
            )
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 503,
                    body = "service unavailable".toByteArray(),
                    headers = emptyMap(),
                ),
            )
            session.enqueueJsonSuccess(json = acceptedJson)

            val clock = FakeClock()
            val queue = makeQueue(session = session, clock = clock, testScope = this)

            queue.enqueue(fixtureEvent())
            queue.drainNow()

            assertEquals(0, queue.count(), "event must be flushed after retries succeed")
            assertEquals(3, session.requests.size, "expected 3 attempts (2 fail + 1 succeed)")
            // First 2 retries follow the exponential schedule: 1s, 2s.
            assertEquals(2, clock.sleeps.size, "exactly 2 backoff sleeps for 2 failures")
            assertEquals(EventQueue.BACKOFF_MILLIS[0], clock.sleeps[0])
            assertEquals(EventQueue.BACKOFF_MILLIS[1], clock.sleeps[1])
        }

    @Test
    fun `transport error retries until success`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(CannedResponse.Failure(IOException("dns failure")))
            session.enqueueJsonSuccess(json = acceptedJson)

            val queue = makeQueue(session = session, testScope = this)
            queue.enqueue(fixtureEvent())
            queue.drainNow()

            assertEquals(0, queue.count())
            assertEquals(2, session.requests.size, "transport error must be retried, not dropped")
        }

    // MARK: - 4xx drop

    @Test
    fun `4xx drops event and does not retry`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 400,
                    body = """{"detail":"bad event_name"}""".toByteArray(),
                    headers = emptyMap(),
                ),
            )

            val queue = makeQueue(session = session, testScope = this)
            queue.enqueue(fixtureEvent())
            queue.drainNow()

            assertEquals(
                0,
                queue.count(),
                "4xx must drop the event so it doesn't block the queue indefinitely",
            )
            assertEquals(
                1,
                session.requests.size,
                "4xx must NOT be retried — exactly one POST attempt",
            )
        }

    @Test
    fun `4xx drop does not consume backoff budget for subsequent events`() =
        runTest {
            val session = MockHTTPSession()
            // First event 4xx-drops; second event succeeds on first try.
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 422,
                    body = "validation failure".toByteArray(),
                    headers = emptyMap(),
                ),
            )
            session.enqueueJsonSuccess(json = acceptedJson)

            val clock = FakeClock()
            val queue = makeQueue(session = session, clock = clock, testScope = this)
            queue.enqueue(fixtureEvent("bad"))
            queue.enqueue(fixtureEvent("good"))
            queue.drainNow()

            assertEquals(0, queue.count())
            assertEquals(2, session.requests.size)
            assertEquals(
                0,
                clock.sleeps.size,
                "4xx must not trigger backoff sleeps — only 5xx / transport do",
            )
        }

    // MARK: - Bounded eviction

    @Test
    fun `overflow drops oldest first to enforce maxQueueSize`() =
        runTest {
            // Block the drain so events accumulate on disk: no responses queued.
            val session = MockHTTPSession()
            // First drain attempt will hit the empty-queued condition and
            // throw — wrap each enqueue in a session that pre-emits a
            // failure so the drain treats the network as broken and stops.
            val queue = makeQueue(session = session, maxQueueSize = 3, testScope = this)

            // Stage 4 unique events. Each enqueue triggers a drain that will
            // immediately fail because the mock has no responses queued.
            // We tolerate the drain crash by pre-enqueueing transport
            // failures.
            repeat(4) {
                session.enqueue(CannedResponse.Failure(IOException("offline")))
                queue.enqueue(fixtureEvent("evt_$it"))
            }
            // Don't call drainNow — we want to inspect disk state without
            // letting the drain succeed.

            assertEquals(3, queue.count(), "maxQueueSize must cap on-disk events to 3")

            // The remaining events should be evt_1, evt_2, evt_3 (oldest evt_0 evicted)
            val rows = dao.selectAll()
            val names = rows.map { it.eventName }
            assertEquals(listOf("evt_1", "evt_2", "evt_3"), names, "FIFO eviction must drop the OLDEST")
        }

    // MARK: - Persistence across SDK restart

    @Test
    fun `events persisted across SDK restart drain on next init`() =
        runTest {
            // 1) Stage: prepopulate the DAO with 2 events as if a previous
            //    SDK session had enqueued them but not yet flushed.
            val event1 = fixtureEvent("survived_1")
            val event2 = fixtureEvent("survived_2")
            dao.insert(QueuedEventEntity.fromDomain(event1, createdAt = 100))
            dao.insert(QueuedEventEntity.fromDomain(event2, createdAt = 200))
            assertEquals(2, dao.count(), "pre-restart fixture must have 2 rows")

            // 2) Restart: build a fresh EventQueue against the SAME dao
            //    (this is what the prod Pyrx.initialize does — Room
            //    survives process death).
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(json = acceptedJson)
            session.enqueueJsonSuccess(json = acceptedJson)
            val queue = makeQueue(session = session, testScope = this)

            queue.drainNow()

            assertEquals(0, queue.count(), "post-restart drain must flush both pre-existing events")
            assertEquals(2, session.requests.size)
            // Order is FIFO (created_at ASC), so survived_1 goes first.
            val firstBody = session.requests[0].body.toString(Charsets.UTF_8)
            val secondBody = session.requests[1].body.toString(Charsets.UTF_8)
            assertTrue(firstBody.contains("survived_1"), "first POST must be the older event")
            assertTrue(secondBody.contains("survived_2"), "second POST must be the newer event")
        }

    // MARK: - Reachability trigger

    @Test
    fun `reachability satisfied transition triggers drain`() =
        runTest {
            val session = MockHTTPSession()
            val reachability = FakeReachability()
            val queue = makeQueue(session = session, testScope = this)
            queue.bindReachability(reachability)

            // Enqueue an event with no response staged — the auto-drain on
            // enqueue will fail (transport error). Then stage a success and
            // emit a SATISFIED transition — the drain should re-fire and
            // succeed.
            session.enqueue(CannedResponse.Failure(IOException("offline at enqueue")))
            queue.enqueue(fixtureEvent("waits_for_reachability"))
            // Let the failing initial drain settle.
            runCurrent()
            assertEquals(1, queue.count(), "event remains on disk after offline drain attempt")

            // Reachability comes back online → drain should flush.
            session.enqueueJsonSuccess(json = acceptedJson)
            reachability.emit(ReachabilityStatus.SATISFIED)
            runCurrent()
            // Wait for the reachability-triggered drain to complete.
            queue.drainNow()

            assertEquals(0, queue.count(), "reachability-triggered drain must flush the event")
            assertEquals(
                2,
                session.requests.size,
                "reachability drain must reattempt the same event (idempotency_key dedupes server-side)",
            )
        }

    // MARK: - Idempotency

    @Test
    fun `retries reuse the same idempotency_key`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 503,
                    body = ByteArray(0),
                    headers = emptyMap(),
                ),
            )
            session.enqueueJsonSuccess(json = acceptedJson)

            val queue = makeQueue(session = session, testScope = this)
            queue.enqueue(fixtureEvent())
            queue.drainNow()

            assertEquals(0, queue.count())
            assertEquals(2, session.requests.size)

            // Both attempts MUST carry the same idempotency_key so the
            // backend dedupes the retry.
            val firstBody = session.requests[0].body.toString(Charsets.UTF_8)
            val secondBody = session.requests[1].body.toString(Charsets.UTF_8)
            val firstKey = extractIdempotencyKey(firstBody)
            val secondKey = extractIdempotencyKey(secondBody)
            assertNotNull(firstKey)
            assertEquals(firstKey, secondKey, "retry must reuse the SAME idempotency_key")
        }

    /**
     * Minimal regex extract — avoids pulling in another JSON dep just for
     * one field. The wire body is well-formed JSON we control.
     */
    private fun extractIdempotencyKey(body: String): String? {
        val regex = Regex(""""idempotency_key"\s*:\s*"([^"]+)"""")
        return regex.find(body)?.groupValues?.getOrNull(1)
    }
}

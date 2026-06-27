/*
 * QueueDrainedFireTest.kt
 * PYRXSynapseTests — Android
 *
 * Phase 9.2.1 — verifies that EventQueue.drainLoop emits
 * PyrxEvent.QueueDrained with the correct count after a successful
 * batch flush, and does NOT emit when no events were flushed (no-op
 * drain).
 *
 * Uses the same Room-in-memory + MockHTTPSession harness as
 * EventQueueTest.
 */

package tech.pyrx.synapse.observer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.MockHTTPSession
import tech.pyrx.synapse.queue.EventQueue
import tech.pyrx.synapse.queue.EventQueueDatabase
import tech.pyrx.synapse.queue.QueueClock
import tech.pyrx.synapse.queue.QueuedEvent
import tech.pyrx.synapse.queue.QueuedEventDao
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class QueueDrainedFireTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val acceptedJson: String =
        """{"event_id":"22222222-2222-2222-2222-222222222222","status":"accepted"}"""

    private lateinit var database: EventQueueDatabase
    private lateinit var dao: QueuedEventDao

    private class FakeClock : QueueClock {
        override suspend fun sleep(millis: Long) = Unit
    }

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                EventQueueDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.queuedEventDao()
        Pyrx.resetForTesting()
    }

    @After
    fun tearDown() {
        Pyrx.resetForTesting()
        database.close()
    }

    private fun makeQueue(
        session: MockHTTPSession,
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
            maxQueueSize = 1000,
            logger = PyrxLogger(),
            clock = FakeClock(),
            // Bind the queue's background scope to the test's virtual
            // time so drainNow() actually completes inside runTest. This
            // is the same pattern EventQueueTest uses.
            scope = TestScope(UnconfinedTestDispatcher(testScope.testScheduler)),
        )
    }

    private fun event(name: String): QueuedEvent =
        QueuedEvent(
            externalId = "user_42",
            eventName = name,
            attributes = emptyMap<String, JSONValue>(),
            occurredAt = "2026-06-27T00:00:00.000Z",
        )

    @Test
    fun `drain emits QueueDrained with count equal to successfully flushed events`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = MockHTTPSession()
            // Three successful responses, one per enqueued event.
            session.enqueueJsonSuccess(json = acceptedJson)
            session.enqueueJsonSuccess(json = acceptedJson)
            session.enqueueJsonSuccess(json = acceptedJson)
            val queue = makeQueue(session, this)

            queue.enqueue(event("a"))
            queue.enqueue(event("b"))
            queue.enqueue(event("c"))
            queue.drainNow()

            // Inspect the replay cache directly — drainNow has returned
            // so the publishEvent has already fired (it's a synchronous
            // tryEmit). Avoid `.first { ... }` because `withTimeout`
            // inside `runTest` uses virtual time and the SharedFlow
            // suspension machinery interacts awkwardly with virtual time
            // when the value is already in replay.
            val drained =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.QueueDrained>()
                    .lastOrNull()
            assertNotNull(drained, "QueueDrained must be present in replay after successful drain")
            assertEquals(3, drained.count)
        }

    @Test
    fun `empty drain does NOT emit QueueDrained`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = MockHTTPSession()
            val queue = makeQueue(session, this)

            // Nothing enqueued.
            queue.drainNow()

            val drained =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.QueueDrained>()
                    .lastOrNull()
            assertNull(drained, "no-op drain (zero events) must NOT emit QueueDrained")
        }
}

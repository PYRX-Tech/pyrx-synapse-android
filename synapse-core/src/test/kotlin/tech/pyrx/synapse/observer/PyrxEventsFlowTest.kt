/*
 * PyrxEventsFlowTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the public `Pyrx.events` SharedFlow + `Pyrx.publishEvent`
 * cross-module seam. Covers the contract from the Phase 9.2.1 plan:
 *
 *   - SharedFlow semantics: multi-collector (every collector gets every
 *     event), replay buffer of 4 (late collectors see most-recent 4),
 *     BufferOverflow.DROP_OLDEST under fast emit (no deadlock).
 *   - Lifecycle cancellation: collecting from a scope and cancelling it
 *     stops collection without leaking.
 *   - Cross-module emit: publishEvent succeeds from any thread / scope.
 *
 * No Pyrx.initialize required — Pyrx.events + publishEvent are wired at
 * object construction (the MutableSharedFlow is a private field
 * initializer). Tests reset state via `Pyrx.resetForTesting()` to drop
 * any replay-cache leakage between tests.
 */

package tech.pyrx.synapse.observer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import tech.pyrx.synapse.Pyrx
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PyrxEventsFlowTest {
    @Before
    fun resetSdk() {
        Pyrx.resetForTesting()
    }

    @After
    fun teardown() {
        Pyrx.resetForTesting()
    }

    // MARK: - Helpers

    private fun pushReceived(
        id: String,
        title: String = "t",
    ): PyrxEvent.PushReceived =
        PyrxEvent.PushReceived(
            PushReceivedEvent(
                pushLogId = id,
                title = title,
                body = "",
                pyrxAttributes = emptyMap(),
                userInfo = emptyMap(),
                receivedAt = Instant.now(),
            ),
        )

    // MARK: - tryEmit / publishEvent

    @Test
    fun `publishEvent returns true under normal load`() {
        val ok = Pyrx.publishEvent(pushReceived("a"))
        assertTrue(ok, "tryEmit must succeed with default buffer sizing")
    }

    @Test
    fun `publishEvent does not throw and does not require coroutine context`() {
        // Calling from outside any coroutine — proves tryEmit is correctly
        // non-suspending and does not blow up on cold path.
        repeat(5) {
            assertTrue(Pyrx.publishEvent(pushReceived("event-$it")))
        }
    }

    // MARK: - Replay buffer

    @Test
    fun `late subscriber receives the most-recent 4 events from the replay buffer`() =
        runTest {
            // Publish 6 events with NO subscriber present.
            repeat(6) { i -> Pyrx.publishEvent(pushReceived("event-$i")) }

            // Subscribe AFTER the publishes; collect the next 4 emissions
            // from the replay cache + any in-flight.
            val received =
                withTimeout(2_000L) {
                    Pyrx.events.take(4).toList()
                }

            assertEquals(4, received.size, "replay buffer of 4 must replay 4")
            // Most recent 4 are event-2..event-5 (DROP_OLDEST evicted 0+1).
            val ids = received.map { (it as PyrxEvent.PushReceived).event.pushLogId }
            assertEquals(listOf("event-2", "event-3", "event-4", "event-5"), ids)
        }

    @Test
    fun `subscriber sees only the most-recent 4 even when many more were published`() =
        runTest {
            repeat(100) { i -> Pyrx.publishEvent(pushReceived("event-$i")) }

            val received =
                withTimeout(2_000L) {
                    Pyrx.events.take(4).toList()
                }
            val ids = received.map { (it as PyrxEvent.PushReceived).event.pushLogId }
            assertEquals(listOf("event-96", "event-97", "event-98", "event-99"), ids)
        }

    // MARK: - Multi-subscriber

    @Test
    fun `every collector gets every event when subscribed concurrently`() =
        runTest {
            // Two collectors, both subscribed before any emit.
            val collectorScopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val collectorScopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val a = mutableListOf<PyrxEvent>()
            val b = mutableListOf<PyrxEvent>()

            val jobA = collectorScopeA.launch { Pyrx.events.collect { a += it } }
            val jobB = collectorScopeB.launch { Pyrx.events.collect { b += it } }

            // Give the collectors a chance to subscribe before we publish.
            // SharedFlow.collect is asynchronous — without a yield the
            // publishes happen before the collectors have actually started
            // observing. We use a delay because the collectors are on
            // Dispatchers.Default and TestScope's virtual time doesn't
            // advance their clock.
            Thread.sleep(50)

            repeat(3) { i -> Pyrx.publishEvent(pushReceived("evt-$i")) }

            // Wait long enough for both collectors to observe.
            Thread.sleep(50)

            jobA.cancel()
            jobB.cancel()
            collectorScopeA.cancel()
            collectorScopeB.cancel()

            val aIds = a.filterIsInstance<PyrxEvent.PushReceived>().map { it.event.pushLogId }
            val bIds = b.filterIsInstance<PyrxEvent.PushReceived>().map { it.event.pushLogId }
            assertTrue(aIds.containsAll(listOf("evt-0", "evt-1", "evt-2")), "A missed events: $aIds")
            assertTrue(bIds.containsAll(listOf("evt-0", "evt-1", "evt-2")), "B missed events: $bIds")
        }

    // MARK: - Backpressure

    @Test
    fun `fast emit with DROP_OLDEST policy does not deadlock`() {
        // Fire many events synchronously from the same thread — no
        // subscriber attached. Without DROP_OLDEST + extraBufferCapacity,
        // a bounded SharedFlow would suspend or fail. With our config,
        // tryEmit returns true every time (oldest drops to make room).
        runBlocking {
            withTimeout(5_000L) {
                repeat(10_000) { i ->
                    Pyrx.publishEvent(pushReceived("burst-$i"))
                }
            }
        }
        // If we got here without timing out, DROP_OLDEST kept things flowing.
    }

    // MARK: - Cancellation

    @Test
    fun `cancelling a collector's scope stops collection without leaking`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val received = mutableListOf<PyrxEvent>()
            val job: Job = scope.launch { Pyrx.events.collect { received += it } }

            Thread.sleep(50)
            Pyrx.publishEvent(pushReceived("before-cancel"))
            Thread.sleep(50)

            scope.cancel()
            Thread.sleep(50)

            // Publish AFTER cancel — collector must NOT receive.
            Pyrx.publishEvent(pushReceived("after-cancel"))
            Thread.sleep(50)

            val ids = received.filterIsInstance<PyrxEvent.PushReceived>().map { it.event.pushLogId }
            assertTrue(ids.contains("before-cancel"))
            assertTrue(!ids.contains("after-cancel"), "cancelled scope must stop receiving")
            assertTrue(job.isCancelled, "the collect Job must be cancelled")
        }

    // MARK: - Cross-module emit (publishEvent seam)

    @Test
    fun `publishEvent from a different thread is observed by collectors`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val received = mutableListOf<PyrxEvent>()
            val job = scope.launch { Pyrx.events.collect { received += it } }

            Thread.sleep(50)

            // Publish from a fresh thread — proves the publish path doesn't
            // require coroutine context (mirrors the FCM service thread).
            val thread = Thread { Pyrx.publishEvent(pushReceived("cross-thread")) }
            thread.start()
            thread.join()

            Thread.sleep(50)

            job.cancel()
            scope.cancel()

            val ids = received.filterIsInstance<PyrxEvent.PushReceived>().map { it.event.pushLogId }
            assertTrue(ids.contains("cross-thread"), "publishEvent from a non-coroutine thread must reach collectors")
        }

    // MARK: - Exhaustiveness — every event subtype reachable

    @Test
    fun `all five PyrxEvent subtypes can be published and collected`() =
        runBlocking {
            // Reset to clear any replay-cache leakage from prior tests.
            Pyrx.resetForTesting()
            val all =
                listOf<PyrxEvent>(
                    PyrxEvent.PushReceived(
                        PushReceivedEvent("a", "t", "b", emptyMap(), emptyMap(), Instant.now()),
                    ),
                    PyrxEvent.PushClicked(
                        PushClickedEvent("b", null, null, emptyMap(), Instant.now()),
                    ),
                    PyrxEvent.PushReceivedColdStart(
                        PushReceivedEvent("c", "t", "b", emptyMap(), emptyMap(), Instant.now()),
                    ),
                    PyrxEvent.QueueDrained(count = 42),
                    PyrxEvent.IdentityChanged(
                        before = null,
                        after = IdentitySnapshot("ext", "anon", Instant.now()),
                    ),
                )

            for (e in all) Pyrx.publishEvent(e)

            // Replay cache holds the 4 most-recent — the first
            // (PushReceived) has been DROP_OLDEST'd. We deliberately only
            // assert the LAST four are reachable; the buffer-sizing test
            // above covers the eviction.
            val received = withTimeout(2_000L) { Pyrx.events.take(4).toList() }
            assertEquals(4, received.size)

            // The four most-recent (in order): PushClicked, PushReceivedColdStart,
            // QueueDrained, IdentityChanged.
            assertTrue(received[0] is PyrxEvent.PushClicked)
            assertTrue(received[1] is PyrxEvent.PushReceivedColdStart)
            assertTrue(received[2] is PyrxEvent.QueueDrained)
            assertTrue(received[3] is PyrxEvent.IdentityChanged)
        }

    // MARK: - QueueDrained payload

    @Test
    fun `QueueDrained carries the count`() =
        runBlocking {
            Pyrx.resetForTesting()
            Pyrx.publishEvent(PyrxEvent.QueueDrained(count = 17))
            val first = withTimeout(1_000L) { Pyrx.events.first() }
            assertTrue(first is PyrxEvent.QueueDrained)
            assertEquals(17, first.count)
        }
}

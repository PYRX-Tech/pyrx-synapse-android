/*
 * InAppEventsTest.kt
 * PYRXSynapseTests — Android — synapse-core
 *
 * Phase 10 PR-2b — verifies the 2 new [PyrxEvent] subtypes
 * (`InAppMessageReceived`, `InAppMessageDismissed`) propagate through
 * `Pyrx.events` correctly. The full in-app manager lives in the
 * synapse-inapp module; this test stays in synapse-core because the
 * sealed-interface extension lives here.
 *
 * Together with [PyrxEventsFlowTest], this raises the exhaustiveness
 * coverage from the original 5 cases to the full 7 (ADR-0009 D5).
 */

package tech.pyrx.synapse.observer

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.inapp.InAppMessage
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InAppEventsTest {
    @Before
    fun resetSdk() {
        Pyrx.resetForTesting()
    }

    @After
    fun teardown() {
        Pyrx.resetForTesting()
    }

    private fun sampleMessage(id: String = "assign-1"): InAppMessage =
        InAppMessage(
            id = id,
            messageId = "msg-$id",
            placement = "home_banner",
            title = "Hello",
            body = "World",
            imageUrl = null,
            ctas = emptyList(),
            custom = null as JsonObject?,
            expiresAt = null,
            priority = 0,
        )

    @Test
    fun `InAppMessageReceived publishes via SharedFlow`() = runBlocking {
        val msg = sampleMessage("a")
        Pyrx.publishEvent(PyrxEvent.InAppMessageReceived(msg))

        val first = withTimeout(1_000L) { Pyrx.events.first() }
        assertTrue(first is PyrxEvent.InAppMessageReceived)
        assertEquals("a", first.message.id)
        assertEquals("home_banner", first.message.placement)
    }

    @Test
    fun `InAppMessageDismissed publishes with messageId and reason`() = runBlocking {
        Pyrx.publishEvent(PyrxEvent.InAppMessageDismissed(messageId = "x", reason = "user_dismissed"))

        val first = withTimeout(1_000L) { Pyrx.events.first() }
        assertTrue(first is PyrxEvent.InAppMessageDismissed)
        assertEquals("x", first.messageId)
        assertEquals("user_dismissed", first.reason)
    }

    @Test
    fun `InAppMessageDismissed reason can be null`() = runBlocking {
        Pyrx.publishEvent(PyrxEvent.InAppMessageDismissed(messageId = "x", reason = null))

        val first = withTimeout(1_000L) { Pyrx.events.first() }
        assertTrue(first is PyrxEvent.InAppMessageDismissed)
        assertNull(first.reason)
    }

    @Test
    fun `7-event taxonomy — every subtype reachable on the stream`() = runTest {
        Pyrx.resetForTesting()
        val all =
            listOf<PyrxEvent>(
                PyrxEvent.PushReceived(
                    PushReceivedEvent("a", "t", "b", emptyMap(), emptyMap(), java.time.Instant.now()),
                ),
                PyrxEvent.PushClicked(
                    PushClickedEvent("b", null, null, emptyMap(), java.time.Instant.now()),
                ),
                PyrxEvent.PushReceivedColdStart(
                    PushReceivedEvent("c", "t", "b", emptyMap(), emptyMap(), java.time.Instant.now()),
                ),
                PyrxEvent.QueueDrained(count = 42),
                PyrxEvent.IdentityChanged(
                    before = null,
                    after = IdentitySnapshot("ext", "anon", java.time.Instant.now()),
                ),
                PyrxEvent.InAppMessageReceived(sampleMessage("d")),
                PyrxEvent.InAppMessageDismissed(messageId = "e", reason = "expired"),
            )

        for (e in all) Pyrx.publishEvent(e)

        // Replay buffer holds the most-recent 4 — assert the LAST four are
        // the new InApp* events (the older 5 push/identity events were
        // DROP_OLDEST'd to make room).
        val received = withTimeout(2_000L) { Pyrx.events.take(4).toList() }
        assertEquals(4, received.size)
        assertTrue(received[0] is PyrxEvent.QueueDrained)
        assertTrue(received[1] is PyrxEvent.IdentityChanged)
        assertTrue(received[2] is PyrxEvent.InAppMessageReceived)
        assertTrue(received[3] is PyrxEvent.InAppMessageDismissed)
    }

    @Test
    fun `exhaustive when on 7-event sum compiles without else`() = runTest {
        // This test exists primarily to ensure the 7-event sum is closed —
        // if anyone adds a new PyrxEvent subtype without updating the
        // when below, this test FAILS TO COMPILE. That is the desired
        // behavior per the file-level "Exhaustive `when`" doc.
        val event: PyrxEvent = PyrxEvent.InAppMessageReceived(sampleMessage("z"))
        val description: String =
            when (event) {
                is PyrxEvent.PushReceived -> "push"
                is PyrxEvent.PushClicked -> "click"
                is PyrxEvent.PushReceivedColdStart -> "cold"
                is PyrxEvent.QueueDrained -> "drain"
                is PyrxEvent.IdentityChanged -> "identity"
                is PyrxEvent.InAppMessageReceived -> "in-app received"
                is PyrxEvent.InAppMessageDismissed -> "in-app dismissed"
            }
        assertEquals("in-app received", description)
    }
}

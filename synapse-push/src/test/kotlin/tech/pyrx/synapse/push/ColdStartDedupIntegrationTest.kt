/*
 * ColdStartDedupIntegrationTest.kt
 * PYRXSynapseTests — Android
 *
 * Non-negotiable Risk Register item #1 invariant — exercised end-to-end
 * through `PushHandlers.handleNotificationTap` + the new
 * `Pyrx.shouldSuppressClickForColdStart` seam. Lives in synapse-push
 * (not synapse-core) because `PushHandlers` lives here; the dedup
 * registry itself lives in synapse-core's [tech.pyrx.synapse.observer
 * .ColdStartClickDedup] (already unit-tested in that module).
 *
 * Scenarios:
 *   1. handleNotificationTap with NO prior cold-start → PushClicked DOES fire.
 *   2. After Pyrx.recordColdStartAttribution emits PushReceivedColdStart
 *      for pushLogId X, a subsequent handleNotificationTap for the same
 *      pushLogId X must NOT emit PushClicked.
 *   3. After the suppression consumes the entry, a second tap of the
 *      same notification within the window legitimately fires PushClicked
 *      (the user re-tapped — the contract is "suppress the cold-start
 *      paired tap, not every subsequent tap").
 *   4. handleActionButton is never suppressed (action buttons cannot
 *      collide with the cold-start path).
 *
 * Uses Robolectric for the Android Context (Intent extras require it).
 * No FCM SDK booted — we construct PushHandlers directly with a
 * MockHTTPSession.
 */

package tech.pyrx.synapse.push

import android.content.Intent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.observer.PushReceivedEvent
import tech.pyrx.synapse.observer.PyrxEvent
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ColdStartDedupIntegrationTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val pushLogId: String = "9b1c8f4a-3a3e-4e1d-9b7f-1c2e3d4e5f6a"
    private val telemetryResponseJson: String =
        """{"status":"accepted","envelope_id":"77777777-7777-7777-7777-777777777777","reason":null}"""

    @Before
    fun setUp() {
        resetPyrxObserverState()
    }

    @After
    fun tearDown() {
        resetPyrxObserverState()
    }

    /**
     * Cross-module test seam. `Pyrx.resetForTesting` is `internal` to
     * synapse-core; this helper reaches the observer-state fields (the
     * SharedFlow's replay cache + the dedup map) via reflection so each
     * test starts with no leakage from the previous one. Limited to
     * test-only paths — never reachable from production.
     */
    private fun resetPyrxObserverState() {
        val pyrxClass = Pyrx::class.java
        // 1. Clear the dedup map. Internal methods get mangled with the
        // module suffix on the JVM; search for the mangled name.
        val dedupField = pyrxClass.getDeclaredField("coldStartDedup")
        dedupField.isAccessible = true
        val dedup = dedupField.get(Pyrx)
        val clearMethod =
            dedup.javaClass.declaredMethods.firstOrNull {
                it.name == "clear" || it.name.startsWith("clear$")
            } ?: error("ColdStartClickDedup.clear method not found")
        clearMethod.isAccessible = true
        clearMethod.invoke(dedup)
        // 2. Drop the replay cache so prior events don't leak.
        val flowField = pyrxClass.getDeclaredField("mutableEvents")
        flowField.isAccessible = true
        val flow = flowField.get(Pyrx) as kotlinx.coroutines.flow.MutableSharedFlow<*>
        kotlin.runCatching {
            val resetMethod = flow.javaClass.getMethod("resetReplayCache")
            resetMethod.invoke(flow)
        }
    }

    private fun makeHandlers(session: MockHTTPSession = MockHTTPSession()): PushHandlers {
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                baseUrl = "https://synapse-events.pyrx.tech",
            )
        val httpClient = HTTPClient(config = config, session = session)
        return PushHandlers(
            httpClient = httpClient,
            // trackProvider is a no-op — these tests focus on the observer path.
            trackProvider = { _, _ -> Unit },
        )
    }

    private fun tapIntent(): Intent =
        Intent().apply {
            putExtra(PushHandlers.KEY_PUSH_LOG_ID, pushLogId)
            putExtra(PushHandlers.KEY_DEEP_LINK, "pyrx://contacts/abc")
            putExtra("${PushHandlers.PREFIX_PYRX_ATTRS}campaign_id", "welcome-2026")
        }

    private fun makeReceivedEvent(): PushReceivedEvent =
        PushReceivedEvent(
            pushLogId = pushLogId,
            title = "T",
            body = "B",
            pyrxAttributes = mapOf("push_log_id" to JSONValue.Str(pushLogId)),
            userInfo = emptyMap(),
            receivedAt = Instant.now(),
        )

    // MARK: - 1. No cold-start → tap fires PushClicked

    @Test
    fun `handleNotificationTap fires PushClicked when no cold-start preceded`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(json = telemetryResponseJson)
            val handlers = makeHandlers(session)

            handlers.handleNotificationTap(tapIntent())

            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushClicked>()
                    .lastOrNull()
            assertNotNull(event)
            assertEquals(pushLogId, event.event.pushLogId)
            assertEquals("pyrx://contacts/abc", event.event.deepLink)
            assertNull(event.event.actionId, "body tap carries null actionId")
            assertEquals(
                JSONValue.Str("welcome-2026"),
                event.event.pyrxAttributes["campaign_id"],
                "pyrx_attrs_* keys must be projected into pyrxAttributes",
            )
        }

    // MARK: - 2. Cold-start preceding tap suppresses PushClicked

    @Test
    fun `handleNotificationTap is suppressed after PushReceivedColdStart for same pushLogId`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(json = telemetryResponseJson)
            val handlers = makeHandlers(session)

            // Simulate cold-start: record dedup + emit cold-start event.
            // (We use the same seam Pyrx.recordColdStartAttribution uses:
            // shouldSuppressClickForColdStart is the cross-module accessor.)
            // Reach the dedup register through publishEvent + the same
            // helper Pyrx itself calls.
            simulateColdStart()

            // Subsequent tap of the same pushLogId.
            handlers.handleNotificationTap(tapIntent())

            // We expect PushReceivedColdStart to be present, PushClicked
            // for this pushLogId to be ABSENT from the replay cache.
            val click =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushClicked>()
                    .firstOrNull { it.event.pushLogId == pushLogId }
            assertNull(
                click,
                "PushClicked for the cold-start pushLogId MUST be suppressed (Risk Register #1)",
            )

            // And the cold-start event itself is present.
            val cold =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushReceivedColdStart>()
                    .lastOrNull()
            assertNotNull(cold)
            assertEquals(pushLogId, cold.event.pushLogId)
        }

    // MARK: - 3. Second tap within the window legitimately fires

    @Test
    fun `second tap after cold-start consumption legitimately fires PushClicked`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(json = telemetryResponseJson)
            session.enqueueJsonSuccess(json = telemetryResponseJson)
            val handlers = makeHandlers(session)

            simulateColdStart()

            // First tap is suppressed (paired with cold-start).
            handlers.handleNotificationTap(tapIntent())
            val firstClickAttempt =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushClicked>()
                    .firstOrNull()
            assertNull(firstClickAttempt, "first paired tap must be suppressed")

            // Second tap of the same notification: the user re-tapped.
            // The dedup entry was consumed by the first call, so this
            // tap fires PushClicked normally.
            handlers.handleNotificationTap(tapIntent())
            val secondClick =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushClicked>()
                    .lastOrNull()
            assertNotNull(secondClick)
            assertEquals(pushLogId, secondClick.event.pushLogId)
        }

    // MARK: - 4. Action buttons never collide with cold-start

    @Test
    fun `handleActionButton always fires PushClicked even after cold-start`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(json = telemetryResponseJson)
            val handlers = makeHandlers(session)

            simulateColdStart()

            handlers.handleActionButton(intent = tapIntent(), actionId = "reply")

            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushClicked>()
                    .lastOrNull()
            assertNotNull(event)
            assertEquals(pushLogId, event.event.pushLogId)
            assertEquals("reply", event.event.actionId)
        }

    /**
     * Helper — simulates what `Pyrx.recordColdStartAttribution` does
     * with respect to the observer surface: registers the dedup AND
     * emits the cold-start event. We don't call `recordColdStartLaunch`
     * directly here because that requires Pyrx.initialize + EventsManager
     * wiring; the integration we're testing is "dedup correctly
     * suppresses the paired tap", which only needs the two observer
     * effects: register + emit.
     */
    private fun simulateColdStart() {
        // Register the dedup so the next tap is suppressed.
        // We reach the dedup through the same public seam PushHandlers
        // uses — via Pyrx. The Pyrx.publishEvent + dedup record are
        // INTERNAL to Pyrx.recordColdStartAttribution; to test
        // PushHandlers' dedup consultation in isolation we just need
        // the dedup state to be recorded. The simplest way is to call
        // Pyrx.shouldSuppressClickForColdStart's underlying register
        // through Pyrx itself — but that's a private method. Use the
        // public test-seam path: emit the cold-start event AND
        // independently prime the dedup by calling
        // recordColdStartAttribution? No — that requires Pyrx.initialize.
        //
        // Easiest test-honest path: use reflection on the Pyrx singleton
        // to call recordColdStart on the coldStartDedup field. Reflection
        // is a clear test-only hack and is bounded to this test.
        val pyrxClass = Pyrx::class.java
        val field = pyrxClass.getDeclaredField("coldStartDedup")
        field.isAccessible = true
        val dedup = field.get(Pyrx)
        // recordColdStart is a public method on ColdStartClickDedup —
        // no name mangling. Use getDeclaredMethod directly.
        val recordMethod = dedup.javaClass.getDeclaredMethod("recordColdStart", String::class.java)
        recordMethod.isAccessible = true
        recordMethod.invoke(dedup, pushLogId)

        // Also emit the cold-start observer event so tests that wait for
        // PushReceivedColdStart can succeed.
        Pyrx.publishEvent(PyrxEvent.PushReceivedColdStart(makeReceivedEvent()))
    }
}

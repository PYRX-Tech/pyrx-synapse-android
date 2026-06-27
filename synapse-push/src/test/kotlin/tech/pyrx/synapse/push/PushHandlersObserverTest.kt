/*
 * PushHandlersObserverTest.kt
 * PYRXSynapseTests — Android
 *
 * Phase 9.2.1 — verifies that every PushHandlers fire-point emits the
 * corresponding observer event with the correct payload shape.
 *
 * Covers:
 *   - recordPushReceived → PyrxEvent.PushReceived with pushLogId,
 *     pyrxAttributes, userInfo populated
 *   - recordPushReceived with no pyrx_push_log_id → NO observer event
 *   - handleNotificationTap → PyrxEvent.PushClicked with deepLink, null
 *     actionId, pyrx_attrs_* projected
 *   - handleActionButton → PyrxEvent.PushClicked with actionId set
 *
 * Cold-start dedup is covered separately in ColdStartDedupIntegrationTest.
 *
 * Mirrors the existing PushHandlersTest case-for-case for the analytics
 * path; this file adds the observer-side assertions.
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
import tech.pyrx.synapse.observer.PyrxEvent
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PushHandlersObserverTest {
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
     * Cross-module test seam — drops the SharedFlow replay cache + the
     * cold-start dedup map so each test starts clean. `Pyrx
     * .resetForTesting` is `internal` to synapse-core; reflection is
     * the cheapest way to reach the same state from a sibling-module
     * test. Test-only path.
     */
    private fun resetPyrxObserverState() {
        val pyrxClass = Pyrx::class.java
        val dedupField = pyrxClass.getDeclaredField("coldStartDedup")
        dedupField.isAccessible = true
        val dedup = dedupField.get(Pyrx)
        // Kotlin `internal` methods get a module-suffix mangled name on
        // the JVM (e.g. clear$synapse_core_debug / _release). Search the
        // declared methods for the mangled spelling so we don't break
        // across debug/release/staged module renames.
        val clearMethod =
            dedup.javaClass.declaredMethods.firstOrNull {
                it.name == "clear" || it.name.startsWith("clear$")
            } ?: error("ColdStartClickDedup.clear method not found")
        clearMethod.isAccessible = true
        clearMethod.invoke(dedup)
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

    // MARK: - recordPushReceived → PushReceived

    @Test
    fun `recordPushReceived emits PushReceived observer event with payload`() =
        runTest {
            val handlers = makeHandlers()

            handlers.recordPushReceived(
                mapOf(
                    PushHandlers.KEY_PUSH_LOG_ID to pushLogId,
                    "${PushHandlers.PREFIX_PYRX_ATTRS}campaign_id" to "welcome-2026",
                    "title" to "Hi",
                    "body" to "Tap to see",
                ),
            )

            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushReceived>()
                    .lastOrNull()
            assertNotNull(event, "PushReceived must be in replay cache after recordPushReceived")
            assertEquals(pushLogId, event.event.pushLogId)
            assertEquals("Hi", event.event.title)
            assertEquals("Tap to see", event.event.body)

            // pyrx_attrs_* projected (with prefix stripped)
            assertEquals(JSONValue.Str("welcome-2026"), event.event.pyrxAttributes["campaign_id"])
            // push_log_id always re-stamped
            assertEquals(JSONValue.Str(pushLogId), event.event.pyrxAttributes["push_log_id"])

            // userInfo carries the raw map (typed as JSONValue.Str)
            assertEquals(JSONValue.Str(pushLogId), event.event.userInfo[PushHandlers.KEY_PUSH_LOG_ID])
            assertEquals(JSONValue.Str("Hi"), event.event.userInfo["title"])

            assertNotNull(event.event.receivedAt, "receivedAt must be populated")
        }

    @Test
    fun `recordPushReceived without pyrx_push_log_id does not emit observer event`() =
        runTest {
            val handlers = makeHandlers()

            handlers.recordPushReceived(mapOf("foreign" to "value"))

            // No PushReceived should land in the replay cache.
            val event = Pyrx.events.replayCache.filterIsInstance<PyrxEvent.PushReceived>().firstOrNull()
            assertNull(
                event,
                "non-Synapse pushes (no pyrx_push_log_id) must NOT emit observer events",
            )
        }

    // MARK: - handleNotificationTap → PushClicked (body tap)

    @Test
    fun `handleNotificationTap emits PushClicked with null actionId for body tap`() =
        runTest {
            val session = MockHTTPSession().apply { enqueueJsonSuccess(json = telemetryResponseJson) }
            val handlers = makeHandlers(session)

            handlers.handleNotificationTap(tapIntent())

            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushClicked>()
                    .lastOrNull()
            assertNotNull(event, "PushClicked must be in replay cache after the tap")
            assertEquals(pushLogId, event.event.pushLogId)
            assertEquals("pyrx://contacts/abc", event.event.deepLink)
            assertNull(event.event.actionId, "body tap → null actionId")
            assertEquals(
                JSONValue.Str("welcome-2026"),
                event.event.pyrxAttributes["campaign_id"],
            )
            assertEquals(JSONValue.Str(pushLogId), event.event.pyrxAttributes["push_log_id"])
        }

    @Test
    fun `handleNotificationTap without pyrx_push_log_id does not emit observer event`() =
        runTest {
            val handlers = makeHandlers()

            handlers.handleNotificationTap(Intent().apply { putExtra("foreign", "value") })

            val event = Pyrx.events.replayCache.filterIsInstance<PyrxEvent.PushClicked>().firstOrNull()
            assertNull(event)
        }

    // MARK: - handleActionButton → PushClicked (action tap)

    @Test
    fun `handleActionButton emits PushClicked with actionId`() =
        runTest {
            val session = MockHTTPSession().apply { enqueueJsonSuccess(json = telemetryResponseJson) }
            val handlers = makeHandlers(session)

            handlers.handleActionButton(intent = tapIntent(), actionId = "reply")

            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushClicked>()
                    .lastOrNull()
            assertNotNull(event, "PushClicked must be in replay cache after the tap")
            assertEquals(pushLogId, event.event.pushLogId)
            assertEquals("reply", event.event.actionId)
            assertEquals("pyrx://contacts/abc", event.event.deepLink)
        }

    @Test
    fun `recordPushReceived deep-link absent yields null deepLink in observer event`() =
        runTest {
            val handlers = makeHandlers()

            // Push without deep link
            handlers.recordPushReceived(
                mapOf(PushHandlers.KEY_PUSH_LOG_ID to pushLogId),
            )

            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.PushReceived>()
                    .lastOrNull()
            assertNotNull(event, "PushReceived must be in replay cache after recordPushReceived")
            assertEquals("", event.event.title, "data-only push → empty title")
            assertEquals("", event.event.body, "data-only push → empty body")
        }
}

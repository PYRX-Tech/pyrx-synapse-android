/*
 * PyrxMessagingServiceTest.kt
 * PYRXSynapseTests — Android
 *
 * Smoke + wiring tests for the FCM service. The actual
 * `FirebaseMessagingService` lifecycle (Wakelock acquisition, intent
 * dispatch) is owned by Firebase and is verified by on-device QA in PR 7
 * — these tests cover the SDK-owned bits:
 *
 *   1. PyrxPush.install returns false when Pyrx hasn't been initialized.
 *   2. The bridge routes recordPushReceived through the trackProvider hook
 *      (proxy for onMessageReceived).
 *   3. The bridge routes handleNotificationTap into the push/opened endpoint
 *      (proxy for the Intent dispatch the host-app launcher Activity drives).
 *   4. The bridge routes handleActionButton into the push/click endpoint
 *      (proxy for the action-button PendingIntent the host wires up).
 *   5. PyrxMessagingService instantiates without throwing under Robolectric
 *      (catches accidental static-init crashes in PyrxPush / SynapsePushBridge).
 *
 * We do NOT instantiate or mock RemoteMessage directly — its constructor
 * is private and reflection-fragile. We do NOT exercise Pyrx.initialize
 * across modules (the test overload is internal to synapse-core); the
 * bridge-level tests above prove the SDK-side wire contract.
 *
 * Mirrors iOS `PushIntegrationTests.swift` shape (which also stops short
 * of mocking `UNNotificationResponse` directly — that's framework code).
 */

package tech.pyrx.synapse.push

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.WireEnvironment
import tech.pyrx.synapse.storage.InMemoryStorage
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PyrxMessagingServiceTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val pushLogId: String = "9b1c8f4a-3a3e-4e1d-9b7f-1c2e3d4e5f6a"

    @Before
    fun setUp() {
        PyrxPush.resetForTesting()
    }

    @After
    fun tearDown() {
        PyrxPush.resetForTesting()
    }

    // MARK: - PyrxPush.install lifecycle

    @Test
    fun `install returns false when Pyrx is not initialized`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val installed = PyrxPush.install(context)
        assertFalse(installed, "install must surface a clean false (not throw) when Pyrx is pending")
        assertNull(PyrxPush.installedBridge(), "no bridge should be cached when install fails")
    }

    // MARK: - bridge ↔ HTTP wire wiring

    @Test
    fun `bridge handleNotificationTap fires push opened with push log id`() =
        runTest {
            val session = MockHTTPSession()
            val bridge = buildBridge(session = session)
            session.enqueueJsonSuccess(
                json = """{"status":"accepted","envelope_id":"e1","reason":null}""",
            )

            val intent = android.content.Intent()
            intent.putExtra(PushHandlers.KEY_PUSH_LOG_ID, pushLogId)

            bridge.handleNotificationTap(intent)

            val recorded = session.requests[0].request
            assertEquals("/v1/push/opened", recorded.url.encodedPath)
            val body = Json.parseToJsonElement(session.requests[0].body.toString(Charsets.UTF_8)).jsonObject
            assertEquals(pushLogId, body["push_log_id"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `bridge handleActionButton fires push click with click url equal to actionId`() =
        runTest {
            val session = MockHTTPSession()
            val bridge = buildBridge(session = session)
            session.enqueueJsonSuccess(
                json = """{"status":"accepted","envelope_id":"e1","reason":null}""",
            )

            val intent = android.content.Intent()
            intent.putExtra(PushHandlers.KEY_PUSH_LOG_ID, pushLogId)

            bridge.handleActionButton(intent = intent, actionId = "snooze")

            val recorded = session.requests[0].request
            assertEquals("/v1/push/click", recorded.url.encodedPath)
            val body = Json.parseToJsonElement(session.requests[0].body.toString(Charsets.UTF_8)).jsonObject
            assertEquals("snooze", body["click_url"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `bridge recordPushReceived enqueues push_received via trackProvider`() =
        runTest {
            val tracked = mutableListOf<Pair<String, Map<String, JSONValue>?>>()
            val bridge =
                buildBridge(
                    trackProvider = { name, props -> tracked += name to props },
                )

            val ok =
                bridge.recordPushReceived(
                    mapOf(PushHandlers.KEY_PUSH_LOG_ID to pushLogId),
                )

            assertTrue(ok)
            assertEquals(PushHandlers.PUSH_RECEIVED_EVENT, tracked[0].first)
            val props = tracked[0].second
            assertNotNull(props)
            val attr = props[PushHandlers.KEY_ATTR_PUSH_LOG_ID] as? JSONValue.Str
            assertEquals(pushLogId, attr?.value)
        }

    // MARK: - PyrxMessagingService instantiation smoke

    @Test
    fun `PyrxMessagingService instantiates under Robolectric without crashing`() {
        // Robolectric.buildService constructs the service host so we can call
        // onCreate(). We're not testing FCM dispatch — only that our static
        // init / onCreate doesn't crash when invoked.
        val controller = Robolectric.buildService(PyrxMessagingService::class.java)
        val service = controller.create().get()
        // onCreate runs PyrxPush.install which returns false (Pyrx not
        // initialised here) — that's a clean no-op path, not a crash.
        assertNotNull(service, "PyrxMessagingService must construct cleanly")
    }

    // MARK: - Helpers

    /** Build a bridge directly (bypassing PyrxPush.install) for narrow tests. */
    private fun buildBridge(
        session: MockHTTPSession = MockHTTPSession(),
        trackProvider: suspend (String, Map<String, JSONValue>?) -> Unit = { _, _ -> },
    ): SynapsePushBridge {
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                baseUrl = "https://synapse-events.pyrx.tech",
            )
        val httpClient = HTTPClient(config = config, session = session)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = InMemoryStorage()
        val registration =
            PushRegistration(
                context = context,
                storage = storage,
                httpClient = httpClient,
                environment = WireEnvironment.LIVE,
            )
        val handlers =
            PushHandlers(
                httpClient = httpClient,
                trackProvider = trackProvider,
            )
        return SynapsePushBridge(registration = registration, handlers = handlers)
    }
}

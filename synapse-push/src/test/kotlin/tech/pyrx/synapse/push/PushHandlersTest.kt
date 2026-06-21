/*
 * PushHandlersTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the push telemetry handlers (Phase 8.4b Task 8.4b.8). All HTTP
 * goes through [MockHTTPSession]; the events queue is faked via a captured
 * `trackProvider` lambda so we don't need to spin up Room.
 *
 * Coverage:
 *
 *   1. handleNotificationTap — fires /v1/push/opened with push_log_id +
 *      occurred_at when the Intent carries pyrx_push_log_id.
 *   2. handleNotificationTap — no-op when the Intent has no pyrx_push_log_id.
 *   3. handleNotificationTap — no-op on malformed pyrx_push_log_id.
 *   4. handleActionButton — fires /v1/push/click with click_url = actionId.
 *   5. handleActionButton — no-op when the Intent has no pyrx_push_log_id.
 *   6. recordPushReceived — fires $push_received via trackProvider with
 *      push_log_id + pyrx_attrs_* keys.
 *   7. recordPushReceived — returns false when no pyrx_push_log_id present.
 *   8. UUID lowercase round-trip (cross-platform parity note).
 *
 * Mirrors iOS `PushHandlersTests.swift` semantics.
 */

package tech.pyrx.synapse.push

import android.content.Intent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.JSONValue
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PushHandlersTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val pushLogId: String = "9b1c8f4a-3a3e-4e1d-9b7f-1c2e3d4e5f6a"
    private val telemetryResponseJson: String =
        """
        {"status":"accepted","envelope_id":"77777777-7777-7777-7777-777777777777","reason":null}
        """.trimIndent()

    private data class TrackCall(
        val eventName: String,
        val properties: Map<String, JSONValue>?,
    )

    private data class Bench(
        val handlers: PushHandlers,
        val session: MockHTTPSession,
        val tracked: MutableList<TrackCall>,
    )

    private fun makeBench(session: MockHTTPSession = MockHTTPSession()): Bench {
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                baseUrl = "https://synapse-events.pyrx.tech",
            )
        val httpClient = HTTPClient(config = config, session = session)
        val tracked = mutableListOf<TrackCall>()
        val handlers =
            PushHandlers(
                httpClient = httpClient,
                trackProvider = { name, props -> tracked += TrackCall(name, props) },
            )
        return Bench(handlers = handlers, session = session, tracked = tracked)
    }

    private fun intentWith(vararg extras: Pair<String, String>): Intent {
        val intent = Intent()
        for ((k, v) in extras) intent.putExtra(k, v)
        return intent
    }

    private fun parseBody(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    // MARK: - handleNotificationTap → /v1/push/opened

    @Test
    fun `handleNotificationTap fires push opened with push_log_id`() =
        runTest {
            val bench = makeBench()
            bench.session.enqueueJsonSuccess(json = telemetryResponseJson)

            val intent = intentWith(PushHandlers.KEY_PUSH_LOG_ID to pushLogId)
            bench.handlers.handleNotificationTap(intent)

            val recorded = bench.session.requests[0].request
            assertEquals("/v1/push/opened", recorded.url.encodedPath)

            val body = parseBody(bench.session.requests[0].body)
            assertEquals(pushLogId, body["push_log_id"]?.jsonPrimitive?.contentOrNull)
            assertNotNull(
                body["occurred_at"]?.jsonPrimitive?.contentOrNull,
                "occurred_at must be present (server expects ISO-8601)",
            )
        }

    @Test
    fun `handleNotificationTap is a no-op when intent has no pyrx_push_log_id`() =
        runTest {
            val bench = makeBench()

            val intent = intentWith("unrelated" to "value")
            bench.handlers.handleNotificationTap(intent)

            assertTrue(
                bench.session.requests.isEmpty(),
                "no HTTP must be issued when the payload isn't a Synapse push",
            )
        }

    @Test
    fun `handleNotificationTap is a no-op on malformed UUID in pyrx_push_log_id`() =
        runTest {
            val bench = makeBench()

            val intent = intentWith(PushHandlers.KEY_PUSH_LOG_ID to "not-a-uuid")
            bench.handlers.handleNotificationTap(intent)

            assertTrue(
                bench.session.requests.isEmpty(),
                "malformed push_log_id must be dropped, not forwarded",
            )
        }

    // MARK: - handleActionButton → /v1/push/click

    @Test
    fun `handleActionButton fires push click with click_url equal to actionId`() =
        runTest {
            val bench = makeBench()
            bench.session.enqueueJsonSuccess(json = telemetryResponseJson)

            val intent = intentWith(PushHandlers.KEY_PUSH_LOG_ID to pushLogId)
            bench.handlers.handleActionButton(intent = intent, actionId = "reply")

            val recorded = bench.session.requests[0].request
            assertEquals("/v1/push/click", recorded.url.encodedPath)

            val body = parseBody(bench.session.requests[0].body)
            assertEquals(pushLogId, body["push_log_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("reply", body["click_url"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `handleActionButton is a no-op when intent has no pyrx_push_log_id`() =
        runTest {
            val bench = makeBench()

            val intent = intentWith("unrelated" to "value")
            bench.handlers.handleActionButton(intent = intent, actionId = "reply")

            assertTrue(bench.session.requests.isEmpty())
        }

    // MARK: - recordPushReceived → trackProvider

    @Test
    fun `recordPushReceived fires push_received via trackProvider with push_log_id`() =
        runTest {
            val bench = makeBench()

            val ok =
                bench.handlers.recordPushReceived(
                    mapOf(PushHandlers.KEY_PUSH_LOG_ID to pushLogId),
                )

            assertTrue(ok, "trackProvider must be invoked when push_log_id is present")
            assertEquals(1, bench.tracked.size, "exactly one track call expected")
            assertEquals(PushHandlers.PUSH_RECEIVED_EVENT, bench.tracked[0].eventName)
            val props = bench.tracked[0].properties
            assertNotNull(props)
            val pushLogIdAttr = props[PushHandlers.KEY_ATTR_PUSH_LOG_ID] as? JSONValue.Str
            assertNotNull(pushLogIdAttr)
            assertEquals(pushLogId, pushLogIdAttr.value)
        }

    @Test
    fun `recordPushReceived forwards pyrx_attrs underscore keys onto event properties`() =
        runTest {
            val bench = makeBench()

            val ok =
                bench.handlers.recordPushReceived(
                    mapOf(
                        PushHandlers.KEY_PUSH_LOG_ID to pushLogId,
                        "${PushHandlers.PREFIX_PYRX_ATTRS}campaign_id" to "abc",
                        "${PushHandlers.PREFIX_PYRX_ATTRS}variant" to "B",
                        "ignored_unrelated" to "x", // host-app extras are dropped
                    ),
                )

            assertTrue(ok)
            val props = bench.tracked[0].properties
            assertNotNull(props)
            // Stripped prefix landed as attribute keys
            assertEquals(JSONValue.Str("abc"), props["campaign_id"])
            assertEquals(JSONValue.Str("B"), props["variant"])
            // Unrelated extras NOT forwarded
            assertFalse(props.containsKey("ignored_unrelated"))
            // push_log_id always re-stamped
            assertEquals(JSONValue.Str(pushLogId), props[PushHandlers.KEY_ATTR_PUSH_LOG_ID])
        }

    @Test
    fun `recordPushReceived returns false and skips track when no pyrx_push_log_id`() =
        runTest {
            val bench = makeBench()

            val ok =
                bench.handlers.recordPushReceived(
                    mapOf("foreign" to "value"),
                )

            assertFalse(ok, "non-Synapse pushes must NOT enqueue \$push_received")
            assertTrue(bench.tracked.isEmpty(), "trackProvider must not be invoked")
        }

    // MARK: - cross-platform UUID note

    @Test
    fun `pushLogId parses UUID and stringifies to lowercase per JDK convention`() {
        val bench = makeBench()
        // Source payload carries UPPERCASE — JDK normalises to lowercase on parse.
        val upperCaseId = pushLogId.uppercase()
        val parsed = bench.handlers.pushLogId(mapOf(PushHandlers.KEY_PUSH_LOG_ID to upperCaseId))
        assertNotNull(parsed)
        // Java UUID.toString() — lowercase, with dashes. iOS Swift returns
        // uppercase. Backend must accept either (parity note in PR body).
        assertEquals(pushLogId, parsed, "JDK UUID.toString must be lowercase")
        assertEquals(parsed, parsed.lowercase())
    }
}

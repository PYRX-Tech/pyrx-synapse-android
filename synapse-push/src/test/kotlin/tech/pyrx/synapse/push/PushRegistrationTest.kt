/*
 * PushRegistrationTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the `/v1/devices` registration path end-to-end through the
 * [PushRegistration] class. All HTTP goes through [MockHTTPSession] —
 * `./gradlew :synapse-push:test` performs no real network I/O and no
 * Firebase SDK calls.
 *
 * Coverage:
 *
 *   1. register() — POSTs /v1/devices with platform=android + token + externalId
 *   2. register() — populates the full metadata snapshot (bundle / app
 *      version / OS / model / locale / timezone / sdk_*)
 *   3. register() — uses WireEnvironment.from(SANDBOX) as "test"
 *   4. register() — persists token to PyrxStorageKey.DEVICE_TOKEN
 *   5. register() — returns the server's DeviceResponse
 *   6. register() — rejects blank externalId
 *   7. register() — rejects blank token
 *
 * Mirrors iOS `PushRegistrationTests.swift` case-for-case.
 */

package tech.pyrx.synapse.push

import androidx.test.core.app.ApplicationProvider
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
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.WireEnvironment
import tech.pyrx.synapse.storage.InMemoryStorage
import tech.pyrx.synapse.storage.PyrxStorageKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PushRegistrationTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val deviceResponseJson: String =
        """
        {"id":"55555555-5555-5555-5555-555555555555",
        "contact_id":"66666666-6666-6666-6666-666666666666",
        "platform":"android","push_token":"fcm-token-xyz",
        "bundle_id":"tech.pyrx.synapse.push.test","app_version":"1.0.0",
        "sdk_version":"0.1.3","sdk_platform":"android","os_version":"Android 14",
        "device_model":"Google Pixel 8","locale":"en_US","timezone":"UTC",
        "environment":"live","push_enabled":true,
        "last_seen_at":"2026-06-21T00:00:00.000Z",
        "registered_at":"2026-06-21T00:00:00.000Z","revoked_at":null,
        "metadata":{}}
        """.trimIndent().replace("\n", "")

    private data class Bench(
        val registration: PushRegistration,
        val storage: InMemoryStorage,
        val session: MockHTTPSession,
    )

    private fun makeBench(
        environment: WireEnvironment = WireEnvironment.LIVE,
        storage: InMemoryStorage = InMemoryStorage(),
        session: MockHTTPSession = MockHTTPSession(),
        sdkVariant: String? = null,
    ): Bench {
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                baseUrl = "https://synapse-events.pyrx.tech",
            )
        val httpClient = HTTPClient(config = config, session = session)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val registration =
            PushRegistration(
                context = context,
                storage = storage,
                httpClient = httpClient,
                environment = environment,
                sdkVariant = sdkVariant,
            )
        return Bench(registration = registration, storage = storage, session = session)
    }

    private fun parseBody(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    // MARK: - happy-path wire body

    @Test
    fun `register POSTs devices endpoint with platform android and token`() =
        runTest {
            val bench = makeBench()
            bench.session.enqueueJsonSuccess(json = deviceResponseJson)

            val response = bench.registration.register(token = "fcm-token-xyz", externalId = "user_42")

            // Endpoint
            val recorded = bench.session.requests[0].request
            assertEquals("/v1/devices", recorded.url.encodedPath)

            // Body — required wire fields
            val body = parseBody(bench.session.requests[0].body)
            assertEquals("android", body["platform"]?.jsonPrimitive?.contentOrNull)
            assertEquals("fcm-token-xyz", body["push_token"]?.jsonPrimitive?.contentOrNull)
            assertEquals("user_42", body["external_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("live", body["environment"]?.jsonPrimitive?.contentOrNull)
            assertEquals(true, body["push_enabled"]?.jsonPrimitive?.content?.toBoolean())

            // Response surfaced unchanged
            assertEquals("55555555-5555-5555-5555-555555555555", response.id)
            assertEquals("66666666-6666-6666-6666-666666666666", response.contactId)
        }

    @Test
    fun `register populates the full metadata snapshot`() =
        runTest {
            val bench = makeBench()
            bench.session.enqueueJsonSuccess(json = deviceResponseJson)

            bench.registration.register(token = "fcm-token-xyz", externalId = "user_42")

            val body = parseBody(bench.session.requests[0].body)
            // Shape-only assertions (Robolectric values vary across host JDKs)
            assertNotNull(
                body["bundle_id"]?.jsonPrimitive?.contentOrNull,
                "bundle_id must be present",
            )
            assertNotNull(body["app_version"]?.jsonPrimitive?.contentOrNull, "app_version must be present")
            assertEquals("0.1.3", body["sdk_version"]?.jsonPrimitive?.contentOrNull)
            assertEquals("android", body["sdk_platform"]?.jsonPrimitive?.contentOrNull)
            val osVersion = body["os_version"]?.jsonPrimitive?.contentOrNull
            assertNotNull(osVersion)
            assertTrue(osVersion.startsWith("Android "), "os_version must start with 'Android '")
            assertNotNull(body["device_model"]?.jsonPrimitive?.contentOrNull, "device_model must be present")
            assertNotNull(body["locale"]?.jsonPrimitive?.contentOrNull, "locale must be present")
            assertNotNull(body["timezone"]?.jsonPrimitive?.contentOrNull, "timezone must be present")
        }

    @Test
    fun `register sends SANDBOX environment as test`() =
        runTest {
            val bench = makeBench(environment = WireEnvironment.TEST)
            bench.session.enqueueJsonSuccess(json = deviceResponseJson)

            bench.registration.register(token = "fcm-token-xyz", externalId = "user_42")

            val body = parseBody(bench.session.requests[0].body)
            assertEquals("test", body["environment"]?.jsonPrimitive?.contentOrNull)
        }

    // MARK: - persistence

    @Test
    fun `register persists token to DEVICE_TOKEN before the network call`() =
        runTest {
            val bench = makeBench()
            bench.session.enqueueJsonSuccess(json = deviceResponseJson)

            bench.registration.register(token = "fcm-token-xyz", externalId = "user_42")

            assertEquals("fcm-token-xyz", bench.storage.get(PyrxStorageKey.DEVICE_TOKEN))
        }

    @Test
    fun `register persists token even when the network call fails`() =
        runTest {
            val bench = makeBench()
            bench.session.enqueue(
                CannedResponse.Failure(java.io.IOException("kaboom")),
            )

            // Should throw, but token must already be persisted.
            assertFailsWith<PyrxError.Network> {
                bench.registration.register(token = "fcm-token-xyz", externalId = "user_42")
            }
            assertEquals(
                "fcm-token-xyz",
                bench.storage.get(PyrxStorageKey.DEVICE_TOKEN),
                "token must be persisted before the network call so debugInfo reports it accurately",
            )
        }

    // MARK: - validation

    @Test
    fun `register rejects blank externalId`() =
        runTest {
            val bench = makeBench()
            val err =
                assertFailsWith<PyrxError.InvalidConfig> {
                    bench.registration.register(token = "fcm-token-xyz", externalId = "   ")
                }
            assertEquals("externalId must not be empty", err.reason)
            assertTrue(bench.session.requests.isEmpty(), "no HTTP must be issued on validation failure")
        }

    @Test
    fun `register rejects blank token`() =
        runTest {
            val bench = makeBench()
            val err =
                assertFailsWith<PyrxError.InvalidConfig> {
                    bench.registration.register(token = "", externalId = "user_42")
                }
            assertEquals("token must not be empty", err.reason)
            assertTrue(bench.session.requests.isEmpty(), "no HTTP must be issued on validation failure")
        }

    // MARK: - sdkVariant on the wire

    @Test
    fun `register with sdkVariant sends suffixed sdk_platform`() =
        runTest {
            // Simulate the React Native wrapper passing its identifier
            // through PyrxConfig.sdkVariant ("rn") → PyrxPushHooks →
            // PushRegistration.
            val bench = makeBench(sdkVariant = "rn")
            bench.session.enqueueJsonSuccess(json = deviceResponseJson)

            bench.registration.register(token = "fcm-token-xyz", externalId = "user_42")

            val body = parseBody(bench.session.requests[0].body)
            // platform stays "android" (drives FCM dispatch); only
            // sdk_platform carries the wrapper marker.
            assertEquals("android", body["platform"]?.jsonPrimitive?.contentOrNull)
            assertEquals("android+rn", body["sdk_platform"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `register without sdkVariant sends bare sdk_platform`() =
        runTest {
            // Bare-Android regression: no variant → no suffix. Locks
            // behavior for every existing integration that pre-dates the
            // variant field.
            val bench = makeBench()
            bench.session.enqueueJsonSuccess(json = deviceResponseJson)

            bench.registration.register(token = "fcm-token-xyz", externalId = "user_42")

            val body = parseBody(bench.session.requests[0].body)
            assertEquals("android", body["sdk_platform"]?.jsonPrimitive?.contentOrNull)
        }
}

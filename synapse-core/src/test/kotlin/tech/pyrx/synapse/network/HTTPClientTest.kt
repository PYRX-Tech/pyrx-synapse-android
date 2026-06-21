/*
 * HTTPClientTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the wire-surface contract that iOS PR #2 owns:
 *
 *   1. Five required headers are present on every request
 *   2. JSON body is encoded with snake_case keys
 *   3. 2xx responses round-trip through the @Serializable types
 *   4. Non-2xx responses surface as PyrxError.Network(PyrxNetworkError.HttpStatus)
 *   5. Transport errors surface as PyrxError.Network(PyrxNetworkError.Transport)
 *   6. Decode errors surface as PyrxError.Network(PyrxNetworkError.Decode)
 *   7. Endpoint paths + header names are locked to the wire contract
 *
 * No real network — [MockHTTPSession] injects canned responses.
 */

package tech.pyrx.synapse.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxConstants
import tech.pyrx.synapse.PyrxEnvironment
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxNetworkError
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HTTPClientTest {
    // MARK: - Fixtures

    private val workspaceId: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val apiKey: String = "psk_test_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val baseUrl: String = "https://synapse-events.pyrx.tech"

    private fun makeClient(session: MockHTTPSession): HTTPClient {
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                environment = PyrxEnvironment.PRODUCTION,
                baseUrl = baseUrl,
            )
        return HTTPClient(config = config, session = session)
    }

    private fun parseBodyAsJsonObject(body: ByteArray): JsonObject {
        val text = body.toString(Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject
    }

    // MARK: - Header injection

    @Test
    fun `post injects all five required headers`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(
                json = """{"status":"accepted","envelope_id":null,"reason":null}""",
            )
            val client = makeClient(session)

            val body = PushOpenedRequest(pushLogId = UUID.randomUUID().toString())
            client.post(
                endpoint = HTTPClient.Endpoint.PUSH_OPENED,
                bodySerializer = PushOpenedRequest.serializer(),
                body = body,
                responseSerializer = PushTelemetryResponse.serializer(),
            )

            val recorded = session.requests
            assertEquals(1, recorded.size, "exactly one request should have been made")
            val headers = recorded[0].request.headers

            assertEquals(workspaceId.toString(), headers[HTTPClient.HeaderName.WORKSPACE_ID])
            assertEquals(apiKey, headers[HTTPClient.HeaderName.API_KEY])
            assertEquals(PyrxConstants.SDK_VERSION, headers[HTTPClient.HeaderName.SDK_VERSION])
            assertEquals("android", headers[HTTPClient.HeaderName.SDK_PLATFORM])
            assertEquals("application/json", headers[HTTPClient.HeaderName.CONTENT_TYPE])
        }

    @Test
    fun `post sets POST method and correct URL path`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(
                json =
                    """
                    {"contact_id":"22222222-2222-2222-2222-222222222222","path":"no_anonymous",
                    "aliased_external_id":null,"events_reattributed":0,"devices_reattributed":0,
                    "anonymous_contact_tombstoned":false}
                    """.trimIndent().replace("\n", ""),
            )
            val client = makeClient(session)

            val body = IdentifyRequest(anonymousId = null, externalId = "user_42")
            client.post(
                endpoint = HTTPClient.Endpoint.IDENTIFY,
                bodySerializer = IdentifyRequest.serializer(),
                body = body,
                responseSerializer = IdentifyResponse.serializer(),
            )

            val recorded = session.requests[0].request
            assertEquals("POST", recorded.method)
            assertEquals("/v1/identify", recorded.url.encodedPath)
            assertEquals("synapse-events.pyrx.tech", recorded.url.host)
        }

    // MARK: - Snake-case JSON body encoding

    @Test
    fun `identifyRequest encodes snake_case keys`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(
                json =
                    """
                    {"contact_id":"22222222-2222-2222-2222-222222222222","path":"first_sighting",
                    "aliased_external_id":"anon-xyz","events_reattributed":0,"devices_reattributed":0,
                    "anonymous_contact_tombstoned":false}
                    """.trimIndent().replace("\n", ""),
            )
            val client = makeClient(session)

            val body =
                IdentifyRequest(
                    anonymousId = "anon-xyz",
                    externalId = "user_42",
                    traits =
                        mapOf(
                            "email" to JSONValue.Str("a@b.co"),
                            "plan" to JSONValue.Str("pro"),
                        ),
                    environment = WireEnvironment.TEST,
                )
            client.post(
                endpoint = HTTPClient.Endpoint.IDENTIFY,
                bodySerializer = IdentifyRequest.serializer(),
                body = body,
                responseSerializer = IdentifyResponse.serializer(),
            )

            val raw = session.requests[0].body
            assertTrue(raw.isNotEmpty(), "request body must be captured")
            val json = parseBodyAsJsonObject(raw)
            assertEquals("anon-xyz", json["anonymous_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("user_42", json["external_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("test", json["environment"]?.jsonPrimitive?.contentOrNull)
            val traits = json["traits"]?.jsonObject
            assertNotNull(traits)
            assertEquals("a@b.co", traits["email"]?.jsonPrimitive?.contentOrNull)
            assertEquals("pro", traits["plan"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `aliasRequest encodes snake_case keys`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(
                json =
                    """
                    {"contact_id":"22222222-2222-2222-2222-222222222222","path":"known_exists",
                    "aliased_external_id":"anon-xyz","events_reattributed":3,"devices_reattributed":1,
                    "anonymous_contact_tombstoned":true}
                    """.trimIndent().replace("\n", ""),
            )
            val client = makeClient(session)

            val body = AliasRequest(anonymousId = "anon-xyz", externalId = "user_42")
            client.post(
                endpoint = HTTPClient.Endpoint.ALIAS,
                bodySerializer = AliasRequest.serializer(),
                body = body,
                responseSerializer = AliasResponse.serializer(),
            )

            val raw = session.requests[0].body
            val json = parseBodyAsJsonObject(raw)
            assertEquals("anon-xyz", json["anonymous_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("user_42", json["external_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("live", json["environment"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `identifyRequest omits null traits from wire body`() =
        runTest {
            // Matches iOS HTTPClientTests behaviour where Swift's Codable uses
            // encodeIfPresent for Optional and so nil optionals are omitted from
            // the JSON object. We mirror via Kotlin's `explicitNulls = false`.
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(
                json =
                    """
                    {"contact_id":"22222222-2222-2222-2222-222222222222","path":"no_anonymous",
                    "aliased_external_id":null,"events_reattributed":0,"devices_reattributed":0,
                    "anonymous_contact_tombstoned":false}
                    """.trimIndent().replace("\n", ""),
            )
            val client = makeClient(session)

            val body =
                IdentifyRequest(
                    anonymousId = null,
                    externalId = "user_42",
                    traits = null,
                )
            client.post(
                endpoint = HTTPClient.Endpoint.IDENTIFY,
                bodySerializer = IdentifyRequest.serializer(),
                body = body,
                responseSerializer = IdentifyResponse.serializer(),
            )

            val json = parseBodyAsJsonObject(session.requests[0].body)
            assertNull(json["traits"], "null traits must be omitted from the wire body")
            assertNull(json["anonymous_id"], "null anonymous_id must be omitted from the wire body")
        }

    // MARK: - Response decoding round-trip

    @Test
    fun `identifyResponse decodes all fields`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(
                json =
                    """
                    {"contact_id":"22222222-2222-2222-2222-222222222222","path":"known_exists",
                    "aliased_external_id":"anon-xyz","events_reattributed":47,"devices_reattributed":1,
                    "anonymous_contact_tombstoned":true}
                    """.trimIndent().replace("\n", ""),
            )
            val client = makeClient(session)

            val response =
                client.post(
                    endpoint = HTTPClient.Endpoint.IDENTIFY,
                    bodySerializer = IdentifyRequest.serializer(),
                    body = IdentifyRequest(anonymousId = "anon-xyz", externalId = "user_42"),
                    responseSerializer = IdentifyResponse.serializer(),
                )

            assertEquals("22222222-2222-2222-2222-222222222222", response.contactId)
            assertEquals(IdentifyPath.KNOWN_EXISTS, response.path)
            assertEquals("anon-xyz", response.aliasedExternalId)
            assertEquals(47, response.eventsReattributed)
            assertEquals(1, response.devicesReattributed)
            assertTrue(response.anonymousContactTombstoned)
        }

    // MARK: - Error mapping

    @Test
    fun `post surfaces HttpStatus on non-2xx`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 403,
                    body =
                        """{"detail":{"message":"forbidden","code":"scope_forbidden"}}"""
                            .toByteArray(Charsets.UTF_8),
                    headers = mapOf("Content-Type" to "application/json"),
                ),
            )
            val client = makeClient(session)

            val thrown =
                assertFailsWith<PyrxError.Network> {
                    client.post(
                        endpoint = HTTPClient.Endpoint.IDENTIFY,
                        bodySerializer = IdentifyRequest.serializer(),
                        body = IdentifyRequest(anonymousId = null, externalId = "user_42"),
                        responseSerializer = IdentifyResponse.serializer(),
                    )
                }
            val inner = thrown.inner
            assertTrue(inner is PyrxNetworkError.HttpStatus, "expected HttpStatus, got $inner")
            assertEquals(403, inner.statusCode)
            assertTrue(inner.body.isNotEmpty(), "response body bytes must be preserved")
        }

    @Test
    fun `post surfaces Transport on session IOException`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(CannedResponse.Failure(IOException("not connected to the internet")))
            val client = makeClient(session)

            val thrown =
                assertFailsWith<PyrxError.Network> {
                    client.post(
                        endpoint = HTTPClient.Endpoint.IDENTIFY,
                        bodySerializer = IdentifyRequest.serializer(),
                        body = IdentifyRequest(anonymousId = null, externalId = "user_42"),
                        responseSerializer = IdentifyResponse.serializer(),
                    )
                }
            val inner = thrown.inner
            assertTrue(inner is PyrxNetworkError.Transport, "expected Transport, got $inner")
            assertTrue(inner.underlying is IOException)
        }

    @Test
    fun `post surfaces Decode on malformed JSON`() =
        runTest {
            val session = MockHTTPSession()
            // Status is 200 but the body is not a valid IdentifyResponse — should
            // surface as a decode failure, not a status failure.
            session.enqueueJsonSuccess(json = """{"unexpected":"shape"}""")
            val client = makeClient(session)

            val thrown =
                assertFailsWith<PyrxError.Network> {
                    client.post(
                        endpoint = HTTPClient.Endpoint.IDENTIFY,
                        bodySerializer = IdentifyRequest.serializer(),
                        body = IdentifyRequest(anonymousId = null, externalId = "user_42"),
                        responseSerializer = IdentifyResponse.serializer(),
                    )
                }
            val inner = thrown.inner
            assertTrue(inner is PyrxNetworkError.Decode, "expected Decode, got $inner")
        }

    // MARK: - Endpoint coverage

    @Test
    fun `endpoint paths are locked to wire contract`() {
        // Triple-guard the path strings. Changing these is a wire-breaking
        // change that requires a coordinated iOS PR.
        assertEquals("/v1/devices", HTTPClient.Endpoint.DEVICES_REGISTER.path)
        assertEquals("/v1/identify", HTTPClient.Endpoint.IDENTIFY.path)
        assertEquals("/v1/alias", HTTPClient.Endpoint.ALIAS.path)
        assertEquals("/v1/events", HTTPClient.Endpoint.EVENTS.path)
        assertEquals("/v1/push/opened", HTTPClient.Endpoint.PUSH_OPENED.path)
        assertEquals("/v1/push/click", HTTPClient.Endpoint.PUSH_CLICK.path)
    }

    @Test
    fun `header names are locked to wire contract`() {
        assertEquals("X-WORKSPACE-ID", HTTPClient.HeaderName.WORKSPACE_ID)
        assertEquals("X-API-KEY", HTTPClient.HeaderName.API_KEY)
        assertEquals("X-PYRX-SDK-VERSION", HTTPClient.HeaderName.SDK_VERSION)
        assertEquals("X-PYRX-SDK-PLATFORM", HTTPClient.HeaderName.SDK_PLATFORM)
        assertEquals("Content-Type", HTTPClient.HeaderName.CONTENT_TYPE)
    }

    // MARK: - Void response variant

    @Test
    fun `postVoid succeeds on 2xx with empty body`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 202,
                    body = ByteArray(0),
                    headers = emptyMap(),
                ),
            )
            val client = makeClient(session)

            // Should not throw — we don't decode anything.
            client.postVoid(
                endpoint = HTTPClient.Endpoint.EVENTS,
                bodySerializer = EventIngestRequest.serializer(),
                body = EventIngestRequest(externalId = "user_42", eventName = "ping"),
            )

            assertEquals(1, session.requests.size)
        }

    @Test
    fun `postVoid throws on non-2xx`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 500,
                    body = "oops".toByteArray(Charsets.UTF_8),
                    headers = emptyMap(),
                ),
            )
            val client = makeClient(session)

            val thrown =
                assertFailsWith<PyrxError.Network> {
                    client.postVoid(
                        endpoint = HTTPClient.Endpoint.EVENTS,
                        bodySerializer = EventIngestRequest.serializer(),
                        body = EventIngestRequest(externalId = "user_42", eventName = "ping"),
                    )
                }
            val inner = thrown.inner
            assertTrue(inner is PyrxNetworkError.HttpStatus)
            assertEquals(500, inner.statusCode)
        }

    // MARK: - JSONValue round-trip

    @Test
    fun `JSONValue numeric types round-trip preserving Int vs Double`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(
                json =
                    """
                    {"contact_id":"22222222-2222-2222-2222-222222222222","path":"no_anonymous",
                    "aliased_external_id":null,"events_reattributed":0,"devices_reattributed":0,
                    "anonymous_contact_tombstoned":false}
                    """.trimIndent().replace("\n", ""),
            )
            val client = makeClient(session)

            val body =
                IdentifyRequest(
                    anonymousId = null,
                    externalId = "user_42",
                    traits =
                        mapOf(
                            "age" to JSONValue.Int(31),
                            "score" to JSONValue.Num(3.14),
                            "active" to JSONValue.Bool(true),
                            "tier" to JSONValue.Null,
                        ),
                )
            client.post(
                endpoint = HTTPClient.Endpoint.IDENTIFY,
                bodySerializer = IdentifyRequest.serializer(),
                body = body,
                responseSerializer = IdentifyResponse.serializer(),
            )

            val json = parseBodyAsJsonObject(session.requests[0].body)
            val traits = json["traits"]?.jsonObject
            assertNotNull(traits)
            assertEquals(31, traits["age"]?.jsonPrimitive?.intOrNull)
            assertEquals("3.14", traits["score"]?.jsonPrimitive?.contentOrNull)
            assertEquals(true, traits["active"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull())
        }
}

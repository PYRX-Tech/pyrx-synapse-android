/*
 * IdentityManagerTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the identity state machine (ARCHITECTURE.md §28.4 + push SDK
 * plan §5.3) end-to-end through the [IdentityManager]. All HTTP goes through
 * [MockHTTPSession] — `./gradlew test` performs no real network I/O.
 *
 * Coverage:
 *
 *   1. identify() — sends anonymousId + externalId, persists externalId
 *   2. identify() — carries traits when supplied
 *   3. identify() — handles all three merge paths
 *      (known_exists, first_sighting, no_anonymous)
 *   4. identify() — sends "test" when SDK is in SANDBOX
 *   5. identify() — rejects blank externalId
 *   6. alias() — sends both ids, persists newExternalId
 *   7. alias() — requires anonymousId on disk
 *   8. logout() — clears externalId, preserves anonymousId + deviceToken,
 *                 makes NO server call
 *
 * Mirrors iOS `IdentityManagerTests.swift` case-for-case.
 */

package tech.pyrx.synapse.identity

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxEnvironment
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.IdentifyPath
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.MockHTTPSession
import tech.pyrx.synapse.network.WireEnvironment
import tech.pyrx.synapse.storage.InMemoryStorage
import tech.pyrx.synapse.storage.PyrxStorageKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityManagerTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    // MARK: - Helpers

    /**
     * Bundles a fresh [IdentityManager] with the same [InMemoryStorage] +
     * [MockHTTPSession] it was constructed with — so tests can introspect
     * what landed where without juggling three separate variables at every
     * call site.
     */
    private data class Bench(
        val manager: IdentityManager,
        val storage: InMemoryStorage,
        val session: MockHTTPSession,
    )

    private fun makeBench(
        environment: PyrxEnvironment = PyrxEnvironment.PRODUCTION,
        storage: InMemoryStorage = InMemoryStorage(),
        session: MockHTTPSession = MockHTTPSession(),
        anonymousIdSeed: String = "fixture-anon",
    ): Bench {
        storage.set(PyrxStorageKey.ANONYMOUS_ID, anonymousIdSeed)
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                environment = environment,
                baseUrl = "https://synapse-events.pyrx.tech",
            )
        val httpClient = HTTPClient(config = config, session = session)
        val manager =
            IdentityManager(
                storage = storage,
                httpClient = httpClient,
                environment = WireEnvironment.from(environment),
                logger = PyrxLogger(),
            )
        return Bench(manager = manager, storage = storage, session = session)
    }

    /**
     * Default identify/alias response fixture. Tests that need to vary
     * `contactId` / `events` / `devices` / `tombstoned` can call
     * [MockHTTPSession.enqueueJsonSuccess] directly with a hand-rolled JSON
     * body — none of the current tests need those knobs, so we keep this
     * helper's surface small (stays under detekt's LongParameterList budget).
     */
    private fun enqueueIdentifyResponse(
        session: MockHTTPSession,
        path: String = "first_sighting",
        aliased: String? = "fixture-anon",
    ) {
        val aliasedField = aliased?.let { "\"$it\"" } ?: "null"
        session.enqueueJsonSuccess(
            json =
                """
                {"contact_id":"$DEFAULT_CONTACT_ID","path":"$path",
                "aliased_external_id":$aliasedField,
                "events_reattributed":0,"devices_reattributed":0,
                "anonymous_contact_tombstoned":false}
                """.trimIndent().replace("\n", ""),
        )
    }

    private companion object {
        private const val DEFAULT_CONTACT_ID: String = "22222222-2222-2222-2222-222222222222"
    }

    private fun parseBody(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    // MARK: - identify state transitions

    @Test
    fun `identify sends anonymousId and externalId and persists externalId`() =
        runTest {
            val bench = makeBench(anonymousIdSeed = "anon-fixture")
            enqueueIdentifyResponse(bench.session, path = "first_sighting", aliased = "anon-fixture")

            val result = bench.manager.identify(externalId = "user_42")

            // Server result surfaced
            assertEquals(IdentifyPath.FIRST_SIGHTING, result.path)
            assertEquals("anon-fixture", result.aliasedExternalId)

            // Wire body — both ids present + correct environment
            val raw = bench.session.requests[0].body
            val json = parseBody(raw)
            assertEquals("anon-fixture", json["anonymous_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("user_42", json["external_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("live", json["environment"]?.jsonPrimitive?.contentOrNull)
            assertNull(json["traits"], "null traits must be omitted from the wire body")

            // externalId persisted client-side; anonymousId still on disk (audit)
            assertEquals("user_42", bench.storage.get(PyrxStorageKey.EXTERNAL_ID))
            assertEquals("anon-fixture", bench.storage.get(PyrxStorageKey.ANONYMOUS_ID))
        }

    @Test
    fun `identify carries traits when provided`() =
        runTest {
            val bench = makeBench()
            enqueueIdentifyResponse(bench.session, path = "no_anonymous", aliased = null)

            bench.manager.identify(
                externalId = "user_42",
                traits =
                    mapOf(
                        "email" to JSONValue.Str("a@b.co"),
                        "age" to JSONValue.Int(31),
                    ),
            )

            val json = parseBody(bench.session.requests[0].body)
            val traits = json["traits"]?.jsonObject
            assertNotNull(traits)
            assertEquals("a@b.co", traits["email"]?.jsonPrimitive?.contentOrNull)
            assertEquals(31, traits["age"]?.jsonPrimitive?.intOrNull)
        }

    @Test
    fun `identify sends SANDBOX as test environment`() =
        runTest {
            val bench = makeBench(environment = PyrxEnvironment.SANDBOX)
            enqueueIdentifyResponse(bench.session, path = "no_anonymous", aliased = null)

            bench.manager.identify(externalId = "user_42")

            val json = parseBody(bench.session.requests[0].body)
            assertEquals("test", json["environment"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `identify handles all three merge paths`() =
        runTest {
            val cases =
                listOf(
                    "known_exists" to IdentifyPath.KNOWN_EXISTS,
                    "first_sighting" to IdentifyPath.FIRST_SIGHTING,
                    "no_anonymous" to IdentifyPath.NO_ANONYMOUS,
                )
            for ((pathString, expectedCase) in cases) {
                val bench = makeBench()
                enqueueIdentifyResponse(bench.session, path = pathString, aliased = null)

                val result = bench.manager.identify(externalId = "user_$pathString")
                assertEquals(expectedCase, result.path, "for path $pathString")
            }
        }

    @Test
    fun `identify rejects blank externalId`() =
        runTest {
            val bench = makeBench()

            val err =
                assertFailsWith<PyrxError.InvalidConfig> {
                    bench.manager.identify(externalId = "   ")
                }
            assertEquals("externalId must not be empty", err.reason)
        }

    @Test
    fun `identify with no anonymousId on disk sends null anonymous_id`() =
        runTest {
            // Construct manually so we can omit the anonymousId seed entirely
            val storage = InMemoryStorage()
            val session = MockHTTPSession()
            val config =
                PyrxConfig(
                    workspaceId = workspaceId,
                    apiKey = apiKey,
                    baseUrl = "https://synapse-events.pyrx.tech",
                )
            val manager =
                IdentityManager(
                    storage = storage,
                    httpClient = HTTPClient(config = config, session = session),
                    environment = WireEnvironment.LIVE,
                    logger = PyrxLogger(),
                )
            enqueueIdentifyResponse(session, path = "no_anonymous", aliased = null)

            manager.identify(externalId = "user_42")

            val json = parseBody(session.requests[0].body)
            assertNull(
                json["anonymous_id"],
                "null anonymous_id must be omitted from the wire body (matches iOS encodeIfPresent)",
            )
            assertEquals("user_42", json["external_id"]?.jsonPrimitive?.contentOrNull)
        }

    // MARK: - alias

    @Test
    fun `alias sends both ids and persists newExternalId`() =
        runTest {
            val bench = makeBench(anonymousIdSeed = "anon-fixture")
            enqueueIdentifyResponse(bench.session, path = "no_anonymous", aliased = "anon-fixture")

            val result = bench.manager.alias(newExternalId = "user_42")

            assertEquals(IdentifyPath.NO_ANONYMOUS, result.path)

            val json = parseBody(bench.session.requests[0].body)
            assertEquals("anon-fixture", json["anonymous_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("user_42", json["external_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("live", json["environment"]?.jsonPrimitive?.contentOrNull)

            // newExternalId persisted
            assertEquals("user_42", bench.storage.get(PyrxStorageKey.EXTERNAL_ID))
        }

    @Test
    fun `alias targets alias endpoint`() =
        runTest {
            val bench = makeBench()
            enqueueIdentifyResponse(bench.session, path = "known_exists", aliased = "fixture")

            bench.manager.alias(newExternalId = "user_42")

            val recorded = bench.session.requests[0].request
            assertEquals("/v1/alias", recorded.url.encodedPath)
        }

    @Test
    fun `alias rejects blank newExternalId`() =
        runTest {
            val bench = makeBench()

            val err =
                assertFailsWith<PyrxError.InvalidConfig> {
                    bench.manager.alias(newExternalId = "")
                }
            assertEquals("newExternalId must not be empty", err.reason)
        }

    @Test
    fun `alias requires anonymousId on disk`() =
        runTest {
            // Build a bench then erase the anonymousId
            val bench = makeBench()
            bench.storage.delete(PyrxStorageKey.ANONYMOUS_ID)

            assertFailsWith<PyrxError.NotInitialized> {
                bench.manager.alias(newExternalId = "user_42")
            }

            // No HTTP call should have been issued
            assertTrue(
                bench.session.requests.isEmpty(),
                "alias must not issue a request when anonymousId is missing",
            )
        }

    // MARK: - logout

    @Test
    fun `logout clears externalId but preserves anonymousId and deviceToken`() =
        runTest {
            val storage = InMemoryStorage()
            // Pre-populate as if PR 4 had registered a device.
            storage.set(PyrxStorageKey.DEVICE_TOKEN, "fixture-device-token")

            val bench = makeBench(storage = storage, anonymousIdSeed = "anon-after-init")
            val anonAfterInit = bench.storage.get(PyrxStorageKey.ANONYMOUS_ID)
            assertNotNull(anonAfterInit)

            // Identify once so externalId is on disk
            enqueueIdentifyResponse(bench.session, path = "first_sighting", aliased = anonAfterInit)
            bench.manager.identify(externalId = "user_42")
            assertEquals("user_42", bench.storage.get(PyrxStorageKey.EXTERNAL_ID))

            // Logout
            bench.manager.logout()

            assertNull(
                bench.storage.get(PyrxStorageKey.EXTERNAL_ID),
                "externalId must be cleared",
            )
            assertEquals(
                anonAfterInit,
                bench.storage.get(PyrxStorageKey.ANONYMOUS_ID),
                "anonymousId must be preserved across logout",
            )
            assertEquals(
                "fixture-device-token",
                bench.storage.get(PyrxStorageKey.DEVICE_TOKEN),
                "deviceToken must be preserved across logout (device row stays valid)",
            )

            // logout() does NOT call the server — no new HTTP request beyond identify
            assertEquals(
                1,
                bench.session.requests.size,
                "logout must not call the server",
            )
        }
}

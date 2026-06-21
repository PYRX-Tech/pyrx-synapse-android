/*
 * EventsManagerTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the public events surface ([EventsManager.track] /
 * [EventsManager.screen]) end-to-end through the queue + Room (via Room's
 * `inMemoryDatabaseBuilder`) + a mock HTTPSession. All HTTP goes through
 * [MockHTTPSession] — `./gradlew test` performs no real network I/O.
 *
 * Coverage:
 *
 *   1. track() — enqueues with correct wire body (event_name / external_id /
 *      idempotency_key / occurred_at present and well-formed)
 *   2. track() — uses externalId when identify() has been called
 *   3. track() — falls back to anonymousId when no externalId on disk
 *   4. track() — rejects blank eventName
 *   5. screen() — produces event_name="$screen" + attributes.screen_name
 *   6. screen() — caller properties are preserved alongside screen_name
 *   7. screen() — caller cannot spoof screen_name through properties
 *   8. screen() — rejects blank screenName
 *
 * Mirrors iOS `EventsManagerTests.swift` case-for-case.
 */

package tech.pyrx.synapse.events

import androidx.room.Room
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.MockHTTPSession
import tech.pyrx.synapse.queue.EventQueue
import tech.pyrx.synapse.queue.EventQueueDatabase
import tech.pyrx.synapse.queue.QueuedEventDao
import tech.pyrx.synapse.storage.InMemoryStorage
import tech.pyrx.synapse.storage.PyrxStorageKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EventsManagerTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val acceptedJson: String = """{"event_id":"22222222-2222-2222-2222-222222222222","status":"accepted"}"""

    private lateinit var database: EventQueueDatabase
    private lateinit var dao: QueuedEventDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                EventQueueDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.queuedEventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // MARK: - Helpers

    private data class Bench(
        val manager: EventsManager,
        val storage: InMemoryStorage,
        val session: MockHTTPSession,
        val queue: EventQueue,
    )

    /**
     * Bundles a fresh [EventsManager] with all collaborators wired the same
     * way the production [tech.pyrx.synapse.Pyrx.initialize] would — except
     * Room is in-memory and HTTP goes through [MockHTTPSession].
     */
    private fun makeBench(
        anonymousId: String = "fixture-anon",
        storage: InMemoryStorage = InMemoryStorage(),
        session: MockHTTPSession = MockHTTPSession(),
        maxQueueSize: Int = PyrxConfig.DEFAULT_MAX_QUEUE_SIZE,
    ): Bench {
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                baseUrl = "https://synapse-events.pyrx.tech",
            )
        val httpClient = HTTPClient(config = config, session = session)
        val queue =
            EventQueue(
                httpClient = httpClient,
                dao = dao,
                maxQueueSize = maxQueueSize,
                logger = PyrxLogger(),
            )
        val manager =
            EventsManager(
                queue = queue,
                storage = storage,
                anonymousId = anonymousId,
                logger = PyrxLogger(),
            )
        return Bench(manager = manager, storage = storage, session = session, queue = queue)
    }

    private fun parseBody(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    // MARK: - track

    @Test
    fun `track enqueues event with correct wire body`() =
        runTest {
            val bench = makeBench(anonymousId = "anon-fixture")
            bench.session.enqueueJsonSuccess(json = acceptedJson)

            bench.manager.track(
                eventName = "page_viewed",
                properties = mapOf("path" to JSONValue.Str("/home")),
            )
            bench.queue.drainNow()

            assertEquals(1, bench.session.requests.size)
            val recorded = bench.session.requests[0]
            assertEquals("/v1/events", recorded.request.url.encodedPath)

            val json = parseBody(recorded.body)
            assertEquals("page_viewed", json["event_name"]?.jsonPrimitive?.contentOrNull)
            assertEquals("anon-fixture", json["external_id"]?.jsonPrimitive?.contentOrNull)
            val idempotencyKey = json["idempotency_key"]?.jsonPrimitive?.contentOrNull
            assertNotNull(idempotencyKey, "idempotency_key must be present on the wire")
            assertTrue(idempotencyKey.isNotEmpty())
            val occurredAt = json["occurred_at"]?.jsonPrimitive?.contentOrNull
            assertNotNull(occurredAt, "occurred_at must be present on the wire")
            // ISO-8601 sanity — yyyy-MM-ddTHH:mm:ss.SSSZ
            assertTrue(
                occurredAt.matches(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""")),
                "occurred_at must be ISO-8601 with ms precision, was $occurredAt",
            )
            val attributes = json["attributes"]?.jsonObject
            assertNotNull(attributes)
            assertEquals("/home", attributes["path"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `track uses externalId when identify has been called`() =
        runTest {
            val storage = InMemoryStorage()
            storage.set(PyrxStorageKey.EXTERNAL_ID, "user_42")
            val bench = makeBench(anonymousId = "anon-fixture", storage = storage)
            bench.session.enqueueJsonSuccess(json = acceptedJson)

            bench.manager.track(eventName = "page_viewed")
            bench.queue.drainNow()

            val json = parseBody(bench.session.requests[0].body)
            assertEquals(
                "user_42",
                json["external_id"]?.jsonPrimitive?.contentOrNull,
                "track must prefer externalId over anonymousId when both are present",
            )
        }

    @Test
    fun `track falls back to anonymousId when no externalId on disk`() =
        runTest {
            val bench = makeBench(anonymousId = "anon-fixture")
            bench.session.enqueueJsonSuccess(json = acceptedJson)

            bench.manager.track(eventName = "page_viewed")
            bench.queue.drainNow()

            val json = parseBody(bench.session.requests[0].body)
            assertEquals("anon-fixture", json["external_id"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `track rejects blank eventName`() =
        runTest {
            val bench = makeBench()
            val err =
                assertFailsWith<PyrxError.InvalidConfig> {
                    bench.manager.track(eventName = "   ")
                }
            assertEquals("eventName must not be empty", err.reason)
            assertTrue(
                bench.session.requests.isEmpty(),
                "blank eventName must not enqueue or POST anything",
            )
        }

    @Test
    fun `track throws NotInitialized when neither externalId nor anonymousId resolvable`() =
        runTest {
            val bench = makeBench(anonymousId = "")
            assertFailsWith<PyrxError.NotInitialized> {
                bench.manager.track(eventName = "page_viewed")
            }
        }

    // MARK: - screen

    @Test
    fun `screen produces dollar-screen event_name and screen_name attribute`() =
        runTest {
            val bench = makeBench(anonymousId = "anon-fixture")
            bench.session.enqueueJsonSuccess(json = acceptedJson)

            bench.manager.screen(screenName = "Home")
            bench.queue.drainNow()

            val json = parseBody(bench.session.requests[0].body)
            assertEquals(
                "\$screen",
                json["event_name"]?.jsonPrimitive?.contentOrNull,
                "screen() must emit the canonical \$screen event_name",
            )
            val attributes = json["attributes"]?.jsonObject
            assertNotNull(attributes)
            assertEquals("Home", attributes["screen_name"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `screen merges caller properties alongside screen_name`() =
        runTest {
            val bench = makeBench(anonymousId = "anon-fixture")
            bench.session.enqueueJsonSuccess(json = acceptedJson)

            bench.manager.screen(
                screenName = "Home",
                properties =
                    mapOf(
                        "referrer" to JSONValue.Str("nav"),
                        "tab_index" to JSONValue.Int(0),
                    ),
            )
            bench.queue.drainNow()

            val attributes = parseBody(bench.session.requests[0].body)["attributes"]?.jsonObject
            assertNotNull(attributes)
            assertEquals("Home", attributes["screen_name"]?.jsonPrimitive?.contentOrNull)
            assertEquals("nav", attributes["referrer"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `screen does not allow caller to spoof screen_name`() =
        runTest {
            val bench = makeBench(anonymousId = "anon-fixture")
            bench.session.enqueueJsonSuccess(json = acceptedJson)

            bench.manager.screen(
                screenName = "ActualScreen",
                properties = mapOf("screen_name" to JSONValue.Str("SpoofedScreen")),
            )
            bench.queue.drainNow()

            val attributes = parseBody(bench.session.requests[0].body)["attributes"]?.jsonObject
            assertNotNull(attributes)
            assertEquals(
                "ActualScreen",
                attributes["screen_name"]?.jsonPrimitive?.contentOrNull,
                "SDK-stamped screen_name must win over caller properties",
            )
        }

    @Test
    fun `screen rejects blank screenName`() =
        runTest {
            val bench = makeBench()
            val err =
                assertFailsWith<PyrxError.InvalidConfig> {
                    bench.manager.screen(screenName = "")
                }
            assertEquals("screenName must not be empty", err.reason)
        }
}

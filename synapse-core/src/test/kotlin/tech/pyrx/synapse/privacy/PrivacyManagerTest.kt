/*
 * PrivacyManagerTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the three privacy surfaces in [PrivacyManager]:
 *
 *   1. setTrackingEnabled(false) → drain refuses to send; enqueue still
 *      persists. setTrackingEnabled(true) re-kicks the drain.
 *   2. deleteUser() wipes storage + queue BEFORE the backend call; 4xx
 *      from the backend is swallowed; 5xx propagates as PyrxError.Network.
 *   3. contactsDeletePath() URL-encodes the external_id segment.
 *
 * No real Firebase / network — [MockHTTPSession] injects canned responses;
 * Room runs via `Room.inMemoryDatabaseBuilder`.
 *
 * Mirrors iOS `PrivacyManagerTests.swift` case-for-case where the iOS
 * test set covers the same surface.
 */

package tech.pyrx.synapse.privacy

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxEnvironment
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.PyrxNetworkError
import tech.pyrx.synapse.network.CannedResponse
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.MockHTTPSession
import tech.pyrx.synapse.queue.EventQueue
import tech.pyrx.synapse.queue.EventQueueDatabase
import tech.pyrx.synapse.queue.QueueClock
import tech.pyrx.synapse.queue.QueuedEvent
import tech.pyrx.synapse.queue.QueuedEventDao
import tech.pyrx.synapse.storage.InMemoryStorage
import tech.pyrx.synapse.storage.PyrxStorageKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PrivacyManagerTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_live_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

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

    // MARK: - Fixtures

    /** No-op clock so backoff doesn't actually pause the test. */
    private class FakeClock : QueueClock {
        override suspend fun sleep(millis: Long) {
            // record nothing; the queue tests already cover backoff timing
        }
    }

    private fun makeClient(session: MockHTTPSession): HTTPClient {
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                environment = PyrxEnvironment.PRODUCTION,
                baseUrl = "https://synapse-events.pyrx.tech",
            )
        return HTTPClient(config = config, session = session)
    }

    private fun makeQueue(client: HTTPClient): EventQueue =
        EventQueue(
            httpClient = client,
            dao = dao,
            maxQueueSize = 100,
            logger = PyrxLogger(),
            clock = FakeClock(),
        )

    private fun makeQueuedEvent(externalId: String = "user_42"): QueuedEvent =
        QueuedEvent(
            externalId = externalId,
            eventName = "test_event",
            attributes = mapOf("k" to JSONValue.Str("v")),
            occurredAt = "2026-06-21T00:00:00.000Z",
        )

    // MARK: - Path encoding

    @Test
    fun `contactsDeletePath URL-encodes the external_id segment`() {
        // Simple id round-trips unchanged.
        assertEquals(
            "/v1/contacts/user_42/delete",
            PrivacyManager.contactsDeletePath("user_42"),
        )
        // Plus → percent-encoded (would otherwise be ambiguous as a space).
        assertEquals(
            "/v1/contacts/user%2Bplus/delete",
            PrivacyManager.contactsDeletePath("user+plus"),
        )
        // Slash → percent-encoded (would otherwise be a new path segment).
        assertEquals(
            "/v1/contacts/user%2Fwith%2Fslash/delete",
            PrivacyManager.contactsDeletePath("user/with/slash"),
        )
    }

    // MARK: - Tracking gate

    @Test
    fun `setTrackingEnabled false prevents drain from sending events`() =
        runTest {
            val session = MockHTTPSession()
            val client = makeClient(session)
            val queue = makeQueue(client)
            val storage = InMemoryStorage()
            val privacy =
                PrivacyManager(
                    storage = storage,
                    queue = queue,
                    httpClient = client,
                    appContext = ApplicationProvider.getApplicationContext(),
                    logger = PyrxLogger(),
                )

            // Disable tracking BEFORE enqueueing.
            privacy.setTrackingEnabled(false)

            // Enqueue an event — should land on disk but not be sent.
            queue.enqueue(makeQueuedEvent())
            queue.drainNow()

            assertEquals(0, session.requests.size, "no requests should be made while tracking disabled")
            assertEquals(1, dao.count(), "event should still be persisted on disk")
            queue.shutdown()
        }

    @Test
    fun `setTrackingEnabled true flushes buffered queue`() =
        runTest {
            val session = MockHTTPSession()
            val client = makeClient(session)
            val queue = makeQueue(client)
            val storage = InMemoryStorage()
            val privacy =
                PrivacyManager(
                    storage = storage,
                    queue = queue,
                    httpClient = client,
                    appContext = ApplicationProvider.getApplicationContext(),
                    logger = PyrxLogger(),
                )

            // Disable, enqueue → 0 requests.
            privacy.setTrackingEnabled(false)
            queue.enqueue(makeQueuedEvent())
            queue.drainNow()
            assertEquals(0, session.requests.size)

            // Enqueue the canned response BEFORE re-enabling so the drain
            // triggered by the privacy flip has something to consume.
            session.enqueueJsonSuccess(
                json = """{"event_id":"00000000-0000-0000-0000-000000000001","status":"accepted"}""",
            )

            // Re-enable — the privacy manager itself calls drainNow() so the
            // buffered event flushes synchronously.
            privacy.setTrackingEnabled(true)
            queue.drainNow()

            assertEquals(1, session.requests.size, "buffered event must flush on re-enable")
            assertEquals(0, dao.count(), "queue must be empty after drain succeeds")
            queue.shutdown()
        }

    // MARK: - deleteUser

    @Test
    fun `deleteUser wipes storage and queue then calls backend`() =
        runTest {
            val session = MockHTTPSession()
            // Backend delete returns an empty 200 — postPath discards the body.
            session.enqueueJsonSuccess(json = """{"status":"deleted"}""")
            val client = makeClient(session)
            val queue = makeQueue(client)
            val storage = InMemoryStorage()
            storage.set(PyrxStorageKey.EXTERNAL_ID, "user_42")
            storage.set(PyrxStorageKey.ANONYMOUS_ID, "anon-1")
            storage.set(PyrxStorageKey.DEVICE_TOKEN, "token-1")

            // Pre-seed the queue WITHOUT triggering a drain (the canned
            // response is reserved for the backend delete call, not an
            // event POST).
            queue.setTrackingEnabled(false)
            queue.enqueue(makeQueuedEvent())
            queue.setTrackingEnabled(true)
            assertEquals(1, dao.count(), "precondition: queue has one event")

            val privacy =
                PrivacyManager(
                    storage = storage,
                    queue = queue,
                    httpClient = client,
                    appContext = ApplicationProvider.getApplicationContext(),
                    logger = PyrxLogger(),
                )
            privacy.deleteUser()

            // Storage wiped.
            assertNull(storage.get(PyrxStorageKey.EXTERNAL_ID), "external_id must be wiped")
            assertNull(storage.get(PyrxStorageKey.ANONYMOUS_ID), "anonymous_id must be wiped")
            assertNull(storage.get(PyrxStorageKey.DEVICE_TOKEN), "device_token must be wiped")

            // Queue wiped.
            assertEquals(0, dao.count(), "queue must be empty after deleteUser")

            // Backend call fired with external_id (not anon).
            assertEquals(1, session.requests.size)
            assertEquals(
                "/v1/contacts/user_42/delete",
                session.requests[0].request.url.encodedPath,
            )
            queue.shutdown()
        }

    @Test
    fun `deleteUser swallows 404 from backend`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 404,
                    body = """{"detail":"Contact not found"}""".toByteArray(),
                    headers = mapOf("Content-Type" to "application/json"),
                ),
            )
            val client = makeClient(session)
            val queue = makeQueue(client)
            val storage = InMemoryStorage()
            storage.set(PyrxStorageKey.EXTERNAL_ID, "user_42")
            val privacy =
                PrivacyManager(
                    storage = storage,
                    queue = queue,
                    httpClient = client,
                    appContext = ApplicationProvider.getApplicationContext(),
                    logger = PyrxLogger(),
                )

            // Should NOT throw — 4xx is treated as already-deleted.
            privacy.deleteUser()

            assertNull(storage.get(PyrxStorageKey.EXTERNAL_ID), "local wipe stands even on 4xx")
            assertEquals(1, session.requests.size, "backend call should still have been attempted")
            queue.shutdown()
        }

    @Test
    fun `deleteUser propagates 500 from backend after local wipe`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueue(
                CannedResponse.Success(
                    statusCode = 500,
                    body = """{"detail":"internal server error"}""".toByteArray(),
                    headers = mapOf("Content-Type" to "application/json"),
                ),
            )
            val client = makeClient(session)
            val queue = makeQueue(client)
            val storage = InMemoryStorage()
            storage.set(PyrxStorageKey.EXTERNAL_ID, "user_42")
            val privacy =
                PrivacyManager(
                    storage = storage,
                    queue = queue,
                    httpClient = client,
                    appContext = ApplicationProvider.getApplicationContext(),
                    logger = PyrxLogger(),
                )

            val error =
                assertFailsWith<PyrxError.Network> {
                    privacy.deleteUser()
                }

            // 5xx surfaces as PyrxError.Network(HttpStatus).
            val inner = error.inner
            assertTrue(inner is PyrxNetworkError.HttpStatus)
            assertEquals(500, inner.statusCode)

            // Local wipe still happened — that's the user-visible promise.
            assertNull(storage.get(PyrxStorageKey.EXTERNAL_ID), "local wipe stands even when backend 5xx")
            queue.shutdown()
        }

    @Test
    fun `deleteUser falls back to anonymousId when no externalId is set`() =
        runTest {
            val session = MockHTTPSession()
            session.enqueueJsonSuccess(json = """{"status":"deleted"}""")
            val client = makeClient(session)
            val queue = makeQueue(client)
            val storage = InMemoryStorage()
            // No externalId — only anonymous.
            storage.set(PyrxStorageKey.ANONYMOUS_ID, "anon-xyz")

            val privacy =
                PrivacyManager(
                    storage = storage,
                    queue = queue,
                    httpClient = client,
                    appContext = ApplicationProvider.getApplicationContext(),
                    logger = PyrxLogger(),
                )
            privacy.deleteUser()

            assertEquals(1, session.requests.size)
            assertEquals(
                "/v1/contacts/anon-xyz/delete",
                session.requests[0].request.url.encodedPath,
            )
            queue.shutdown()
        }

    @Test
    fun `deleteUser skips backend call when no identifier exists`() =
        runTest {
            val session = MockHTTPSession()
            // No canned response — if the backend gets called, the mock
            // throws "no canned response queued" and the test fails.
            val client = makeClient(session)
            val queue = makeQueue(client)
            val storage = InMemoryStorage() // empty

            val privacy =
                PrivacyManager(
                    storage = storage,
                    queue = queue,
                    httpClient = client,
                    appContext = ApplicationProvider.getApplicationContext(),
                    logger = PyrxLogger(),
                )
            privacy.deleteUser() // should NOT throw

            assertEquals(0, session.requests.size, "backend must NOT be called when no identifier exists")
            queue.shutdown()
        }

    // MARK: - Notification permission

    @Test
    fun `notificationPermissionStatus returns a non-null value`() {
        // We don't assert GRANTED vs DENIED here — Robolectric defaults
        // depend on the SDK version and manifest declarations. We DO assert
        // that the method returns a non-null enum value (i.e. no
        // NullPointerException and no exception thrown).
        val storage = InMemoryStorage()
        val session = MockHTTPSession()
        val client = makeClient(session)
        val queue = makeQueue(client)
        val privacy =
            PrivacyManager(
                storage = storage,
                queue = queue,
                httpClient = client,
                appContext = ApplicationProvider.getApplicationContext(),
                logger = PyrxLogger(),
            )
        val status = privacy.notificationPermissionStatus()
        assertNotNull(status, "notificationPermissionStatus must return a non-null enum value")
    }

    @Test
    fun `staticNotificationPermissionStatus returns NOT_REQUESTED for null context`() {
        // Pre-init / no-context path — used by PyrxDebugInfo before initialize().
        val status = PrivacyManager.staticNotificationPermissionStatus(null)
        assertEquals(tech.pyrx.synapse.PyrxNotificationPermission.NOT_REQUESTED, status)
    }
}

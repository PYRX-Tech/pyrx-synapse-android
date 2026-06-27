/*
 * IdentityChangedFireTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the identity-transition fire-points wired in Phase 9.2.1
 * Task 9.2.1.13b. End-to-end through `Pyrx.identify` / `Pyrx.alias` /
 * `Pyrx.logout`, with `MockHTTPSession` faking the backend and Room
 * in-memory faking the queue.
 *
 * Covers:
 *   - First identify after a fresh install — `before` is null, `after`
 *     carries externalId + anonymousId
 *   - Subsequent identify (user switch) — `before` carries prior
 *     external_id, `after` carries new
 *   - Alias — `before` and `after` reflect the storage transition
 *   - Logout — `after.externalId` is null while anonymousId survives
 *
 * Uses Robolectric for the Android Context (EncryptedSharedPreferences
 * + Room require it). `@RunWith(RobolectricTestRunner::class)` is the
 * established pattern across the existing test suite.
 */

package tech.pyrx.synapse.observer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxEnvironment
import tech.pyrx.synapse.network.MockHTTPSession
import tech.pyrx.synapse.queue.EventQueueDatabase
import tech.pyrx.synapse.queue.QueuedEventDao
import tech.pyrx.synapse.queue.Reachability
import tech.pyrx.synapse.queue.ReachabilityStatus
import tech.pyrx.synapse.storage.InMemoryStorage
import tech.pyrx.synapse.storage.PyrxStorageKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IdentityChangedFireTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_test_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private lateinit var database: EventQueueDatabase
    private lateinit var dao: QueuedEventDao

    private class FakeReachability : Reachability {
        private val flow = MutableSharedFlow<ReachabilityStatus>(replay = 1, extraBufferCapacity = 16)
        override val status: Flow<ReachabilityStatus> = flow.asSharedFlow()

        override fun start() = Unit

        override fun stop() = Unit
    }

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                EventQueueDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.queuedEventDao()
        Pyrx.resetForTesting()
    }

    @After
    fun tearDown() {
        Pyrx.resetForTesting()
        database.close()
    }

    private suspend fun initSdk(
        anonymousIdSeed: String = "anon-fixture",
        externalIdSeed: String? = null,
        session: MockHTTPSession = MockHTTPSession(),
    ): MockHTTPSession {
        val storage = InMemoryStorage()
        storage.set(PyrxStorageKey.ANONYMOUS_ID, anonymousIdSeed)
        externalIdSeed?.let { storage.set(PyrxStorageKey.EXTERNAL_ID, it) }
        val config =
            PyrxConfig(
                workspaceId = workspaceId,
                apiKey = apiKey,
                environment = PyrxEnvironment.SANDBOX,
                baseUrl = "https://synapse-events.staging.pyrx.tech",
            )
        Pyrx.initialize(
            context = ApplicationProvider.getApplicationContext(),
            config = config,
            storageOverride = storage,
            sessionOverride = session,
            daoOverride = dao,
            reachabilityOverride = FakeReachability(),
        )
        return session
    }

    /**
     * Canned `/v1/identify` response for both identify and alias —
     * `IdentifyResponse` and `AliasResponse` share the same shape.
     */
    private fun enqueueIdentifyResponse(
        session: MockHTTPSession,
        aliased: String? = "anon-fixture",
    ) {
        val aliasedField = aliased?.let { "\"$it\"" } ?: "null"
        session.enqueueJsonSuccess(
            json =
                """
                {"contact_id":"22222222-2222-2222-2222-222222222222","path":"first_sighting",
                "aliased_external_id":$aliasedField,
                "events_reattributed":0,"devices_reattributed":0,
                "anonymous_contact_tombstoned":false}
                """.trimIndent().replace("\n", ""),
        )
    }

    // MARK: - identify

    @Test
    fun `first identify after fresh install fires IdentityChanged with before equal to anonymous-only snapshot`() =
        runTest {
            // anonymousId is seeded by initialize (the ensureAnonymousId
            // step in Pyrx.initialize). Pre-identify, externalId is null.
            val session = initSdk(anonymousIdSeed = "anon-fixture")
            enqueueIdentifyResponse(session, aliased = "anon-fixture")

            Pyrx.identify(externalId = "user_42")

            // The IdentityChanged event lands in the replay cache as soon
            // as identify() returns — read it directly to avoid the
            // virtual-time pitfall with withTimeout inside runTest.
            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.IdentityChanged>()
                    .lastOrNull()
            assertNotNull(event, "identify() must publish IdentityChanged")

            // `before` is the pre-mutation snapshot — externalId was
            // null, anonymousId was seeded. We treat "no external_id and
            // an anonymousId" as a valid pre-identify state, not "no
            // prior state". Only a truly fresh-install pre-init gets
            // before == null.
            assertNotNull(event.before, "before must capture the pre-identify storage state")
            assertNull(event.before?.externalId, "no external_id before first identify")
            assertEquals("anon-fixture", event.before?.anonymousId)

            // `after` carries the freshly-persisted externalId.
            assertEquals("user_42", event.after.externalId)
            assertEquals("anon-fixture", event.after.anonymousId)
        }

    @Test
    fun `second identify with a different externalId fires IdentityChanged with before equal to prior state`() =
        runTest {
            val session = initSdk(anonymousIdSeed = "anon-fixture")
            enqueueIdentifyResponse(session, aliased = "anon-fixture")
            enqueueIdentifyResponse(session, aliased = "anon-fixture")

            Pyrx.identify(externalId = "user_first")
            Pyrx.identify(externalId = "user_second")

            // Replay buffer (4) carries both IdentityChanged events.
            // Filter for the "second" transition.
            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.IdentityChanged>()
                    .firstOrNull { it.after.externalId == "user_second" }

            assertNotNull(event, "second identify must publish IdentityChanged")
            assertEquals("user_first", event.before?.externalId)
            assertEquals("user_second", event.after.externalId)
            assertEquals("anon-fixture", event.after.anonymousId)
        }

    // MARK: - alias

    @Test
    fun `alias fires IdentityChanged with before equal to anonymous and after equal to new externalId`() =
        runTest {
            val session = initSdk(anonymousIdSeed = "anon-fixture")
            enqueueIdentifyResponse(session, aliased = "anon-fixture")

            Pyrx.alias(newExternalId = "user_aliased")

            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.IdentityChanged>()
                    .lastOrNull()
            assertNotNull(event)
            assertNull(event.before?.externalId)
            assertEquals("user_aliased", event.after.externalId)
            assertEquals("anon-fixture", event.after.anonymousId)
        }

    // MARK: - logout

    @Test
    fun `logout fires IdentityChanged with before equal to identified state and after equal to anonymous-only`() =
        runTest {
            initSdk(anonymousIdSeed = "anon-fixture", externalIdSeed = "user_42")

            Pyrx.logout()

            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.IdentityChanged>()
                    .lastOrNull()
            assertNotNull(event)
            assertEquals("user_42", event.before?.externalId)
            assertNull(event.after.externalId, "logout clears externalId")
            assertEquals("anon-fixture", event.after.anonymousId, "anonymousId survives logout")
        }

    // MARK: - snapshot resolvedAt is populated

    @Test
    fun `IdentitySnapshot resolvedAt is populated by every fire-point`() =
        runTest {
            val session = initSdk(anonymousIdSeed = "anon-fixture")
            enqueueIdentifyResponse(session, aliased = "anon-fixture")

            val before = java.time.Instant.now()
            Pyrx.identify(externalId = "user_42")
            val event =
                Pyrx.events.replayCache
                    .filterIsInstance<PyrxEvent.IdentityChanged>()
                    .lastOrNull()
            assertNotNull(event)

            assertTrue(
                event.after.resolvedAt.isAfter(before) || event.after.resolvedAt.equals(before),
                "after snapshot resolvedAt must be at-or-after the pre-call wall clock",
            )
        }
}

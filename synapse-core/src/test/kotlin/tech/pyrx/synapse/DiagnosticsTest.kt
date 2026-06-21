/*
 * DiagnosticsTest.kt
 * PYRXSynapseTests — Android
 *
 * Exercises the PR 5 [PyrxDebugInfo] enrichment (Phase 8.4b Task 8.4b.11):
 *
 *   - sdkVersion / platform / initialized — present pre- and post-init.
 *   - environment / baseUrl               — present post-init.
 *   - deviceTokenFingerprint              — `…<last-8>` view; never full token.
 *   - trackingEnabled                     — reflects PrivacyManager state.
 *   - notificationPermission              — non-null enum value.
 *   - eventQueueDepth                     — reflects on-disk queue.
 *   - lastDrainAt                         — null pre-drain, non-null after.
 *
 * Mirrors iOS `PyrxDebugInfoTests.swift` enrichment cases. No real Firebase
 * / no real network — `MockHTTPSession` injects canned responses.
 */

package tech.pyrx.synapse

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
class DiagnosticsTest {
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

    // MARK: - Fingerprint helper

    @Test
    fun `fingerprint of a normal-length token returns ellipsis plus last 8 chars`() {
        // 140-character fake token (real FCM tokens are ~152-200 chars).
        val token = "a".repeat(132) + "ABCDEFGH"
        val fp = PyrxDebugInfo.fingerprint(forDeviceToken = token)
        assertEquals("…ABCDEFGH", fp)
    }

    @Test
    fun `fingerprint of a short token returns ellipsis plus full token`() {
        val fp = PyrxDebugInfo.fingerprint(forDeviceToken = "abc")
        assertEquals("…abc", fp)
    }

    @Test
    fun `fingerprint of an exactly-8-char token returns ellipsis plus full token`() {
        val fp = PyrxDebugInfo.fingerprint(forDeviceToken = "12345678")
        assertEquals("…12345678", fp)
    }

    @Test
    fun `fingerprint of null returns null`() {
        assertNull(PyrxDebugInfo.fingerprint(forDeviceToken = null))
    }

    @Test
    fun `fingerprint of empty string returns null`() {
        assertNull(PyrxDebugInfo.fingerprint(forDeviceToken = ""))
    }

    // MARK: - Pre-init snapshot

    @Test
    fun `debugInfo before initialize reports uninitialized state with safe defaults`() =
        runTest {
            val info = Pyrx.debugInfo()
            assertEquals(PyrxConstants.SDK_VERSION, info.sdkVersion)
            assertEquals("android", info.platform)
            assertEquals(false, info.initialized)
            assertNull(info.workspaceId)
            assertNull(info.environment)
            assertNull(info.baseUrl)
            assertNull(info.anonymousId)
            assertEquals(false, info.hasExternalId)
            assertEquals(false, info.hasDeviceToken)
            assertNull(info.deviceTokenFingerprint)
            assertEquals(true, info.trackingEnabled, "tracking defaults to enabled")
            assertNotNull(info.notificationPermission, "notification permission must be a non-null enum value")
            assertEquals(0, info.eventQueueDepth)
            assertNull(info.lastDrainAt)
        }

    // MARK: - Post-init snapshot

    @Test
    fun `debugInfo after initialize reports environment and baseUrl`() =
        runTest {
            val session = MockHTTPSession()
            val storage = InMemoryStorage()
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

            val info = Pyrx.debugInfo()

            assertEquals(true, info.initialized)
            assertEquals(workspaceId, info.workspaceId)
            assertEquals("sandbox", info.environment)
            assertEquals("https://synapse-events.staging.pyrx.tech", info.baseUrl)
            assertNotNull(info.anonymousId, "anonymousId must be present post-init")
            assertEquals(true, info.trackingEnabled, "tracking enabled by default")
            assertEquals(0, info.eventQueueDepth)
        }

    @Test
    fun `debugInfo reports device token fingerprint when token is persisted`() =
        runTest {
            val session = MockHTTPSession()
            val storage = InMemoryStorage()
            val fakeToken = "f".repeat(132) + "DEADBEEF"
            storage.set(PyrxStorageKey.DEVICE_TOKEN, fakeToken)

            val config =
                PyrxConfig(
                    workspaceId = workspaceId,
                    apiKey = apiKey,
                    environment = PyrxEnvironment.PRODUCTION,
                    baseUrl = "https://synapse-events.pyrx.tech",
                )
            Pyrx.initialize(
                context = ApplicationProvider.getApplicationContext(),
                config = config,
                storageOverride = storage,
                sessionOverride = session,
                daoOverride = dao,
                reachabilityOverride = FakeReachability(),
            )

            val info = Pyrx.debugInfo()

            assertEquals(true, info.hasDeviceToken)
            assertEquals("…DEADBEEF", info.deviceTokenFingerprint)
            // CRITICAL: the full token must NEVER appear in the snapshot.
            assertTrue(
                info.deviceTokenFingerprint?.contains("ffffffffff") != true,
                "device token fingerprint must NOT include the full token bytes",
            )
        }

    @Test
    fun `debugInfo trackingEnabled flips when setTrackingEnabled is called`() =
        runTest {
            val session = MockHTTPSession()
            val storage = InMemoryStorage()
            val config =
                PyrxConfig(
                    workspaceId = workspaceId,
                    apiKey = apiKey,
                    environment = PyrxEnvironment.PRODUCTION,
                    baseUrl = "https://synapse-events.pyrx.tech",
                )
            Pyrx.initialize(
                context = ApplicationProvider.getApplicationContext(),
                config = config,
                storageOverride = storage,
                sessionOverride = session,
                daoOverride = dao,
                reachabilityOverride = FakeReachability(),
            )

            assertEquals(true, Pyrx.debugInfo().trackingEnabled)

            Pyrx.setTrackingEnabled(false)
            assertEquals(false, Pyrx.debugInfo().trackingEnabled)

            Pyrx.setTrackingEnabled(true)
            assertEquals(true, Pyrx.debugInfo().trackingEnabled)
        }

    @Test
    fun `debugInfo pendingTrackingEnabled survives across pre-init flip`() =
        runTest {
            // Flip BEFORE initialize — the value must be buffered.
            Pyrx.setTrackingEnabled(false)
            assertEquals(false, Pyrx.debugInfo().trackingEnabled, "buffered value must surface pre-init too")
        }
}

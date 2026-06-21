/*
 * PyrxSmokeTest.kt
 * PYRXSynapse — Android instrumented tests
 *
 * Phase 8.4b Task 8.4b.12 smoke surface — runs on a real Android device or
 * emulator (NOT under Robolectric; those live in `src/test/`). The intent is
 * a deliberately small set of checks that prove the SDK boots end-to-end on
 * a real Android runtime:
 *
 *   1. Pyrx.initialize completes without throwing on a real device (the
 *      Keystore is real; EncryptedSharedPreferences uses the platform TEE,
 *      not a Robolectric stub).
 *   2. debugInfo() can be queried after initialize and surfaces non-null
 *      identifiers (this catches accidental missing-storage bugs that would
 *      only surface on-device).
 *
 * These tests are NOT run by the default `./gradlew test` task — they need
 * a connected emulator/device via `./gradlew connectedDebugAndroidTest`.
 * That on-device verification is deferred to PR 7 (which adds CI emulator
 * coverage). For now this file is the wire surface for that future work.
 *
 * Mirrors iOS PR #6's `PyrxSmokeTests.swift` shape — equally small, equally
 * deferred to the dedicated on-device CI lane.
 */

package tech.pyrx.synapse

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PyrxSmokeTest {
    private val workspaceId: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val apiKey: String = "psk_test_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    @Before
    fun setUp() {
        // Pyrx.resetForTesting is internal — instrumented tests don't have
        // access. We rely on a fresh process per test class which is the
        // AndroidX test runner default.
    }

    @After
    fun tearDown() {
        // No-op — process tear-down handles cleanup.
    }

    @Test
    fun pyrx_initialize_boots_on_real_device() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val config =
                PyrxConfig(
                    workspaceId = workspaceId,
                    apiKey = apiKey,
                    environment = PyrxEnvironment.SANDBOX,
                    baseUrl = "https://synapse-events.pyrx.tech",
                )
            // The interesting bit: on a real device, EncryptedSharedPreferences
            // talks to the platform Keystore. Robolectric stubs that out — so
            // a green Robolectric run does NOT prove this works in production.
            Pyrx.initialize(context, config)

            val info = Pyrx.debugInfo()
            assertNotNull(info, "debugInfo must not be null after initialize")
            assertTrue(info.initialized, "debugInfo.initialized must be true after initialize")
            assertNotNull(info.anonymousId, "anonymous id must be assigned on first init")
        }
}

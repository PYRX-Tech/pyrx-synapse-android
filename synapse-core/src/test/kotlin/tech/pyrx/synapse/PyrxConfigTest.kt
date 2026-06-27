/*
 * PyrxConfigTest.kt
 * PYRXSynapse — Android
 *
 * Validates the [PyrxConfig.validate] contract. Mirrors the iOS
 * PyrxConfigTests cases so a behaviour drift on either platform is
 * caught by cross-platform contract testing in PR 6.
 */

package tech.pyrx.synapse

import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PyrxConfigTest {
    private val sampleWorkspace = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `default base URL points to production ingestion endpoint`() {
        val cfg = PyrxConfig(workspaceId = sampleWorkspace, apiKey = "psk_test_abcdef")
        assertEquals("https://synapse-events.pyrx.tech", cfg.baseUrl)
        assertEquals(PyrxEnvironment.PRODUCTION, cfg.environment)
        assertEquals(LogLevel.INFO, cfg.logLevel)
    }

    @Test
    fun `validate accepts a well-formed test key`() {
        PyrxConfig(workspaceId = sampleWorkspace, apiKey = "psk_test_abc123").validate()
    }

    @Test
    fun `validate accepts a well-formed live key`() {
        PyrxConfig(workspaceId = sampleWorkspace, apiKey = "psk_live_xyz789").validate()
    }

    @Test
    fun `validate rejects empty apiKey`() {
        val err =
            assertFailsWith<PyrxError.InvalidConfig> {
                PyrxConfig(workspaceId = sampleWorkspace, apiKey = "").validate()
            }
        assertEquals("apiKey must not be empty", err.reason)
    }

    @Test
    fun `validate rejects whitespace-only apiKey`() {
        val err =
            assertFailsWith<PyrxError.InvalidConfig> {
                PyrxConfig(workspaceId = sampleWorkspace, apiKey = "   ").validate()
            }
        assertEquals("apiKey must not be empty", err.reason)
    }

    @Test
    fun `validate rejects apiKey without psk_ prefix`() {
        val err =
            assertFailsWith<PyrxError.InvalidConfig> {
                PyrxConfig(workspaceId = sampleWorkspace, apiKey = "sk_test_abc").validate()
            }
        assertEquals("apiKey must start with 'psk_'", err.reason)
    }

    @Test
    fun `validate rejects non-http base URL`() {
        val err =
            assertFailsWith<PyrxError.InvalidConfig> {
                PyrxConfig(
                    workspaceId = sampleWorkspace,
                    apiKey = "psk_test_abc",
                    baseUrl = "ftp://example.com",
                ).validate()
            }
        assertEquals("baseUrl must use http(s) scheme", err.reason)
    }

    @Test
    fun `wireValue maps PRODUCTION to live and SANDBOX to test`() {
        // Locks the wire contract — backend identify/alias/devices bodies
        // discriminate on these literal strings.
        assertEquals("live", PyrxEnvironment.PRODUCTION.wireValue)
        assertEquals("test", PyrxEnvironment.SANDBOX.wireValue)
    }

    @Test
    fun `SDK constants match the locked release values`() {
        // PR 7 release script bumps SDK_VERSION; PLATFORM is forever "android".
        assertEquals("0.1.4", PyrxConstants.SDK_VERSION)
        assertEquals("android", PyrxConstants.PLATFORM)
    }

    // MARK: - sdkVariant

    @Test
    fun `sdkVariant defaults to null`() {
        // A bare-Android integration never sets this — verify the default
        // does NOT inject a wrapper marker into the wire payload.
        val cfg = PyrxConfig(workspaceId = sampleWorkspace, apiKey = "psk_test_abc123")
        assertEquals(null, cfg.sdkVariant)
        assertEquals(null, cfg.normalizedSdkVariant())
    }

    @Test
    fun `sdkVariant passes through for a valid value`() {
        val cfg =
            PyrxConfig(
                workspaceId = sampleWorkspace,
                apiKey = "psk_test_abc123",
                sdkVariant = "rn",
            )
        assertEquals("rn", cfg.sdkVariant)
        assertEquals("rn", cfg.normalizedSdkVariant())
    }

    @Test
    fun `normalizedSdkVariant trims incidental whitespace`() {
        // Trimming guards against accidental wire values like "android+ rn".
        val cfg =
            PyrxConfig(
                workspaceId = sampleWorkspace,
                apiKey = "psk_test_abc123",
                sdkVariant = "  rn  ",
            )
        assertEquals("rn", cfg.normalizedSdkVariant())
    }

    @Test
    fun `normalizedSdkVariant collapses empty and whitespace to null`() {
        // Either of these would otherwise serialize as the malformed wire
        // value "android+" — collapsing to null keeps telemetry clean.
        val empty =
            PyrxConfig(workspaceId = sampleWorkspace, apiKey = "psk_test_abc123", sdkVariant = "")
        assertEquals(null, empty.normalizedSdkVariant())

        val blanks =
            PyrxConfig(workspaceId = sampleWorkspace, apiKey = "psk_test_abc123", sdkVariant = "   \n\t")
        assertEquals(null, blanks.normalizedSdkVariant())
    }
}

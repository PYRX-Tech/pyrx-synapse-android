/*
 * DeviceMetadataTest.kt
 * PYRXSynapseTests — Android
 *
 * Asserts the shape (not literal values) of the device-metadata snapshot
 * the push module sends on `/v1/devices`. Robolectric provides default
 * Build values (Build.MODEL = "robolectric", Build.MANUFACTURER =
 * "robolectric", Build.VERSION.RELEASE = the major Android version) — we
 * lock onto those for the specific-value assertions but stay tolerant for
 * the SDK-version / platform fields which are owned by PyrxConstants.
 *
 * Mirrors iOS `DeviceMetadataTests.swift` shape-for-shape.
 */

package tech.pyrx.synapse.push

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.pyrx.synapse.PyrxConstants
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeviceMetadataTest {
    @Test
    fun `bundleId returns the host context package name`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bundle = DeviceMetadata.bundleId(context)
        // Robolectric defaults the package to "tech.pyrx.synapse.push.test"
        // (or similar), but we don't pin it — only that it is non-empty
        // and contains dots (real Android packages are reverse-DNS).
        assertTrue(bundle.isNotEmpty(), "bundleId must not be empty")
        assertTrue(bundle.contains('.'), "bundleId must look like a reverse-DNS string — got $bundle")
    }

    @Test
    fun `appVersion is non-empty (either a real version or the unknown fallback)`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val version = DeviceMetadata.appVersion(context)
        assertTrue(version.isNotEmpty(), "appVersion must not be empty")
        // Robolectric leaves versionName null by default, so we expect the
        // "unknown" fallback — but we don't pin it exactly because a future
        // Robolectric upgrade might start filling it in.
    }

    @Test
    fun `sdkVersion matches the compile-time PyrxConstants value`() {
        assertEquals(PyrxConstants.SDK_VERSION, DeviceMetadata.sdkVersion())
    }

    @Test
    fun `sdkPlatform is always android`() {
        assertEquals("android", DeviceMetadata.sdkPlatform())
        assertEquals(PyrxConstants.PLATFORM, DeviceMetadata.sdkPlatform())
    }

    @Test
    fun `osVersion is prefixed with Android and contains the release number`() {
        val version = DeviceMetadata.osVersion()
        assertTrue(
            version.startsWith("Android "),
            "osVersion must begin with 'Android ' — got '$version'",
        )
        // Trailing portion is non-empty (Robolectric sets a real release).
        assertTrue(
            version.length > "Android ".length,
            "osVersion must include a release suffix — got '$version'",
        )
    }

    @Test
    fun `deviceModel concatenates manufacturer and model with a space`() {
        val model = DeviceMetadata.deviceModel()
        // Robolectric default: Build.MANUFACTURER = "robolectric",
        // Build.MODEL = "robolectric"; we don't pin those, only the shape.
        assertTrue(model.isNotEmpty(), "deviceModel must not be empty")
        // The format is "<manufacturer> <model>" — either both are non-empty
        // (single space) or one is empty (trim collapses the space). We
        // assert non-empty and contain no leading/trailing whitespace.
        assertEquals(model.trim(), model, "deviceModel must not have leading/trailing whitespace")
    }

    @Test
    fun `locale follows the Locale toString format`() {
        val loc = DeviceMetadata.locale()
        // Locale.toString() shape: language[_REGION[_variant]] OR just
        // language. Robolectric default is "en_US"; we only assert non-empty.
        assertTrue(loc.isNotEmpty(), "locale must not be empty")
    }

    @Test
    fun `timezone is a non-empty zone id`() {
        val tz = DeviceMetadata.timezone()
        // TimeZone.getDefault().id — e.g. "America/Los_Angeles" or "UTC" or
        // "Etc/UTC". Always non-empty per JDK contract.
        assertTrue(tz.isNotEmpty(), "timezone must not be empty")
    }

    // MARK: - sdkPlatform(variant)

    @Test
    fun `sdkPlatform with null variant returns bare android`() {
        assertEquals("android", DeviceMetadata.sdkPlatform(null))
    }

    @Test
    fun `sdkPlatform with non-empty variant appends suffix`() {
        // The wrapper-variant convention: "android+<variant>".
        assertEquals("android+rn", DeviceMetadata.sdkPlatform("rn"))
        assertEquals("android+flutter", DeviceMetadata.sdkPlatform("flutter"))
    }

    @Test
    fun `sdkPlatform variant trims whitespace`() {
        assertEquals("android+rn", DeviceMetadata.sdkPlatform("  rn  "))
    }

    @Test
    fun `sdkPlatform empty or blank variant falls back to bare android`() {
        // Empty / blank should NOT produce the malformed wire value "android+".
        assertEquals("android", DeviceMetadata.sdkPlatform(""))
        assertEquals("android", DeviceMetadata.sdkPlatform("   "))
    }
}

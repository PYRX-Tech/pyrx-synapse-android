/*
 * DeviceMetadata.kt
 * PYRXSynapse — Android
 *
 * Helpers that snapshot the host device's identifying metadata for the
 * `POST /v1/devices` payload (Phase 8.4b Task 8.4b.7).
 *
 * Why these values, in this shape?
 * ================================
 *
 * The backend `DeviceRegister` schema (synapse-api/app/schemas/device.py)
 * accepts these fields verbatim — we mirror them via [tech.pyrx.synapse
 * .network.DeviceRegisterRequest]. Concretely we capture:
 *
 *   - bundle_id     : Context.packageName (e.g. "tech.pyrx.crm.android")
 *   - app_version   : PackageInfo.versionName ("2.4.1") or "unknown"
 *   - sdk_version   : PyrxConstants.SDK_VERSION (compile-time)
 *   - sdk_platform  : PyrxConstants.PLATFORM (always "android")
 *   - os_version    : "Android 14" / "Android 13"
 *   - device_model  : Build.MANUFACTURER + " " + Build.MODEL
 *                     (e.g. "Google Pixel 8 Pro", "samsung SM-S918B")
 *   - locale        : Locale.getDefault().toString() ("en_US", "ja_JP")
 *   - timezone      : TimeZone.getDefault().id ("America/Los_Angeles")
 *
 * Why no `#if` walls?
 * -------------------
 * Unlike iOS (UIKit vs SwiftPM Linux), every Android SDK target ships
 * `android.os.Build`, `java.util.Locale`, and `java.util.TimeZone`. We
 * never compile on non-Android JVMs, so no shims are needed.
 *
 * Context dependency
 * ------------------
 * Every helper that needs the host package info takes a [android.content
 * .Context] parameter (rather than a stored field) — DeviceMetadata is an
 * `object` (Kotlin singleton) and we deliberately avoid stashing a Context
 * to prevent accidental leaks. The caller (PushRegistration) already has a
 * Context in scope.
 *
 * Tests
 * -----
 * All helpers are pure — `deviceModel()` reads `Build.MODEL` directly, no
 * injection seam needed. Robolectric provides default values for
 * `Build.MODEL` / `Build.VERSION.RELEASE` etc. so unit tests can assert the
 * shape is well-formed (non-empty, matches a reasonable format) rather than
 * pinning a literal value.
 *
 * Mirrors iOS `DeviceMetadata.swift` shape-for-shape.
 */

package tech.pyrx.synapse.push

import android.content.Context
import android.os.Build
import tech.pyrx.synapse.PyrxConstants
import java.util.Locale
import java.util.TimeZone

/**
 * Snapshot of identifying device metadata for `/v1/devices` registration.
 * All fields are optional in the wire schema; we fill what we can and
 * surface the request-builder with sensible defaults for the rest.
 *
 * Public so [PushRegistration] (which lives in this same module) can
 * reach it; not part of the host-app contract — host apps never call
 * these methods directly.
 */
public object DeviceMetadata {
    /**
     * Bundle identifier of the host app — e.g. `"tech.pyrx.crm.android"`.
     * Reads directly from [Context.getPackageName] which is always present
     * (the OS guarantees it for every running app).
     */
    public fun bundleId(context: Context): String = context.packageName

    /**
     * Marketing version of the host app, e.g. `"2.4.1"`. Falls back to
     * `"unknown"` if [android.content.pm.PackageInfo.versionName] is null
     * (some test fixtures and instant apps don't set one).
     *
     * We swallow exceptions defensively — it should never trigger for our
     * own package, but if a weird shim Context is passed in we don't want
     * the SDK to crash on registration.
     */
    public fun appVersion(context: Context): String =
        try {
            @Suppress("DEPRECATION") // PackageInfoFlags is API 33+; we support 21+.
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName?.takeIf { it.isNotEmpty() } ?: UNKNOWN
        } catch (_: Throwable) {
            UNKNOWN
        }

    /** SDK semantic version — compile-time constant from [PyrxConstants]. */
    public fun sdkVersion(): String = PyrxConstants.SDK_VERSION

    /** SDK platform identifier — always `"android"`. Mirrors iOS `"ios"`. */
    public fun sdkPlatform(): String = PyrxConstants.PLATFORM

    /**
     * SDK platform identifier with an optional wrapper-variant suffix —
     * e.g. `"android"` (no variant) or `"android+rn"` (React Native wrapper).
     *
     * The suffix is **telemetry-only**: the backend's push dispatcher
     * routes on `Device.platform` (`"ios"` / `"android"`), not on
     * `sdk_platform`, so a variant value can never break delivery.
     * Wrappers pass their identifier via `PyrxConfig.sdkVariant`; the
     * [PushRegistration] class threads that value through to this
     * helper.
     *
     * Empty / whitespace-only values fall back to the bare
     * [PyrxConstants.PLATFORM] string so misuse never produces the
     * malformed wire value `"android+"`.
     */
    public fun sdkPlatform(variant: String?): String {
        val trimmed = variant?.trim()
        return if (trimmed.isNullOrEmpty()) PyrxConstants.PLATFORM else "${PyrxConstants.PLATFORM}+$trimmed"
    }

    /**
     * Human-readable OS string, e.g. `"Android 14"`.
     *
     * We deliberately prepend `"Android "` rather than sending the bare
     * version number — the dashboard Device Explorer (Phase 8 §8.3.7) groups
     * by this string and the prefix makes the column readable without
     * joining against `platform`. Matches the iOS shape (`"iOS 17.4.1"`).
     */
    public fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"

    /**
     * Hardware identifier — `Build.MANUFACTURER` + space + `Build.MODEL`.
     *
     * E.g. `"Google Pixel 8 Pro"`, `"samsung SM-S918B"`, `"OnePlus IN2025"`.
     * Android's `Build.MODEL` alone is often non-unique across manufacturers
     * (e.g. multiple OEMs ship a `"M2007J20CG"` variant), so the
     * manufacturer prefix disambiguates without requiring a server-side
     * lookup table.
     *
     * The iOS counterpart returns a single string like `"iPhone15,3"`; the
     * dashboard maps both via its own per-platform translation layer.
     */
    public fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /**
     * Current user locale identifier, e.g. `"en_US"`, `"fr_FR"`, `"ja_JP"`.
     *
     * Uses [Locale.getDefault] which reflects the device user's language /
     * region setting at the moment of the call. We do NOT cache it because
     * the user can change locale at runtime (locale change triggers an
     * Activity restart but library code receives the new value on next
     * read).
     */
    public fun locale(): String = Locale.getDefault().toString()

    /**
     * Current device timezone identifier, e.g. `"America/Los_Angeles"`,
     * `"Asia/Tokyo"`, `"UTC"`. Uses [TimeZone.getDefault] for the same
     * runtime-mutation reason as [locale].
     */
    public fun timezone(): String = TimeZone.getDefault().id

    private const val UNKNOWN: String = "unknown"
}

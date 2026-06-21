/*
 * PyrxDebugInfo.kt
 * PYRXSynapse — Android
 *
 * Read-only snapshot of SDK state at a point in time. Returned by
 * [Pyrx.debugInfo] for diagnostics — wire into a debug menu or include
 * in bug reports.
 *
 * Mirrors iOS `PyrxDebugInfo` field-for-field. The two booleans
 * (`hasExternalId`, `hasDeviceToken`) are always `false` in PR 1 because
 * identify (PR 2) and push registration (PR 4) haven't shipped yet.
 */

package tech.pyrx.synapse

import java.util.UUID

public data class PyrxDebugInfo(
    /** SDK semantic version (matches the Gradle `version` declarations + release tag). */
    val sdkVersion: String,
    /** Platform identifier sent on `X-PYRX-SDK-PLATFORM` (always `"android"`). */
    val platform: String,
    /** True if [Pyrx.initialize] succeeded. */
    val initialized: Boolean,
    /** Workspace UUID the SDK is bound to, if initialized. */
    val workspaceId: UUID?,
    /** Active log level. */
    val logLevel: LogLevel,
    /** Locally-persisted anonymous ID (always present once initialized). */
    val anonymousId: String?,
    /** True if `identify(externalId)` has set an external ID. Always false in PR 1. */
    val hasExternalId: Boolean,
    /** True if push registration has stored a device token. Always false in PR 1. */
    val hasDeviceToken: Boolean,
)

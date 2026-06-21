/*
 * PyrxDebugInfo.kt
 * PYRXSynapse — Android
 *
 * Read-only snapshot of SDK state at a point in time. Returned by
 * [Pyrx.debugInfo] for diagnostics — wire into a debug menu or include
 * in bug reports.
 *
 * PR 1 fields:
 *   sdkVersion, platform, initialized, workspaceId, logLevel,
 *   anonymousId, hasExternalId, hasDeviceToken.
 *
 * PR 5 extension (Phase 8.4b Task 8.4b.11) adds:
 *   - environment              — the SDK's wire environment ("live" / "test").
 *   - baseUrl                  — the base URL the SDK is POSTing to.
 *   - deviceTokenFingerprint   — last-8-char ellipsis-prefixed view of the
 *                                FCM token (NEVER the full token). Mirrors
 *                                iOS PyrxDebugInfo + dashboard PR #134.
 *   - trackingEnabled          — current value of the privacy kill switch.
 *   - notificationPermission   — Android `POST_NOTIFICATIONS` permission
 *                                state (Android 13+). Always GRANTED on
 *                                pre-13 because the runtime permission
 *                                didn't exist (notifications were granted
 *                                implicitly by app install).
 *   - eventQueueDepth          — pending count in the offline queue.
 *   - lastDrainAt              — wall-clock millis-since-epoch of the most
 *                                recent drain pass (any outcome — success
 *                                or transient failure both count). Null if
 *                                the queue has not drained yet.
 *
 * Mirrors iOS `PyrxDebugInfo` field-for-field with one platform adaptation:
 * iOS surfaces `attStatus` (App Tracking Transparency); Android surfaces
 * `notificationPermission` (the closest analogue — the user-facing privacy
 * grant the SDK consults but does not auto-prompt for).
 */

package tech.pyrx.synapse

import java.util.UUID

/**
 * Notification permission state, mirroring the result of
 * `ContextCompat.checkSelfPermission(POST_NOTIFICATIONS)` plus the pre-API-33
 * baseline. Surfaced in [PyrxDebugInfo] for transparency; the SDK never
 * auto-requests the runtime permission — host apps own that UX.
 *
 * Distinct from iOS's [tech.pyrx.synapse.PyrxATTStatus]-shaped enum (Apple
 * authorization-status) because Android's grant model is simpler — there is
 * no `restricted` vs `denied` distinction at the framework level; only
 * granted vs not granted.
 */
public enum class PyrxNotificationPermission {
    /** Permission granted (either explicitly on Android 13+, or implicitly pre-13). */
    GRANTED,

    /** Permission denied (Android 13+ with the user having declined the prompt). */
    DENIED,

    /**
     * Permission not yet decided — the runtime prompt has never been shown
     * on Android 13+, OR the SDK was constructed without an Android context
     * (extremely unusual; only possible before [Pyrx.initialize]).
     */
    NOT_REQUESTED,
}

public data class PyrxDebugInfo(
    /** SDK semantic version (matches the Gradle `version` declarations + release tag). */
    val sdkVersion: String,
    /** Platform identifier sent on `X-PYRX-SDK-PLATFORM` (always `"android"`). */
    val platform: String,
    /** True if [Pyrx.initialize] succeeded. */
    val initialized: Boolean,
    /** Workspace UUID the SDK is bound to, if initialized. */
    val workspaceId: UUID?,
    /**
     * SDK environment selector ("production" or "sandbox") — string form of
     * [PyrxEnvironment] so the debug payload stays JSON-friendly.
     */
    val environment: String?,
    /**
     * Base URL the SDK is POSTing to (full URL string). Useful for diagnosing
     * "wrong cluster" misconfigurations.
     */
    val baseUrl: String?,
    /** Active log level. */
    val logLevel: LogLevel,
    /** Locally-persisted anonymous ID (always present once initialized). */
    val anonymousId: String?,
    /** True if `identify(externalId)` has set an external ID. */
    val hasExternalId: Boolean,
    /** True if push registration has stored a device token. */
    val hasDeviceToken: Boolean,
    /**
     * Last-8-char view of the FCM device token, prefixed with `…` (a
     * horizontal ellipsis). Mirrors the dashboard `…<8 chars>` pattern
     * (dashboard PR #134) and iOS so support diffs reconcile cleanly across
     * frontend + backend + SDKs. `null` when no token has been stored.
     *
     * **NEVER** the full token — full tokens are PII-adjacent and are only
     * ever sent to FCM by Firebase / to the SDK's own `/v1/devices`
     * registration endpoint.
     */
    val deviceTokenFingerprint: String?,
    /**
     * Current value of the privacy kill switch. `true` by default; flipped by
     * [Pyrx.setTrackingEnabled].
     */
    val trackingEnabled: Boolean,
    /**
     * Current `POST_NOTIFICATIONS` permission state. Always [GRANTED][
     * PyrxNotificationPermission.GRANTED] on Android < 13 because the
     * runtime permission did not exist before API 33; pre-13 apps that
     * declare the permission in their manifest have it granted implicitly.
     */
    val notificationPermission: PyrxNotificationPermission,
    /**
     * Pending event count on the offline queue at the moment of the snapshot.
     * Always 0 before [Pyrx.initialize] completes.
     */
    val eventQueueDepth: Int,
    /**
     * Wall-clock timestamp (millis-since-epoch) of the last drain attempt
     * (any outcome). `null` until the queue has at least attempted to flush
     * once.
     *
     * iOS surfaces this as `Date?`; Android uses `Long?` because Android
     * does not have a native millisecond-precision wall-clock type that
     * round-trips cleanly through `kotlinx.serialization` — a host app
     * inspecting this can wrap it in `java.util.Date` or `java.time.Instant`
     * trivially.
     */
    val lastDrainAt: Long?,
) {
    public companion object {
        /**
         * Build the `…<last-8>` fingerprint view of a stored FCM device-token
         * string. Returns `null` for an empty/missing token; returns the full
         * string prefixed by `…` if the token is somehow shorter than 8
         * characters (defensive — production FCM tokens are always 140+
         * characters).
         *
         * Surfaced as a static so [Pyrx.debugInfo] can build the view at
         * snapshot time without round-tripping through any service object.
         */
        @JvmStatic
        public fun fingerprint(forDeviceToken: String?): String? {
            if (forDeviceToken.isNullOrEmpty()) return null
            if (forDeviceToken.length <= FINGERPRINT_TAIL_LEN) return "$ELLIPSIS$forDeviceToken"
            val suffix = forDeviceToken.takeLast(FINGERPRINT_TAIL_LEN)
            return "$ELLIPSIS$suffix"
        }

        /** Number of trailing characters preserved in the fingerprint. */
        private const val FINGERPRINT_TAIL_LEN: Int = 8

        /** Horizontal ellipsis prefix on the fingerprint string. */
        private const val ELLIPSIS: String = "…"
    }
}

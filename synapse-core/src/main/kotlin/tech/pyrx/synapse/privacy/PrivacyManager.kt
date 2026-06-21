/*
 * PrivacyManager.kt
 * PYRXSynapse — Android
 *
 * Privacy controls for Phase 8.4b Task 8.4b.10 — `setTrackingEnabled` kill
 * switch + `deleteUser` GDPR cascade + `POST_NOTIFICATIONS` permission
 * awareness.
 *
 * Three separable surfaces, intentionally kept together so the privacy
 * story has one obvious file to read:
 *
 *   1. Tracking gate — actor-isolated `Boolean` that the SDK consults
 *      before draining the event queue. Events still ENQUEUE while disabled
 *      (so a flip back to enabled doesn't lose in-flight intent) but they
 *      DO NOT drain until tracking is re-enabled.
 *
 *   2. Delete user — GDPR right-to-erasure. Wipes EncryptedStore
 *      (anonymousId + externalId + deviceToken), wipes the on-disk event
 *      queue, then POSTs `/v1/contacts/{external_id}/delete` to ask the
 *      backend to cascade its rows. **Local wipe happens BEFORE the
 *      backend call** — if the backend fails, the on-device data is still
 *      gone.
 *
 *   3. Notification permission — Android 13+ runtime `POST_NOTIFICATIONS`.
 *      We READ the grant status only. We DO NOT auto-prompt — that's an
 *      app-level decision. Pre-13 apps report [PyrxNotificationPermission
 *      .GRANTED] because the permission did not exist before API 33.
 *
 * Wire shape for the delete call:
 *
 *     POST /v1/contacts/{external_id}/delete
 *     X-WORKSPACE-ID: …
 *     X-API-KEY:     …
 *     (empty body — the path carries the identifier)
 *
 *     → 200 { "status": "deleted", … }   (backend-defined envelope)
 *     → 404 { "detail": "Contact not found", … }  (no-op — already gone)
 *
 * The SDK swallows 4xx on this call — the user-facing semantic is "your
 * data is gone from this device", which is true regardless of what the
 * server replies. Transport / 5xx errors propagate so callers can surface a
 * "we couldn't reach the server, please try again" message.
 *
 * Mirrors iOS `PrivacyManager.swift` verbatim — same three concerns, same
 * ordering of wipe → backend call, same lenient 4xx swallow.
 */

package tech.pyrx.synapse.privacy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.PyrxLogger
import tech.pyrx.synapse.PyrxNetworkError
import tech.pyrx.synapse.PyrxNotificationPermission
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.queue.EventQueue
import tech.pyrx.synapse.storage.PyrxStorage
import tech.pyrx.synapse.storage.PyrxStorageKey
import java.net.URLEncoder

/**
 * Privacy controls. Owned by [tech.pyrx.synapse.Pyrx]. Construction is
 * cheap — no I/O until a public method is called.
 *
 * Thread-safe — every state mutation goes through [mutex]; reads of the
 * [trackingEnabled] flag are unsynchronised (the field is [Volatile] on the
 * queue's mirror, and stale-read here is harmless because the queue's gate
 * is the one the drain loop actually consults).
 *
 * @param storage Encrypted K/V store the SDK persists identity into.
 * @param queue The offline event queue whose drain gate this manager flips.
 * @param httpClient Wire-level client used to call
 *                   `/v1/contacts/{external_id}/delete`.
 * @param appContext Application context (never an Activity — safe to retain)
 *                   used to read the `POST_NOTIFICATIONS` permission state.
 * @param logger Internal logger.
 */
public class PrivacyManager internal constructor(
    private val storage: PyrxStorage,
    private val queue: EventQueue,
    private val httpClient: HTTPClient,
    private val appContext: Context,
    private val logger: PyrxLogger,
) {
    /** Serialises mutations to [trackingEnabledState]. */
    private val mutex = Mutex()

    /**
     * Default to `true` — the SDK is opt-OUT, not opt-IN. Apps that need
     * stricter defaults can flip this with `setTrackingEnabled(false)` before
     * (or right after) calling `initialize(...)`. The flag is NOT persisted
     * across launches; restore from the host app's preferences store on
     * launch if a sticky opt-out is desired.
     */
    @Volatile
    private var trackingEnabledState: Boolean = true

    /** Current tracking-enabled state. Read by [tech.pyrx.synapse.Pyrx.debugInfo]. */
    public val trackingEnabled: Boolean get() = trackingEnabledState

    // MARK: - Tracking gate

    /**
     * Toggle the SDK's tracking kill switch.
     *
     * When `enabled == false`:
     *   - New `track` / `screen` calls still ENQUEUE events to the on-disk
     *     queue (so the SDK preserves user intent through the flip).
     *   - The drain loop refuses to send anything until tracking is
     *     re-enabled. The next `setTrackingEnabled(true)` automatically
     *     triggers a drain so queued events flush as soon as the user
     *     re-opts-in.
     *
     * When `enabled == true` (the default):
     *   - Normal SDK behaviour — `enqueue` triggers a drain immediately.
     *
     * The flag is NOT persisted across launches. Apps that want a sticky
     * opt-out should restore the choice from their own preferences store on
     * launch and call `setTrackingEnabled(false)` before `initialize(...)` —
     * or right after, before any tracking calls.
     */
    public suspend fun setTrackingEnabled(enabled: Boolean) {
        val transition =
            mutex.withLock {
                val wasEnabled = trackingEnabledState
                trackingEnabledState = enabled
                queue.setTrackingEnabled(enabled)
                wasEnabled to enabled
            }
        announceTransition(wasEnabled = transition.first, enabled = transition.second)
    }

    /**
     * Post-transition side-effects — logger + drain kick. Extracted so
     * [setTrackingEnabled] stays under detekt's complexity budget.
     */
    private suspend fun announceTransition(
        wasEnabled: Boolean,
        enabled: Boolean,
    ) {
        if (enabled && !wasEnabled) {
            // Re-enabling → kick the drain immediately so events buffered
            // during the disabled window flush without waiting for the
            // next track call.
            logger.info { "PrivacyManager: tracking re-enabled — flushing buffered queue." }
            queue.drainNow()
        } else if (!enabled && wasEnabled) {
            logger.info { "PrivacyManager: tracking disabled — events will buffer but not drain." }
        }
    }

    // MARK: - GDPR delete

    /**
     * Right-to-erasure cascade.
     *
     * Order of operations (intentional — local wipe first so a backend
     * failure does NOT leave on-device data behind):
     *
     *   1. Resolve the active `external_id` BEFORE wiping (we'll need it
     *      for the backend call).
     *   2. Wipe the EncryptedStore (`anonymousId`, `externalId`,
     *      `deviceToken`).
     *   3. Wipe the event queue (drop every pending event without sending).
     *   4. POST `/v1/contacts/{external_id}/delete` — IF we had an
     *      external_id (or an anonymousId fallback). Cold-installed SDKs
     *      that never enqueued an event have nothing the backend knows
     *      about, so we skip the backend call.
     *
     * @throws PyrxError.Network on transport / 5xx failure. Local data has
     *         already been wiped at that point — callers should treat the
     *         throw as "tell the user to retry the server side" rather than
     *         "the wipe didn't happen".
     */
    @Throws(PyrxError::class)
    public suspend fun deleteUser() {
        // 1. Capture identity BEFORE we wipe.
        val externalId = storage.get(PyrxStorageKey.EXTERNAL_ID)?.takeIf { it.isNotEmpty() }
        val anonId = storage.get(PyrxStorageKey.ANONYMOUS_ID)?.takeIf { it.isNotEmpty() }
        val backendIdentifier = externalId ?: anonId

        // 2. Wipe local storage. Failures are surfaced via the logger but
        //    do NOT prevent the queue wipe + backend call — the user asked
        //    us to delete their data and we will try every step.
        wipeStorageSafely()

        // 3. Wipe the on-disk event queue.
        queue.wipe()
        logger.info { "PrivacyManager: event queue wiped." }

        // 4. Backend cascade. Skipped if we never had any identifier at all.
        if (backendIdentifier == null) {
            logger.info { "PrivacyManager: no identifier to delete server-side — local wipe complete." }
            return
        }
        deleteBackend(backendIdentifier)
    }

    /**
     * Best-effort storage wipe — failures are logged but do not abort the
     * GDPR cascade. Extracted so [deleteUser] stays under detekt's
     * complexity budget.
     */
    private fun wipeStorageSafely() {
        try {
            storage.wipe()
            logger.info { "PrivacyManager: storage wiped." }
        } catch (e: Throwable) {
            logger.warning { "PrivacyManager: storage wipe failed — ${e.message}" }
        }
    }

    /**
     * Backend half of the GDPR cascade. Pulled out so [deleteUser]'s
     * try/catch budget stays under detekt's `ThrowsCount` ceiling.
     */
    @Throws(PyrxError::class)
    private suspend fun deleteBackend(identifier: String) {
        val path = contactsDeletePath(identifier)
        try {
            httpClient.postPath(path)
            logger.info { "PrivacyManager: backend delete OK for external_id=$identifier." }
        } catch (e: PyrxError.Network) {
            val inner = e.inner
            if (inner is PyrxNetworkError.HttpStatus && inner.statusCode in HTTP_4XX_RANGE) {
                // 4xx: backend says "contact not found" or similar. Local data
                // is already gone — that's the user-visible promise. Log and
                // swallow so callers don't see a confusing "delete failed"
                // when in fact every byte the SDK had IS gone.
                logger.info {
                    "PrivacyManager: backend returned ${inner.statusCode} on delete — " +
                        "treating as already-deleted, local wipe stands."
                }
                return
            }
            // 5xx / transport / decode errors propagate — callers can
            // surface a "couldn't reach server, please try again" message.
            // Local data is still gone at this point.
            logger.warning { "PrivacyManager: backend delete failed — ${e.message}" }
            throw e
        }
    }

    // MARK: - Notification permission awareness

    /**
     * Read the current `POST_NOTIFICATIONS` grant status WITHOUT requesting.
     *
     * Returns:
     *   - [PyrxNotificationPermission.GRANTED] on Android < 13 (the runtime
     *     permission did not exist; notifications were granted implicitly by
     *     app install).
     *   - [PyrxNotificationPermission.GRANTED] on Android 13+ when the user
     *     has approved the runtime prompt.
     *   - [PyrxNotificationPermission.DENIED] on Android 13+ when the user
     *     has declined.
     *   - [PyrxNotificationPermission.NOT_REQUESTED] is reserved for the
     *     pre-init / no-context case — we cannot distinguish "never asked"
     *     from "asked and denied" at the framework level on Android, so
     *     denied + not-asked both surface as DENIED here.
     *
     * This is a READ — we never call `ActivityCompat.requestPermissions`.
     * Notification prompts are an app-level concern: only the host app knows
     * the right moment + UX to ask.
     */
    public fun notificationPermissionStatus(): PyrxNotificationPermission =
        staticNotificationPermissionStatus(appContext)

    // MARK: - Path helper

    public companion object {
        /** HTTP 4xx range — backend delete responses here are treated as success. */
        private val HTTP_4XX_RANGE: IntRange = 400..499

        /**
         * Build `/v1/contacts/{external_id}/delete` with the external_id
         * URL-encoded so identifiers that contain spaces, `+`, or `/`
         * round-trip safely through the path. Surfaced as a static so tests
         * can assert the exact path the SDK will hit without invoking the
         * network layer.
         *
         * Mirrors iOS `PrivacyManager.contactsDeletePath(externalId:)`.
         */
        @JvmStatic
        public fun contactsDeletePath(externalId: String): String {
            // `URLEncoder.encode(s, "UTF-8")` is application/x-www-form-
            // urlencoded — it turns space into `+` and percent-encodes
            // `/`. Both of those are correct for a single path segment.
            val encoded = URLEncoder.encode(externalId, Charsets.UTF_8.name())
            return "/v1/contacts/$encoded/delete"
        }

        /**
         * Static version of [notificationPermissionStatus] — same logic, no
         * instance required. Surfaced so the pre-init `debugInfo` path can
         * report permission status before any [PrivacyManager] has been
         * built.
         *
         * @param context Application context to query. Pass null pre-init
         *                — the function returns
         *                [PyrxNotificationPermission.NOT_REQUESTED] in
         *                that case (we have no way to inspect the system).
         */
        @JvmStatic
        public fun staticNotificationPermissionStatus(context: Context?): PyrxNotificationPermission {
            if (context == null) return PyrxNotificationPermission.NOT_REQUESTED
            // POST_NOTIFICATIONS was introduced in API 33 (Android 13). On
            // pre-13 devices, notifications are granted implicitly when the
            // user installs the app — there is no runtime check to perform.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return PyrxNotificationPermission.GRANTED
            }
            val granted =
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            return if (granted) PyrxNotificationPermission.GRANTED else PyrxNotificationPermission.DENIED
        }
    }
}

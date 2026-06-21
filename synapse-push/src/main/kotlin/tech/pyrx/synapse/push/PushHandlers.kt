/*
 * PushHandlers.kt
 * PYRXSynapse — Android
 *
 * Notification-tap / action-button telemetry handlers (Phase 8.4b Task
 * 8.4b.8). Three concerns, kept side-by-side:
 *
 *   1. recordPushReceived(data)         — fire `$push_received` telemetry
 *                                         when FCM delivers a Synapse push.
 *   2. handleNotificationTap(intent)    — body tap → `POST /v1/push/opened`.
 *   3. handleActionButton(intent, id)   — custom action → `POST /v1/push/click`.
 *
 * Payload contract
 * ================
 *
 * PYRX pushes carry data keys that map onto the FCM `data` field (which
 * Android materialises as Intent string extras when the user taps the
 * notification):
 *
 *   pyrx_push_log_id   : "9b1c8f4a-3a3e-4e1d-9b7f-1c2e3d4e5f6a"
 *   pyrx_tenant_id     : "…"
 *   pyrx_deep_link     : "pyrx://contacts/abc"   (optional)
 *   pyrx_attrs_<key>   : "…"                     (zero or more campaign
 *                                                 attributes, propagated
 *                                                 onto $push_received as
 *                                                 `attributes.<key>`)
 *
 * We treat `pyrx_push_log_id` as the source of truth for telemetry IDs.
 * The `pyrx_attrs_*` prefix lets the campaign emitter attach arbitrary
 * metadata that surfaces as `$push_received.attributes.<key>` — same
 * spirit as iOS's `pyrx_attrs` nested dict, flattened for Android's
 * key-value-string-only Intent extras model.
 *
 * Note on payload representation
 * ------------------------------
 * iOS receives the APNs payload as a nested `[AnyHashable: Any]` map and
 * can model `pyrx` as a sub-dictionary. Android's `RemoteMessage.data` and
 * `Intent.extras` are both flat `Map<String, String>` — Firebase does not
 * preserve nesting. We use the `pyrx_` prefix so we can grep the entire
 * payload without colliding with host-app extras.
 *
 * Cross-platform UUID note
 * ------------------------
 * Java/Kotlin `UUID.toString()` returns LOWERCASE per JDK convention;
 * Swift's `UUID.uuidString` returns UPPERCASE. The Synapse backend MUST
 * accept either case for the `push_log_id` field. We send what we parse
 * — usually the campaign emitter writes lowercase already, but we don't
 * upper-/lower-case before sending.
 *
 * Deep link extraction
 * --------------------
 * The SDK does NOT open deep links itself — Android's Intent system already
 * does this when the FCM `notification.click_action` or a per-notification
 * PendingIntent is configured by the host. The SDK exposes the deep link
 * value for logging / diagnostics; host apps wire their launcher Activity
 * to handle the URI.
 *
 * Mirrors iOS `PushHandlers.swift` semantics with the platform-appropriate
 * substitutions (Intent extras vs. UNNotificationResponse, flat keys vs.
 * nested dict).
 */

package tech.pyrx.synapse.push

import android.content.Intent
import android.util.Log
import tech.pyrx.synapse.network.HTTPClient
import tech.pyrx.synapse.network.JSONValue
import tech.pyrx.synapse.network.PushClickedRequest
import tech.pyrx.synapse.network.PushOpenedRequest
import tech.pyrx.synapse.network.PushTelemetryResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Push-telemetry façade. Owned by [SynapsePushBridge]; not part of the
 * host-app contract. Stateless beyond its injected dependencies — safe to
 * share across threads.
 *
 * @param httpClient The wire-level HTTP client (carries config).
 * @param trackProvider Suspends and enqueues an event through the events
 *                      queue. Used to fire `$push_received` without
 *                      importing the (internal) EventsManager directly.
 */
public class PushHandlers(
    private val httpClient: HTTPClient,
    private val trackProvider: suspend (eventName: String, properties: Map<String, JSONValue>?) -> Unit,
) {
    // MARK: - Push-received telemetry

    /**
     * Fire `$push_received` through the events queue (offline-durable,
     * retried automatically). Returns true if an event was actually enqueued
     * — false means the payload didn't carry the `pyrx_push_log_id` we use
     * to identify Synapse-originated pushes (legacy / cross-vendor pushes
     * pass through silently so analytics doesn't over-count them).
     *
     * Called from [PyrxMessagingService.onMessageReceived]. Public so debug
     * menus can also exercise it.
     *
     * @param data The flat key/value map from `RemoteMessage.data` (FCM)
     *             or `Intent.getExtras()` (notification tap).
     */
    public suspend fun recordPushReceived(data: Map<String, String>): Boolean {
        val pushLogId =
            pushLogId(data) ?: run {
                Log.d(TAG, "recordPushReceived: no pyrx_push_log_id — skipping \$push_received.")
                return false
            }
        val attrs = pyrxAttributes(data, pushLogId)
        try {
            trackProvider(PUSH_RECEIVED_EVENT, attrs)
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "recordPushReceived: track failed — ${e.message}")
            return false
        }
    }

    // MARK: - Notification tap → /v1/push/opened

    /**
     * Bridge a launcher Activity's `Intent` (from `getIntent()` /
     * `onNewIntent(intent)`) into a `POST /v1/push/opened` call.
     *
     * No-op (with a debug log) if the intent doesn't carry a Synapse
     * `pyrx_push_log_id` — the user tapped a non-Synapse push, or this is
     * a plain Activity launch.
     *
     * @param intent The intent received by the launcher Activity.
     */
    public suspend fun handleNotificationTap(intent: Intent) {
        val data = extrasMap(intent)
        val pushLogId =
            pushLogId(data) ?: run {
                Log.d(TAG, "handleNotificationTap: no pyrx_push_log_id — no telemetry to emit.")
                return
            }
        val body =
            PushOpenedRequest(
                pushLogId = pushLogId,
                occurredAt = iso8601Now(),
            )
        try {
            val response: PushTelemetryResponse =
                httpClient.post(
                    endpoint = HTTPClient.Endpoint.PUSH_OPENED,
                    bodySerializer = PushOpenedRequest.serializer(),
                    body = body,
                    responseSerializer = PushTelemetryResponse.serializer(),
                )
            Log.i(
                TAG,
                "push/opened: status=${response.status} envelope=${response.envelopeId ?: "nil"}",
            )
        } catch (e: Throwable) {
            Log.w(TAG, "push/opened: failed — ${e.message}")
        }
    }

    // MARK: - Action button → /v1/push/click

    /**
     * Bridge a custom-action button press into a `POST /v1/push/click`
     * call, with [actionId] as the `click_url` discriminator (backend
     * stores it on `attributes.click_url` per push SDK plan §6.5).
     *
     * No-op (with a debug log) if the intent doesn't carry a Synapse
     * `pyrx_push_log_id`.
     *
     * @param intent The intent that fired the action.
     * @param actionId The action discriminator — sent as `click_url`.
     */
    public suspend fun handleActionButton(
        intent: Intent,
        actionId: String,
    ) {
        val data = extrasMap(intent)
        val pushLogId =
            pushLogId(data) ?: run {
                Log.d(TAG, "handleActionButton: no pyrx_push_log_id — no telemetry to emit.")
                return
            }
        val body =
            PushClickedRequest(
                pushLogId = pushLogId,
                occurredAt = iso8601Now(),
                clickUrl = actionId,
            )
        try {
            val response: PushTelemetryResponse =
                httpClient.post(
                    endpoint = HTTPClient.Endpoint.PUSH_CLICK,
                    bodySerializer = PushClickedRequest.serializer(),
                    body = body,
                    responseSerializer = PushTelemetryResponse.serializer(),
                )
            Log.i(
                TAG,
                "push/click: status=${response.status} action=$actionId",
            )
        } catch (e: Throwable) {
            Log.w(TAG, "push/click: failed — ${e.message}")
        }
    }

    // MARK: - Payload parsing

    /**
     * Parse `pyrx_push_log_id` from a flat key/value payload (either
     * RemoteMessage.data or Intent extras). Returns null on missing or
     * malformed (non-UUID-parseable) values.
     *
     * We require the value to parse as a [java.util.UUID] so a malformed
     * payload doesn't cause the backend to reject the telemetry call. The
     * parsed UUID is stringified back via [java.util.UUID.toString] which
     * is lowercase per JDK convention.
     */
    internal fun pushLogId(data: Map<String, String>): String? {
        val raw = data[KEY_PUSH_LOG_ID] ?: return null
        return try {
            java.util.UUID.fromString(raw).toString()
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "pushLogId: malformed UUID in pyrx_push_log_id='$raw'")
            null
        }
    }

    /**
     * Optional deep-link URI from `pyrx_deep_link`. The SDK does NOT open
     * the URI — Android's Intent system handles that natively via the
     * launcher PendingIntent the campaign emitter configured. We expose the
     * value for diagnostics / logging.
     */
    public fun deepLink(intent: Intent): String? = extrasMap(intent)[KEY_DEEP_LINK]

    /**
     * Walk Intent extras into a flat `Map<String, String>`. Numbers / bools
     * are stringified via [toString] so the downstream parsers can stay
     * uniform.
     */
    private fun extrasMap(intent: Intent): Map<String, String> {
        val bundle = intent.extras ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION") // bundle.get(key) is the only universal accessor
            val value = bundle.get(key) ?: continue
            out[key] = value.toString()
        }
        return out
    }

    /**
     * Snapshot the `pyrx_attrs_*` keys (and the push_log_id itself) into a
     * `Map<String, JSONValue>` for the `$push_received` event's
     * `attributes`. Always non-null when called with a valid [pushLogId]
     * (we stamp push_log_id even if no `pyrx_attrs_*` keys are present).
     */
    internal fun pyrxAttributes(
        data: Map<String, String>,
        pushLogId: String,
    ): Map<String, JSONValue> {
        val converted = mutableMapOf<String, JSONValue>()
        for ((rawKey, value) in data) {
            if (rawKey.startsWith(PREFIX_PYRX_ATTRS)) {
                val key = rawKey.removePrefix(PREFIX_PYRX_ATTRS)
                if (key.isNotEmpty()) {
                    converted[key] = JSONValue.Str(value)
                }
            }
        }
        // SDK-stamped — last write wins so a campaign cannot spoof the id.
        converted[KEY_ATTR_PUSH_LOG_ID] = JSONValue.Str(pushLogId)
        return converted
    }

    // MARK: - Helpers

    /**
     * ISO-8601 wall-clock timestamp for the `occurred_at` field. Built per-
     * call rather than cached because these handlers fire on user
     * interaction — the formatter cost is negligible vs. the network
     * round trip.
     */
    private fun iso8601Now(): String {
        val formatter =
            SimpleDateFormat(ISO_8601_PATTERN, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        return formatter.format(Date())
    }

    public companion object {
        /** Canonical event name fired on incoming Synapse pushes. */
        public const val PUSH_RECEIVED_EVENT: String = "\$push_received"

        /** Intent-extra / RemoteMessage.data key for the push log id. */
        public const val KEY_PUSH_LOG_ID: String = "pyrx_push_log_id"

        /** Intent-extra / RemoteMessage.data key for the deep-link URI. */
        public const val KEY_DEEP_LINK: String = "pyrx_deep_link"

        /**
         * Prefix that identifies a campaign-emitter-attached attribute on
         * a Synapse push payload. Everything after the prefix becomes the
         * attribute key on `$push_received.attributes`.
         */
        public const val PREFIX_PYRX_ATTRS: String = "pyrx_attrs_"

        /** Attribute key under which we re-stamp the push_log_id. */
        public const val KEY_ATTR_PUSH_LOG_ID: String = "push_log_id"

        /** ISO-8601 pattern with millisecond precision + `Z` suffix. */
        private const val ISO_8601_PATTERN: String = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

        /** Logcat tag — matches `PyrxLogger.TAG` for visual grep'ability. */
        private const val TAG: String = "PYRXSynapse"
    }
}

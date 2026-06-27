/*
 * PushReceivedEvent.kt
 * PYRXSynapse — Android
 *
 * Phase 9.2.1 (D4) — observer event payload published whenever a Synapse-
 * originated push lands on the device. Fires in two flavours:
 *
 *   1. [PyrxEvent.PushReceived] — foreground delivery. Published from
 *      `PushHandlers.recordPushReceived` (called by
 *      `PyrxMessagingService.onMessageReceived`).
 *
 *   2. [PyrxEvent.PushReceivedColdStart] — the app was launched from a
 *      tapped notification (the OS started the process to deliver the
 *      tap). Published from `Pyrx.recordColdStartAttribution` AFTER the
 *      existing `$app_opened_from_push` event enqueue.
 *
 * The payload mirrors iOS `PushReceivedEvent` field-for-field so cross-
 * platform docs use one phrasing.
 *
 * Non-Synapse pushes
 * ==================
 * Pushes that did not originate from the Synapse backend (no
 * `pyrx_push_log_id` data key) are NOT published — we only emit observer
 * events for pushes the SDK identifies as ours. The existing
 * `PushHandlers.recordPushReceived` already filters these out by
 * returning `false`; the observer fire-point only runs after that filter.
 * This keeps consumers from seeing third-party FCM pushes on the stream.
 *
 * Cross-platform shape note
 * -------------------------
 * `pushLogId` is a String here (not UUID) because Android's
 * `RemoteMessage.data` and `Intent.extras` are flat `Map<String, String>`
 * — FCM does not preserve typed payloads. We do parse and re-stringify
 * via `UUID.fromString(...)` at the boundary so malformed payloads are
 * filtered before they reach this event; the resulting string is JDK-
 * convention lowercase. iOS surfaces a `UUID?` (uppercase per Swift); the
 * backend accepts either case.
 */

package tech.pyrx.synapse.observer

import java.time.Instant

/**
 * Payload for [PyrxEvent.PushReceived] and [PyrxEvent.PushReceivedColdStart].
 *
 * @property pushLogId Canonical Synapse-side push log identifier (parsed
 *   from the `pyrx_push_log_id` payload key). Always non-null on the
 *   observer stream — pushes lacking this key are filtered upstream.
 *   String-typed (not UUID) to match Android's flat-string FCM payload
 *   convention; value is JDK-convention lowercase.
 * @property title Notification title from the FCM `notification` payload,
 *   or empty string when the push is data-only.
 * @property body Notification body from the FCM `notification` payload,
 *   or empty string when the push is data-only.
 * @property pyrxAttributes Campaign-emitter-attached metadata keys
 *   (extracted from `pyrx_attrs_*` payload entries with the prefix
 *   stripped). Always contains at least `push_log_id` — the SDK
 *   re-stamps the canonical id so a campaign cannot spoof it.
 * @property userInfo The complete raw FCM data payload as a typed map,
 *   for consumers that want unfiltered access (debugging,
 *   custom routing on non-`pyrx_*` keys, etc.). Includes the
 *   `pyrx_*` entries.
 * @property receivedAt Wall-clock instant when the SDK observed the push
 *   (UTC, millisecond precision). Differs from APNs/FCM
 *   server-side `sent_at` by network + service delay.
 */
public data class PushReceivedEvent(
    val pushLogId: String,
    val title: String,
    val body: String,
    val pyrxAttributes: Map<String, PyrxAttributeValue>,
    val userInfo: Map<String, PyrxAttributeValue>,
    val receivedAt: Instant,
)

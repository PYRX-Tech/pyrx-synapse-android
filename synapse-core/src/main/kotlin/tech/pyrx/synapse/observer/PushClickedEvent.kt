/*
 * PushClickedEvent.kt
 * PYRXSynapse — Android
 *
 * Phase 9.2.1 (D4) — observer event payload published whenever the user
 * taps a Synapse-originated push notification or one of its action
 * buttons. Two fire-points map to one [PyrxEvent.PushClicked] type:
 *
 *   - `PushHandlers.handleNotificationTap` — body tap (the user tapped
 *     the notification card itself). `actionId` is null.
 *
 *   - `PushHandlers.handleActionButton` — custom action button press.
 *     `actionId` carries the discriminator the campaign emitter
 *     configured for that button (e.g. "reply", "dismiss", "open").
 *
 * Cold-start dedup
 * ================
 * If the SAME push (same `pushLogId`) caused a cold-start launch AND
 * subsequently arrived through `handleNotificationTap`, ONLY
 * [PyrxEvent.PushReceivedColdStart] fires — the [PyrxEvent.PushClicked]
 * for the cold-start payload is suppressed via a 5-second LRU dedup in
 * `PushHandlers`. This invariant is non-negotiable per the Phase 9.2.1
 * Risk Register item #1 and is JUnit-covered. Warm-start taps (the app
 * was already alive) fire `PushClicked` normally.
 *
 * The payload mirrors iOS `PushClickedEvent` field-for-field.
 */

package tech.pyrx.synapse.observer

import java.time.Instant

/**
 * Payload for [PyrxEvent.PushClicked].
 *
 * @property pushLogId Canonical Synapse-side push log identifier (parsed
 *   from the `pyrx_push_log_id` payload key). Always non-null on the
 *   observer stream — pushes lacking this key are filtered upstream.
 *   String-typed and JDK-convention lowercase, matching
 *   [PushReceivedEvent.pushLogId].
 * @property deepLink Optional URI string from the `pyrx_deep_link` payload
 *   key. The SDK does NOT open the URI — Android's Intent system
 *   handles that natively via the launcher Activity's
 *   PendingIntent. The observer exposes the value for diagnostics
 *   and consumer-controlled routing (e.g., the host app may want
 *   to swap to a different navigation target than the URI).
 * @property actionId The discriminator from the tapped action button, or
 *   `null` for a default body tap. Sent on the wire to
 *   `/v1/push/click` as `click_url`.
 * @property pyrxAttributes Campaign-emitter-attached metadata keys
 *   (extracted from `pyrx_attrs_*` payload entries with the
 *   prefix stripped). Always contains at least `push_log_id`.
 * @property clickedAt Wall-clock instant when the SDK observed the click
 *   (UTC, millisecond precision).
 */
public data class PushClickedEvent(
    val pushLogId: String,
    val deepLink: String?,
    val actionId: String?,
    val pyrxAttributes: Map<String, PyrxAttributeValue>,
    val clickedAt: Instant,
)

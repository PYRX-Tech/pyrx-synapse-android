/*
 * InAppMessage.kt
 * PYRXSynapse — Android
 *
 * Phase 10 PR-2b — public payload delivered to host apps via the in-app
 * messaging surface (`Pyrx.inApp.show(...)`). Wire shape mirrors the
 * backend `InAppMessageSdkPayload` schema (synapse-api/app/schemas/
 * in_app.py) — field names are snake_case on the wire and exposed as
 * idiomatic camelCase properties on the Kotlin data class via
 * `@SerialName`.
 *
 * Authority: ADR-0008 D2 (rendering-callback contract — SDK delivers a
 * typed struct; host app draws the UI), ADR-0009 D5 (cross-SDK
 * symmetric shape — same semantic fields across browser / iOS / Android
 * / RN / Flutter).
 *
 * Lives in synapse-core (not synapse-inapp) so [tech.pyrx.synapse
 * .observer.PyrxEvent.InAppMessageReceived] can wrap it without a
 * circular module dependency. The manager (`InAppManager`) lives in
 * synapse-inapp.
 *
 * Mirrors the browser SDK's `InAppMessage` interface
 * (packages/sdk/src/types.ts:193) and iOS `InAppMessage` struct
 * field-for-field.
 */

package tech.pyrx.synapse.inapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One in-app message delivered to the host app's render callback.
 *
 * **The SDK does NOT render this message** — it hands the typed struct
 * to the host app's `InAppRenderCallback` (registered via `Pyrx.inApp
 * .show(placement, callback)`). The host app draws the UI in whatever
 * style fits its design system. The SDK owns: fetch, in-memory cache,
 * dismissal/impression telemetry, expiry. The SDK does NOT own: pixels,
 * animation, layout, accessibility. PYRX UI Kit is deferred to Phase 10.x.
 *
 * @property id Server-issued assignment id. Pass back via [markInteracted]
 *   / [dismiss] / observer events to identify the message.
 * @property messageId The `in_app_messages.id` — stable across
 *   assignments (re-sends to the same contact carry the same
 *   message id but a new assignment id).
 * @property placement Placement key the host app maps to a UI surface
 *   (e.g., `"home_banner"`, `"settings_modal"`). On the wire the
 *   field is `placement_key`.
 * @property title NLT-rendered title text — ready to render verbatim.
 * @property body NLT-rendered body text — ready to render verbatim.
 * @property imageUrl NLT-rendered image URL, or null when the message
 *   carries no image. Wire field: `image_url`.
 * @property ctas 0..2 call-to-action buttons (Phase 10 v1 scope). Order
 *   is server-controlled and meaningful for the host's button row layout.
 * @property custom Host-app-driven custom JSON. Never NLT-rendered
 *   server-side; the host uses these fields for custom analytics tags,
 *   structured product lists for host-rendered carousels, etc.
 * @property expiresAt ISO-8601 expiry timestamp (string), or null when
 *   the message has no expiry. The SDK does NOT auto-evict expired
 *   messages from the cache — the next poll's server-authoritative
 *   recompute drops them. Wire field: `expires_at`.
 * @property priority Host-app sort/queue priority. Higher = more
 *   important. [tech.pyrx.synapse.Pyrx.inApp.getActive] sorts by
 *   priority desc, then expiry asc.
 */
@Serializable
public data class InAppMessage(
    val id: String,
    @SerialName("message_id")
    val messageId: String,
    @SerialName("placement_key")
    val placement: String,
    val title: String,
    val body: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    val ctas: List<InAppCta> = emptyList(),
    val custom: JsonObject? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    val priority: Int = 0,
)

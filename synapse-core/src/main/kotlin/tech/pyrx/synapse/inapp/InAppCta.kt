/*
 * InAppCta.kt
 * PYRXSynapse — Android
 *
 * Phase 10 PR-2b — call-to-action button shape carried inside
 * [InAppMessage.ctas]. Wire-symmetric with backend `InAppCtaRendered`
 * (synapse-api/app/schemas/in_app.py:123) and the browser SDK's
 * `InAppCta` interface (packages/sdk/src/types.ts:159).
 *
 * Authority: ADR-0009 D5 (cross-SDK symmetric CTA payload).
 */

package tech.pyrx.synapse.inapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One CTA on an [InAppMessage]. NLT source has already been resolved
 * against the current contact at fetch time — [label] and
 * [actionPayload] are ready to render / route verbatim.
 *
 * @property id Stable identifier passed back via [tech.pyrx.synapse.Pyrx
 *   .inApp.markInteracted] on tap. The campaign emitter assigns it.
 * @property label NLT-rendered label text for the button.
 * @property actionType How the host app should handle the tap. See
 *   [InAppCtaActionType]. Wire field: `action_type`.
 * @property actionPayload NLT-rendered action payload. URL string for
 *   `DEEP_LINK` / `WEBVIEW`; opaque string for `CALLBACK`; null/omitted
 *   for `DISMISS`. Wire field: `action_payload`.
 */
@Serializable
public data class InAppCta(
    val id: String,
    val label: String,
    @SerialName("action_type")
    val actionType: InAppCtaActionType,
    @SerialName("action_payload")
    val actionPayload: String? = null,
)

/**
 * The four CTA action discriminators the SDK delivers verbatim from the
 * backend. Symmetric across all 5 SDKs per ADR-0009 D5. Wire form is
 * lowercase snake_case (matched by `@SerialName`); Kotlin idiom is
 * `UPPER_SNAKE_CASE`.
 *
 * - [DEEP_LINK]: open a URI inside the host app's deep-link router.
 * - [DISMISS]: close the message; [InAppCta.actionPayload] is null.
 * - [WEBVIEW]: open a URL in an in-app webview surface.
 * - [CALLBACK]: invoke a host-app-registered callback keyed by the
 *   opaque [InAppCta.actionPayload] string. The SDK does NOT
 *   dispatch the callback — the host app interprets the payload.
 */
@Serializable
public enum class InAppCtaActionType {
    @SerialName("deep_link")
    DEEP_LINK,

    @SerialName("dismiss")
    DISMISS,

    @SerialName("webview")
    WEBVIEW,

    @SerialName("callback")
    CALLBACK,
}

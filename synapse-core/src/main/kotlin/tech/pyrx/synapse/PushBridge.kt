/*
 * PushBridge.kt
 * PYRXSynapse — Android
 *
 * Cross-module seam between synapse-core (Firebase-free) and synapse-push
 * (Firebase-dependent). Declared HERE in synapse-core so [Pyrx] can hold a
 * reference to it without ever importing Firebase types or synapse-push
 * classes — which would create a circular module dependency.
 *
 * The push module installs a concrete [PushBridge] implementation during
 * [Pyrx.initialize]. Apps that don't depend on synapse-push leave the
 * bridge unset; in that case [Pyrx.handleDeviceToken] /
 * [Pyrx.handleNotificationTap] / [Pyrx.handleActionButton] log a warning
 * and no-op rather than crashing.
 *
 * Why an interface rather than direct calls into synapse-push?
 * ============================================================
 *
 * Gradle module structure:
 *
 *   synapse-core      (no Firebase, no Android Intent-extras semantics)
 *      ↑
 *   synapse-push      (depends on synapse-core; adds Firebase Messaging)
 *
 * synapse-core cannot import synapse-push (cycle), but the public API
 * lives on [Pyrx] in synapse-core. The interface is the inversion-of-
 * control mechanism: synapse-push implements it and installs it; synapse-
 * core invokes it through the interface without knowing the concrete type.
 *
 * Mirrors no specific iOS file — iOS is a single Swift target, so the
 * separation isn't needed there. This is Android-specific scaffolding.
 */

package tech.pyrx.synapse

import android.content.Intent
import tech.pyrx.synapse.network.DeviceResponse

/**
 * Service-locator interface for the push module's per-call work. Installed
 * once via [Pyrx.installPushBridge] when [Pyrx.initialize] runs and the
 * push module is on the classpath.
 *
 * Every method is `suspend` (or has a suspend-capable variant) because all
 * three operations involve a network round-trip to the Synapse backend.
 *
 * Implementations MUST be thread-safe — [Pyrx] serialises construction but
 * not subsequent invocations.
 */
public interface PushBridge {
    /**
     * Persist [token] to encrypted storage and POST `/v1/devices` with the
     * full metadata snapshot.
     *
     * @param token The opaque FCM token from `onNewToken(token)` or
     *              `FirebaseMessaging.getInstance().token.await()`.
     * @param externalId The active contact identity — externalId if set by
     *                   `identify()`, otherwise the SDK anonymousId.
     * @return The server's [DeviceResponse] so debug menus can surface the
     *         device id + contact id.
     * @throws PyrxError on transport / HTTP / storage failure.
     */
    @Throws(PyrxError::class)
    public suspend fun registerToken(
        token: String,
        externalId: String,
    ): DeviceResponse

    /**
     * Fire `POST /v1/push/opened` with the `push_log_id` parsed from
     * [intent] extras (FCM puts the data payload there when a notification
     * is tapped from the system tray).
     *
     * No-op (with a warning log) if [intent] does not carry a Synapse
     * `push_log_id` — legacy / cross-vendor pushes pass through silently.
     */
    public suspend fun handleNotificationTap(intent: Intent)

    /**
     * Fire `POST /v1/push/click` with the `push_log_id` from [intent]
     * extras and [actionId] as the `click_url` discriminator (backend
     * stores it on `attributes.click_url` per push SDK plan §6.5).
     */
    public suspend fun handleActionButton(
        intent: Intent,
        actionId: String,
    )

    /** Log a registration failure surfaced from elsewhere. Fire-and-forget. */
    public fun registrationFailed(error: Throwable)
}

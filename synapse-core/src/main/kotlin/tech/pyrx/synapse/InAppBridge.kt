/*
 * InAppBridge.kt
 * PYRXSynapse — Android
 *
 * Phase 10 PR-2b — cross-module seam between synapse-core (network +
 * identity foundation) and synapse-inapp (polling cache + telemetry +
 * lifecycle).
 *
 * Mirrors the [PushBridge] inversion-of-control pattern (see PushBridge
 * .kt). The public `Pyrx.inApp.*` namespace lives in synapse-core (so
 * the cross-SDK-symmetric API surface is reachable without adding an
 * extra module dependency for apps that never use in-app), but the
 * implementation lives in synapse-inapp. Apps that don't depend on
 * synapse-inapp call into `Pyrx.inApp.*` and see a warning log + no-op
 * — same UX as calling `Pyrx.handleDeviceToken` without synapse-push on
 * the classpath.
 *
 * Why an interface
 * ================
 *
 *   synapse-core      (no in-app manager, no polling timer, no telemetry)
 *      ↑
 *   synapse-inapp     (depends on synapse-core; adds the manager)
 *
 * synapse-core cannot import synapse-inapp (cycle). [InAppBridge] is
 * the IoC seam: synapse-inapp implements it via `SynapseInAppBridge`
 * and installs it via `Pyrx.installInAppBridge(bridge)`; synapse-core
 * invokes it through this interface without knowing the concrete type.
 *
 * Mirrors no specific iOS / browser file — Apple/JS have monolithic
 * targets, so the separation isn't needed there. Android-specific
 * scaffolding to keep synapse-core's transitive dependency set lean.
 */

package tech.pyrx.synapse

import tech.pyrx.synapse.inapp.InAppMessage
import tech.pyrx.synapse.inapp.InAppRenderCallback
import tech.pyrx.synapse.inapp.ShowToken

/**
 * Service-locator interface for the in-app manager. Installed once via
 * [Pyrx.installInAppBridge] when the synapse-inapp module's
 * `PyrxInApp.install(context)` runs.
 *
 * The five methods mirror the public `Pyrx.inApp.*` surface 1:1 — the
 * bridge is a thin pass-through that lets synapse-core stay
 * implementation-free.
 *
 * Implementations MUST be thread-safe. The manager uses a [kotlinx
 * .coroutines.sync.Mutex] internally to serialise the in-flight poll;
 * Kotlin coroutines guarantee suspension-point safety across threads.
 */
public interface InAppBridge {
    /**
     * Register a render callback for [placement]. The bridge invokes
     * the callback once per fresh message AND once per already-cached
     * message at registration time (replay).
     *
     * Returns a [ShowToken] that unregisters when closed.
     */
    public fun show(
        placement: String,
        callback: InAppRenderCallback,
    ): ShowToken

    /**
     * Read currently-active messages from the in-memory cache. Does NOT
     * trigger a poll. Filter to a single placement by passing the key;
     * pass `null` to get every active message.
     *
     * Returned list is a defensive copy sorted by priority desc, then
     * expiry asc (mirrors the browser SDK's getActive contract).
     */
    public suspend fun getActive(placement: String?): List<InAppMessage>

    /**
     * Evict [messageId] from the cache and fire the `dismissed`
     * telemetry event. [reason] is host-side observer metadata; per
     * ADR-0008 D2 it does NOT cross the wire (PR-1 backend would 422).
     * Reserved for forward-compat.
     */
    public suspend fun dismiss(
        messageId: String,
        reason: String?,
    )

    /**
     * Fire the `interacted` telemetry event for [messageId] with
     * [ctaId] as the CTA discriminator. Does NOT evict the message —
     * host apps decide whether interaction implies dismissal.
     */
    public suspend fun markInteracted(
        messageId: String,
        ctaId: String,
    )

    /**
     * Force an immediate poll. Coalesces with any in-flight poll (the
     * caller awaits the in-flight one rather than firing a duplicate
     * request). No-op when no placements are registered or the SDK is
     * not yet identified.
     */
    public suspend fun refresh()

    /**
     * Notify the bridge that the SDK's identity transitioned. Called by
     * `Pyrx.identify` / `Pyrx.alias` / `Pyrx.logout` so the bridge can
     * trigger an immediate poll on null→identified and clear its
     * dedupe set on user-switch.
     *
     * The bridge reads [Pyrx]'s current identity from its own snapshot;
     * this method's parameters are intentionally absent — the bridge
     * is allowed to call back into [Pyrx] (one-way coupling from
     * synapse-inapp → synapse-core is fine; the other direction is the
     * cycle we are avoiding).
     */
    public fun notifyIdentityChanged()
}

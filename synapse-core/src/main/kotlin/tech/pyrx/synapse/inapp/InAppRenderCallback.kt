/*
 * InAppRenderCallback.kt
 * PYRXSynapse — Android
 *
 * Phase 10 PR-2b — host-app render callback signature + the
 * [ShowToken] handle returned from [tech.pyrx.synapse.Pyrx.inApp.show].
 *
 * Authority: ADR-0008 D2 (rendering-callback contract). The SDK does
 * NOT render — the callback is where the host app draws the UI; the
 * SDK's job ends at "here's the data."
 */

package tech.pyrx.synapse.inapp

/**
 * Callback signature for [tech.pyrx.synapse.Pyrx.inApp.show]. The SDK
 * invokes this callback once per fresh message matching the registered
 * placement key, AND once per already-cached message at the moment of
 * registration (replay — so a late-registering host doesn't miss
 * messages from a prior poll).
 *
 * The callback runs synchronously on the SDK's "fresh message" code
 * path. Long-running work inside the callback will block the SDK's
 * polling loop — defer heavy work via `launch { ... }` if needed.
 *
 * Exceptions thrown from the callback are caught by the SDK (logged at
 * warning level) so a buggy host doesn't kill the polling loop.
 *
 * Per ADR-0008 D2 the SDK does NOT render — the callback is where the
 * host app draws the UI. The SDK's job ends at "here's the data."
 */
public typealias InAppRenderCallback = (message: InAppMessage) -> Unit

/**
 * Handle returned by [tech.pyrx.synapse.Pyrx.inApp.show]. Closing the
 * token unregisters the render callback for the placement; the SDK
 * stops dispatching cached + fresh messages to that callback.
 *
 * Implements [AutoCloseable] so host apps can use Kotlin's `use { }`
 * scope-function or wire it into a lifecycle observer that calls
 * [close] in `onDestroy`. Idempotent — calling [close] twice is a no-op
 * (the second call's "filter where callback == thisCb" finds nothing).
 *
 * Mirrors the browser SDK's `unregisterFn` return from `synapse('inApp
 * .show', placement, callback)` and iOS's `InAppShowToken` returned from
 * `Pyrx.inApp.show(placement:callback:)`.
 */
public fun interface ShowToken : AutoCloseable {
    /** Unregister the render callback for the placement. Safe to call twice. */
    override fun close()
}

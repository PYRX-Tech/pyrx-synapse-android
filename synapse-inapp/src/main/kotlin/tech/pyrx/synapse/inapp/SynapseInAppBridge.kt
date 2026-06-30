/*
 * SynapseInAppBridge.kt
 * PYRXSynapse — Android — synapse-inapp module
 *
 * Phase 10 PR-2b — concrete [tech.pyrx.synapse.InAppBridge] implementation
 * that wires [InAppManager] into the [tech.pyrx.synapse.Pyrx.inApp]
 * surface in synapse-core.
 *
 * Installation
 * ============
 * Built and installed by [PyrxInApp.install]; not constructed by host
 * apps. `Pyrx.installInAppBridge(bridge)` accepts this implementation
 * and routes every `Pyrx.inApp.*` call through it.
 *
 * Why a separate class?
 * ---------------------
 * Could be an `object PyrxInApp : InAppBridge` — but the bridge holds
 * a single [InAppManager] reference + we want a fresh manager on every
 * install (so re-install during tests doesn't leak polling timers from
 * the previous one). A class lets us keep the manager immutable per
 * install while letting the singleton entry point (`PyrxInApp.install`)
 * re-bridge cleanly.
 */

package tech.pyrx.synapse.inapp

import tech.pyrx.synapse.InAppBridge

/**
 * Concrete bridge — owns one [InAppManager] for the install's lifetime
 * and delegates every cross-module method through to it.
 *
 * Public so [PyrxInApp.install] can construct it; host apps don't.
 */
public class SynapseInAppBridge internal constructor(
    private val manager: InAppManager,
) : InAppBridge {
    override fun show(
        placement: String,
        callback: InAppRenderCallback,
    ): ShowToken = manager.show(placement = placement, callback = callback)

    override suspend fun getActive(placement: String?): List<InAppMessage> = manager.getActive(placement = placement)

    override suspend fun dismiss(
        messageId: String,
        reason: String?,
    ) {
        manager.dismiss(messageId = messageId, reason = reason)
    }

    override suspend fun markInteracted(
        messageId: String,
        ctaId: String,
    ) {
        manager.markInteracted(messageId = messageId, ctaId = ctaId)
    }

    override suspend fun refresh() {
        manager.refresh()
    }

    override fun notifyIdentityChanged() {
        manager.notifyIdentityChanged()
    }

    /**
     * Test-only — expose the underlying manager so coverage of the
     * `Pyrx.inApp.*` → bridge → manager chain can assert on manager
     * state directly. Marked `internal` so production callers cannot
     * reach it.
     */
    internal fun managerForTests(): InAppManager = manager
}

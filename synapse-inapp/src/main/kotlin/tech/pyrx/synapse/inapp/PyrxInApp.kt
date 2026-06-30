/*
 * PyrxInApp.kt
 * PYRXSynapse — Android — synapse-inapp module
 *
 * Phase 10 PR-2b — public entry point that builds an [InAppManager],
 * wraps it in a [SynapseInAppBridge], and installs it on the
 * [tech.pyrx.synapse.Pyrx] singleton.
 *
 * Wiring
 * ======
 * Host apps call `PyrxInApp.install(context)` once after
 * `Pyrx.initialize(...)` — typically from `Application.onCreate`. The
 * call is idempotent and cheap; subsequent calls replace the bridge
 * (the previous bridge's manager is shut down to release the polling
 * timer and the offline log queue).
 *
 * Mirrors the synapse-push module's `PyrxPush.install(context)` entry
 * point shape — single static method, no host-app DI required, safe to
 * call before identify.
 */

package tech.pyrx.synapse.inapp

import android.content.Context
import android.util.Log
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.network.OkHttpSession

/**
 * Entry point for the synapse-inapp module. Call once per app process
 * after [Pyrx.initialize] has completed:
 *
 *     class App : Application() {
 *         override fun onCreate() {
 *             super.onCreate()
 *             GlobalScope.launch {
 *                 Pyrx.initialize(this@App, config)
 *                 PyrxInApp.install(this@App)
 *             }
 *         }
 *     }
 *
 * Safe to call before [Pyrx.identify] — the manager pauses polling
 * until identity lands (rule 1), then auto-kicks an immediate poll
 * (rule 2) when [Pyrx.identify] fires [InAppBridge.notifyIdentityChanged].
 *
 * Returns `true` if the bridge was installed; `false` when
 * [Pyrx.initialize] has not yet completed (the host app is calling
 * [install] before init — common race during very early lifecycle
 * code; retry from a coroutine after init).
 */
public object PyrxInApp {
    @Volatile
    private var installedManager: InAppManager? = null

    /**
     * Construct + install the synapse-inapp bridge.
     *
     * @param context Any [Context] — `applicationContext` is taken
     *   internally so passing an Activity does not leak it.
     * @return `true` on successful install; `false` when
     *   [Pyrx.initialize] has not completed yet (try again from a
     *   coroutine after init).
     */
    @Suppress("UNUSED_PARAMETER")
    public fun install(context: Context): Boolean {
        val hooks = Pyrx.inAppHooks()
        if (hooks == null) {
            // Pyrx.initialize hasn't completed yet — the host should
            // retry after the initialize coroutine resumes.
            Log.w(
                TAG,
                "PyrxInApp.install: Pyrx.initialize has not completed yet — install skipped.",
            )
            return false
        }

        // Tear down any previously-installed manager so re-install
        // (e.g., during tests or hot-reload) doesn't leak the prior
        // polling timer + log queue.
        installedManager?.shutdown()

        val manager =
            InAppManager(
                config = hooks.config,
                session = OkHttpSession(),
                contactIdProvider = hooks.contactIdProvider,
                eventPublisher = hooks.eventPublisher,
                logger = { msg -> Log.d(TAG, msg) },
            )
        installedManager = manager

        val bridge = SynapseInAppBridge(manager)
        Pyrx.installInAppBridge(bridge)

        // Seed the manager with the SDK's CURRENT identity — if the
        // host called identify() BEFORE install(), the manager must
        // know about it without waiting for the next identify call.
        manager.notifyIdentityChanged()
        return true
    }

    /**
     * Test-only — explicitly install a pre-built [InAppManager]
     * (typically constructed with a test-fixture HTTPSession). Used by
     * the synapse-inapp test suite to exercise the end-to-end
     * `Pyrx.inApp.*` → bridge → manager chain without going through
     * the production [OkHttpSession]. Not part of the host-app API.
     */
    internal fun installForTesting(manager: InAppManager) {
        installedManager?.shutdown()
        installedManager = manager
        Pyrx.installInAppBridge(SynapseInAppBridge(manager))
    }

    /**
     * Test-only — shut down the installed manager and detach. Not part
     * of the host-app API.
     */
    internal fun resetForTesting() {
        installedManager?.shutdown()
        installedManager = null
    }

    private const val TAG: String = "PYRXSynapse"
}

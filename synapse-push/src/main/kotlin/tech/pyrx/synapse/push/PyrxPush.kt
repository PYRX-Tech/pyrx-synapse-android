/*
 * PyrxPush.kt
 * PYRXSynapse — Android
 *
 * Public installer for the synapse-push module. Host apps call
 * [PyrxPush.install] either from `Application.onCreate` (explicit,
 * recommended) or implicitly via [PyrxMessagingService.onCreate] (lazy,
 * first time FCM dispatches a message to the service). Both paths converge
 * on the same installed [SynapsePushBridge].
 *
 * Typical host-app wiring (recommended):
 *
 *     class MyApp : Application() {
 *         override fun onCreate() {
 *             super.onCreate()
 *             // PYRX SDK setup
 *             val config = PyrxConfig(workspaceId, apiKey)
 *             runBlocking { Pyrx.initialize(this@MyApp, config) }
 *             // Wire push (idempotent — safe to skip; lazy install also works)
 *             PyrxPush.install(this)
 *         }
 *     }
 *
 * If a host app skips [install], the bridge installs itself the first time
 * `PyrxMessagingService.onCreate()` runs (which happens before FCM hands
 * the service its first [com.google.firebase.messaging.RemoteMessage] or
 * token). This keeps simple integrations working without explicit
 * Application.onCreate wiring.
 *
 * Thread-safety: install is idempotent and re-entrant. Multiple calls with
 * the same hooks are no-ops; a re-init of the SDK (Pyrx.resetForTesting +
 * Pyrx.initialize) requires a re-install — we surface that as a warning
 * log on the next bridge-needing call.
 */

package tech.pyrx.synapse.push

import android.content.Context
import android.util.Log
import tech.pyrx.synapse.Pyrx

/**
 * Public installer entry point for synapse-push. Idempotent and safe to
 * call from multiple lifecycle hooks.
 */
public object PyrxPush {
    /**
     * Install the synapse-push bridge onto [Pyrx]. No-op if [Pyrx.initialize]
     * hasn't completed (we log a warning and return — the host app must
     * call this AFTER initialize). Re-install with the same hooks is a
     * no-op; re-install with different hooks replaces the bridge.
     *
     * @param context Any [Context] — `applicationContext` is taken
     *                internally so passing an Activity won't leak it.
     * @return true if the bridge was installed (or already installed),
     *         false if [Pyrx.initialize] hasn't run yet.
     */
    public fun install(context: Context): Boolean {
        val hooks = Pyrx.pushHooks()
        if (hooks == null) {
            Log.w(
                TAG,
                "PyrxPush.install: Pyrx not initialized — " +
                    "call Pyrx.initialize() before PyrxPush.install().",
            )
            return false
        }

        val registration =
            PushRegistration(
                context = context.applicationContext,
                storage = hooks.storage,
                httpClient = hooks.httpClient,
                environment = hooks.environment,
            )
        val handlers =
            PushHandlers(
                httpClient = hooks.httpClient,
                trackProvider = hooks.trackProvider,
            )
        val bridge =
            SynapsePushBridge(
                registration = registration,
                handlers = handlers,
            )
        Pyrx.installPushBridge(bridge)
        rememberInstalledBridge(bridge)
        return true
    }

    /**
     * Return the currently installed [SynapsePushBridge] (i.e. installed
     * by this module via [install]). Used by [PyrxMessagingService] to
     * fire `$push_received` on incoming pushes without re-resolving every
     * dependency.
     *
     * Public visibility for cross-package access within synapse-push; not
     * part of the host-app contract.
     */
    public fun installedBridge(): SynapsePushBridge? = installedBridgeRef

    /**
     * Snapshot of the bridge produced by [install]. `@Volatile` so a
     * concurrent install from another thread becomes visible without a
     * memory-barrier dance.
     */
    @Volatile
    private var installedBridgeRef: SynapsePushBridge? = null

    /** Cache the install. Called only from [install]. */
    internal fun rememberInstalledBridge(bridge: SynapsePushBridge) {
        installedBridgeRef = bridge
    }

    /** Test-only reset between cases. */
    internal fun resetForTesting() {
        installedBridgeRef = null
    }

    private const val TAG: String = "PYRXSynapse"
}

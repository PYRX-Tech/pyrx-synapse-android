/*
 * Reachability.kt
 * PYRXSynapse — Android
 *
 * Thin wrapper around [android.net.ConnectivityManager.NetworkCallback] that
 * publishes "we just became reachable" notifications to the [EventQueue]
 * drain loop. API 21+ APIs only — no SystemConfiguration / legacy broadcast
 * receivers.
 *
 * Why a protocol seam:
 *
 *   - Unit tests need a deterministic way to simulate reachability
 *     transitions without going through [android.net.ConnectivityManager]
 *     (which depends on the device's network state and cannot be mocked
 *     from outside its module).
 *
 *   - The queue listens for "satisfied" transitions (offline → online).
 *     Tests inject a hand-rolled [Reachability] and emit
 *     [ReachabilityStatus.SATISFIED] to drive the drain loop.
 *
 * Production conformance: [NetworkCallbackReachability] registers a
 * [ConnectivityManager.NetworkCallback] for any internet-capable network
 * and forwards every transition to a [kotlinx.coroutines.flow.SharedFlow].
 *
 * Mirrors iOS `Reachability.swift` (NWPathMonitor-backed) verbatim.
 */

package tech.pyrx.synapse.queue

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reachability transition the queue cares about. We do NOT distinguish
 * wifi / cellular / wired — the queue treats any "satisfied" path as
 * "try to drain". The server's environment header derivation does not
 * depend on the transport.
 */
public enum class ReachabilityStatus {
    SATISFIED,
    UNSATISFIED,
}

/**
 * Shared reachability source. Implementations MUST be thread-safe; the
 * queue subscribes once and consumes the flow until the queue itself is
 * deallocated.
 *
 * Public so a host app can inject a custom implementation in tests (e.g.,
 * if they want to drive both the SDK queue and their own retry loops from
 * a single mock).
 */
public interface Reachability {
    /**
     * Hot flow of reachability transitions. Implementations should emit the
     * current path status when first subscribed so the queue knows whether
     * to attempt an initial drain.
     */
    public val status: Flow<ReachabilityStatus>

    /** Start listening for network changes. Idempotent. */
    public fun start()

    /** Stop listening. Idempotent. */
    public fun stop()
}

/**
 * Production [Reachability] backed by [ConnectivityManager.NetworkCallback].
 *
 * @param context Any [Context] — we capture `applicationContext` internally
 *                so an Activity reference isn't leaked across the SDK's
 *                lifetime.
 */
public class NetworkCallbackReachability(
    context: Context,
) : Reachability {
    private val appContext: Context = context.applicationContext
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val flow = MutableSharedFlow<ReachabilityStatus>(replay = 1, extraBufferCapacity = 16)
    private val started = AtomicBoolean(false)
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                flow.tryEmit(ReachabilityStatus.SATISFIED)
            }

            override fun onLost(network: Network) {
                flow.tryEmit(ReachabilityStatus.UNSATISFIED)
            }
        }

    override val status: Flow<ReachabilityStatus>
        get() = flow.asSharedFlow()

    @SuppressLint("MissingPermission")
    override fun start() {
        if (!started.compareAndSet(false, true)) return
        val cm = connectivityManager ?: return

        // Seed the flow with the current state so the queue's initial
        // subscribe doesn't need to wait for the first transition.
        flow.tryEmit(currentStatus())

        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        runCatching { cm.registerNetworkCallback(request, networkCallback) }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        val cm = connectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
    }

    /**
     * One-shot probe of the current path status. Used to seed the flow on
     * [start] so subscribers see an initial value without waiting for the
     * next OS callback.
     */
    @SuppressLint("MissingPermission")
    private fun currentStatus(): ReachabilityStatus {
        val cm = connectivityManager ?: return ReachabilityStatus.UNSATISFIED
        val active = cm.activeNetwork ?: return ReachabilityStatus.UNSATISFIED
        val caps = cm.getNetworkCapabilities(active) ?: return ReachabilityStatus.UNSATISFIED
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            ReachabilityStatus.SATISFIED
        } else {
            ReachabilityStatus.UNSATISFIED
        }
    }
}

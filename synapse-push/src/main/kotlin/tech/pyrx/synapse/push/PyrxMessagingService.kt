/*
 * PyrxMessagingService.kt
 * PYRXSynapse — Android
 *
 * FirebaseMessagingService subclass — the SDK's primary entry point for
 * Firebase Cloud Messaging callbacks (Phase 8.4b Task 8.4b.7 + 8.4b.8).
 *
 * Declared in synapse-push's AndroidManifest.xml; manifest merger picks it
 * up automatically when a host app declares
 * `implementation("tech.pyrx.synapse:synapse-push:<version>")`. Host apps
 * that want custom behaviour (notification styling, multi-SDK routing) can
 * subclass this service and re-declare it in their own manifest using
 * `tools:replace="android:name"`.
 *
 * Service callbacks
 * =================
 *
 * `onCreate()`             — lazy-install [PyrxPush.install] in case the host
 *                            app didn't call it from `Application.onCreate`.
 * `onNewToken(token)`      — forward to `Pyrx.handleDeviceToken(token)` →
 *                            POST /v1/devices.
 * `onMessageReceived(rm)`  — fire `$push_received` via the events queue.
 *
 * Concurrency
 * -----------
 * FirebaseMessagingService callbacks run on a background thread (Firebase's
 * own dispatcher pool). We bridge to coroutines via [kotlinx.coroutines
 * .runBlocking] because the service has no `lifecycleScope` and the
 * callback contract requires us to return only after our work is enqueued
 * (Firebase may release the WakeLock once `onMessageReceived` returns).
 *
 * For long work (the network POSTs in PushRegistration / PushHandlers),
 * we DO use suspend functions and DO block — Firebase tolerates ~10 seconds
 * of work in `onMessageReceived` and we're well under that.
 */

package tech.pyrx.synapse.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import tech.pyrx.synapse.Pyrx

/**
 * Public — host apps either accept the default service via manifest
 * merger, or subclass it. Subclasses MUST call `super.onCreate()`,
 * `super.onNewToken(token)`, and `super.onMessageReceived(message)` so
 * the SDK's hooks still fire.
 */
public open class PyrxMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        // Lazy install — no-op if the host app already called
        // PyrxPush.install from Application.onCreate, or if Pyrx hasn't
        // been initialised yet (we'll log and skip; the next onNewToken /
        // onMessageReceived will retry).
        PyrxPush.install(applicationContext)
    }

    // `onNewToken(String)` is deprecated as of firebase-messaging 25.x in
    // favour of `onRegistered(String)`, but the new method is only
    // delivered when the *Android Registration* SDK side ships it — every
    // current FCM token-delivery path STILL fires `onNewToken` on every
    // supported app environment. Suppressing the deprecation keeps us
    // compatible with the broadest BoM range; PR-7 docs explicitly
    // describe both lifecycles.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "onNewToken: registering with backend (len=${token.length})")
        // If install hasn't run yet (Pyrx initialise pending), retry now.
        if (PyrxPush.installedBridge() == null) {
            PyrxPush.install(applicationContext)
        }
        try {
            runBlocking {
                Pyrx.handleDeviceToken(token)
            }
        } catch (e: Throwable) {
            // Surface as a log + bridge-side registrationFailed so the
            // host's diagnostics catch it; FCM does not have a retry hook
            // on onNewToken so we log + move on.
            Log.w(TAG, "onNewToken: handleDeviceToken threw — ${e.message}", e)
            Pyrx.handleRegistrationError(e)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val bridge =
            PyrxPush.installedBridge() ?: run {
                // Try to install now — Pyrx may have initialised between
                // onCreate (which ran while Pyrx was still pending) and the
                // first message delivery.
                PyrxPush.install(applicationContext)
                PyrxPush.installedBridge() ?: run {
                    Log.w(TAG, "onMessageReceived: no bridge installed — dropping push telemetry.")
                    return
                }
            }
        try {
            runBlocking {
                bridge.recordPushReceived(message.data)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "onMessageReceived: recordPushReceived threw — ${e.message}", e)
        }
    }

    private companion object {
        private const val TAG: String = "PYRXSynapse"
    }
}

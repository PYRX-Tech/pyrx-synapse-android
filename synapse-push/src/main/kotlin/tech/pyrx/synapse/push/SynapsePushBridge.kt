/*
 * SynapsePushBridge.kt
 * PYRXSynapse — Android
 *
 * Concrete [tech.pyrx.synapse.PushBridge] implementation that wires
 * [PushRegistration] (POST /v1/devices) and [PushHandlers] (POST /v1/push/
 * {opened,click}) into the [tech.pyrx.synapse.Pyrx] singleton.
 *
 * Installation
 * ============
 * Built and installed by [PyrxPush.install]; not constructed by host apps.
 * `Pyrx.installPushBridge(bridge)` accepts this implementation and routes
 * every call to `Pyrx.handleDeviceToken` / `handleNotificationTap` /
 * `handleActionButton` through it.
 *
 * Why a separate class?
 * ---------------------
 * Could be an `object PyrxPush : PushBridge` — but we'd have to share state
 * across init paths (re-install with new hooks, etc.). A class lets us
 * keep the dependencies immutable per install.
 */

package tech.pyrx.synapse.push

import android.content.Intent
import tech.pyrx.synapse.PushBridge
import tech.pyrx.synapse.PyrxError
import tech.pyrx.synapse.network.DeviceResponse

/**
 * Concrete bridge — owns [PushRegistration] (device registration) and
 * [PushHandlers] (tap / action telemetry).
 *
 * Public so [PyrxPush.install] can construct it; host apps don't.
 */
public class SynapsePushBridge internal constructor(
    private val registration: PushRegistration,
    private val handlers: PushHandlers,
) : PushBridge {
    @Throws(PyrxError::class)
    override suspend fun registerToken(
        token: String,
        externalId: String,
    ): DeviceResponse = registration.register(token = token, externalId = externalId)

    override suspend fun handleNotificationTap(intent: Intent) {
        handlers.handleNotificationTap(intent)
    }

    override suspend fun handleActionButton(
        intent: Intent,
        actionId: String,
    ) {
        handlers.handleActionButton(intent = intent, actionId = actionId)
    }

    override fun registrationFailed(error: Throwable) {
        android.util.Log.e(TAG, "handleRegistrationError: FCM failure — ${error.message}", error)
    }

    /**
     * Recorder for incoming pushes — used by [PyrxMessagingService
     * .onMessageReceived]. NOT part of the [PushBridge] interface (which
     * is reachable from synapse-core's Pyrx surface); kept here so the
     * service can call into the bridge without re-resolving the handlers.
     */
    internal suspend fun recordPushReceived(data: Map<String, String>): Boolean = handlers.recordPushReceived(data)

    private companion object {
        private const val TAG: String = "PYRXSynapse"
    }
}

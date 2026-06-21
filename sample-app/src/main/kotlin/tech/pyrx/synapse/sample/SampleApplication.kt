/*
 * SampleApplication.kt
 * PYRXSynapse — Android sample app
 *
 * Application subclass — sole responsibility is to initialise the SDK
 * from `onCreate` so every Activity / Service has a ready-to-use `Pyrx`
 * singleton.
 *
 * Two SDK setup calls run here:
 *   1. `Pyrx.initialize(this, config)` — wires storage, queue, and HTTP
 *      client. Must run before any other Pyrx call.
 *   2. `PyrxPush.install(this)` — opt-in install of the push module's
 *      `SynapsePushBridge`. Idempotent; the FCM service also installs
 *      lazily on first message delivery, so an app that wants the
 *      simplest possible wiring could skip this.
 *
 * Configuration
 * -------------
 * Real credentials are NEVER bundled. The sample uses placeholder UUID
 * + API key constants below. To run against a live backend:
 *   - copy `google-services.json.placeholder` → `sample-app/google-services.json`
 *     and replace with the real Firebase config from your project.
 *   - replace `SAMPLE_WORKSPACE_ID` and `SAMPLE_API_KEY` below with values
 *     from your Synapse dashboard (Settings → Developers → API keys).
 *   - rebuild and install.
 *
 * Threading
 * ---------
 * `Pyrx.initialize` is a suspend function. We bridge via `runBlocking` on
 * Application.onCreate because we need the SDK ready before any Activity
 * fires up — `runBlocking` here is the AGP-recommended pattern (the cost
 * is ~10ms and only happens at process start, never on a UI thread that's
 * already running).
 */

package tech.pyrx.synapse.sample

import android.app.Application
import android.util.Log
import kotlinx.coroutines.runBlocking
import tech.pyrx.synapse.LogLevel
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxEnvironment
import tech.pyrx.synapse.push.PyrxPush
import java.util.UUID

public class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        try {
            val config =
                PyrxConfig(
                    workspaceId = SAMPLE_WORKSPACE_ID,
                    apiKey = SAMPLE_API_KEY,
                    environment = PyrxEnvironment.SANDBOX,
                    baseUrl = SAMPLE_BASE_URL,
                    logLevel = LogLevel.DEBUG,
                )
            runBlocking {
                Pyrx.initialize(this@SampleApplication, config)
            }
            // Install the push bridge so the demo Push screen can drive the
            // synapse-push surface. Idempotent — safe to call here even
            // though PyrxMessagingService also installs lazily on first FCM
            // delivery.
            PyrxPush.install(this)
            Log.i(TAG, "PYRX Synapse SDK initialised — environment=${config.environment}")
        } catch (t: Throwable) {
            // Don't crash the sample app — log + keep the UI usable so QA
            // can still inspect screens. The error usually means the
            // placeholder credentials are still in place; the README walks
            // through the fix.
            Log.e(TAG, "PYRX Synapse SDK initialisation failed", t)
        }
    }

    private companion object {
        private const val TAG: String = "PYRXSynapseSample"

        // ===========================================================
        // Placeholder credentials — REPLACE BEFORE RUNNING AGAINST PROD
        // ===========================================================
        // Generated UUIDv4; not a real workspace. The events POST will be
        // rejected by the backend until the host-app developer fills in
        // their own workspace UUID (Settings → Developers → API keys).
        private val SAMPLE_WORKSPACE_ID: UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000000")

        // Placeholder API key. Format matches `psk_{env}_{32-hex}` so the
        // SDK passes config validation without any code edits — but the
        // backend will reject the value at the first event POST.
        private const val SAMPLE_API_KEY: String =
            "psk_test_00000000000000000000000000000000"

        // Default Synapse events endpoint. Swap to a staging URL if the
        // host-app developer is testing against a non-prod environment.
        private const val SAMPLE_BASE_URL: String = "https://synapse-events.pyrx.tech"
    }
}

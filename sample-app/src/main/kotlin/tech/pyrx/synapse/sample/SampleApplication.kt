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
import tech.pyrx.synapse.sample.BuildConfig
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
        // Config sourced from BuildConfig (sample-app/build.gradle.kts)
        // ===========================================================
        // The PYRX_* constants are baked into BuildConfig at compile time
        // from Gradle properties (project ``gradle.properties`` or, for
        // secrets like the API key, user-global ``~/.gradle/gradle.properties``
        // — see the comment on ``providers.gradleProperty`` in
        // sample-app/build.gradle.kts). Defaults are production-safe
        // placeholders. Override for dev tunnel testing in
        // ``~/.gradle/gradle.properties``:
        //
        //     pyrx.workspaceId = <UUID from /settings/workspace>
        //     pyrx.apiKey      = psk_test_<32-hex from /settings/api-keys>
        //     pyrx.baseUrl     = https://<first-level-subdomain>.pyrx.tech
        //
        // See sample-app/README.md "Local push notification testing".
        private val SAMPLE_WORKSPACE_ID: UUID =
            UUID.fromString(BuildConfig.PYRX_WORKSPACE_ID)
        private val SAMPLE_API_KEY: String = BuildConfig.PYRX_API_KEY
        private val SAMPLE_BASE_URL: String = BuildConfig.PYRX_BASE_URL
    }
}

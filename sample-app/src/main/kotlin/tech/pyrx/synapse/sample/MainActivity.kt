/*
 * MainActivity.kt
 * PYRXSynapse — Android sample app
 *
 * Compose entry point. Hosts a Material3 bottom-navigation shell that
 * routes between the five demo screens. Each demo screen exercises one
 * SDK subsystem so QA (and host-app developers reading this as a
 * reference) can verify behaviour piece-by-piece.
 *
 * Why no Navigation library?
 * --------------------------
 * The sample deliberately uses raw `var selectedTab: BottomTab` instead of
 * androidx.navigation. Goal: keep the dependency graph tiny so a host-app
 * developer reading this file can isolate "what SDK call does what" from
 * "what nav library am I looking at". The cost (no back-stack between
 * tabs) is acceptable for a demo.
 *
 * Why does this Activity bridge `Intent` into the SDK?
 * ----------------------------------------------------
 * When the user taps a Synapse push, Android delivers the FCM `data`
 * payload as Intent string extras to the launcher Activity. The SDK does
 * NOT receive the tap directly — there is no equivalent of iOS's
 * `UNUserNotificationCenterDelegate` for the body-tap path. The host app
 * MUST forward the Intent to the SDK from BOTH `onCreate` (cold-launch:
 * the OS started the process to deliver this tap) AND `onNewIntent` (warm-
 * launch: the process was already alive). Skipping either path silently
 * loses telemetry — the user sees the notification, the tap registers on
 * device, but `push_logs.opened_at` on the backend stays `NULL`.
 *
 * Two SDK calls fire on every tap, in this order:
 *   1. [Pyrx.recordColdStartLaunch] — fires `$app_opened_from_push` for
 *      attribution. No-op if the intent has no Synapse payload.
 *   2. [Pyrx.handleNotificationTap] — `POST /v1/push/opened` for delivery
 *      telemetry. No-op if the intent has no Synapse payload.
 *
 * Both are safe to invoke on every Activity launch — the contract is
 * documented on [Pyrx.handleNotificationTap] (PushHandlers.kt:122). See
 * `docs/PUSH_SETUP.md` §7 for the host-app integration recipe this file
 * implements.
 */

package tech.pyrx.synapse.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold-launch path: the OS may have started this process to deliver
        // a notification tap. Forward the activity's launch intent into the
        // SDK BEFORE building UI so telemetry is enqueued ASAP.
        handlePushIntent(intent)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SampleAppShell()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm-launch path: process was already alive, FCM tap delivered a
        // fresh intent. setIntent so subsequent getIntent() reads see the
        // latest payload (Android convention — singleTop activities lose
        // the new intent otherwise).
        setIntent(intent)
        handlePushIntent(intent)
    }

    /**
     * Bridge a launcher Intent into the Synapse SDK's push-tap telemetry
     * surface. Both calls are no-ops on intents that don't carry a Synapse
     * payload (`pyrx_push_log_id`), so it's safe to invoke on every
     * Activity launch — including ordinary "user opened the app" launches.
     *
     * Fires:
     *   - `$app_opened_from_push` (analytics event, recordColdStartLaunch)
     *   - `POST /v1/push/opened` (delivery telemetry, handleNotificationTap)
     */
    private fun handlePushIntent(intent: Intent?) {
        if (intent == null) return
        lifecycleScope.launch {
            Pyrx.recordColdStartLaunch(intent)
            Pyrx.handleNotificationTap(intent)
        }
    }
}

private enum class BottomTab(val label: String, val icon: ImageVector) {
    Identity("Identity", Icons.Filled.Person),
    Events("Events", Icons.Filled.Bolt),
    Push("Push", Icons.Filled.NotificationsActive),
    Observer("Observer", Icons.Filled.Visibility),
    Debug("Debug", Icons.Filled.BugReport),
    Privacy("Privacy", Icons.Filled.Lock),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleAppShell() {
    var selected by remember { mutableStateOf(BottomTab.Identity) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "PYRX Synapse Sample · ${selected.label}") })
        },
        bottomBar = {
            NavigationBar {
                BottomTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(text = tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (selected) {
            BottomTab.Identity -> IdentityScreen(modifier = modifier)
            BottomTab.Events -> EventsScreen(modifier = modifier)
            BottomTab.Push -> PushScreen(modifier = modifier)
            BottomTab.Observer -> ObserverScreen(modifier = modifier)
            BottomTab.Debug -> DebugInfoScreen(modifier = modifier)
            BottomTab.Privacy -> PrivacyScreen(modifier = modifier)
        }
    }
}

/*
 * DebugInfoScreen.kt
 * PYRXSynapse — Android sample app
 *
 * Demo of `Pyrx.debugInfo()` — the snapshot the support team uses to
 * diagnose host-app integrations. Dumps every field of the data class as
 * a key:value list (rendered manually so we don't pull in a JSON-pretty
 * dependency just for the sample).
 *
 * Use this screen to verify after each demo action:
 *   - identify → hasExternalId flips to true
 *   - logout → hasExternalId flips back to false
 *   - track / screen → eventQueueDepth ticks up (drains back to 0 fast
 *     when the backend is reachable)
 *   - push token registered → hasDeviceToken true,
 *     deviceTokenFingerprint shows last 8 chars
 *   - setTrackingEnabled(false) → trackingEnabled flips
 */

package tech.pyrx.synapse.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxDebugInfo

@Composable
public fun DebugInfoScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<PyrxDebugInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { Pyrx.debugInfo() }
            .onSuccess {
                info = it
                error = null
            }
            .onFailure { error = it.message }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                scope.launch {
                    runCatching { Pyrx.debugInfo() }
                        .onSuccess {
                            info = it
                            error = null
                        }
                        .onFailure { error = it.message }
                }
            },
        ) {
            Text(text = stringResource(id = R.string.debug_action_refresh))
        }

        error?.let {
            Text(text = "debugInfo() error: $it", color = MaterialTheme.colorScheme.error)
        }

        info?.let { snapshot ->
            // Hand-rendered key:value pretty-print so the screen reads as a
            // diagnostic dump without dragging in a JSON library.
            val lines =
                listOf(
                    "sdkVersion" to snapshot.sdkVersion,
                    "platform" to snapshot.platform,
                    "initialized" to snapshot.initialized.toString(),
                    "workspaceId" to (snapshot.workspaceId?.toString() ?: "—"),
                    "environment" to (snapshot.environment ?: "—"),
                    "baseUrl" to (snapshot.baseUrl ?: "—"),
                    "logLevel" to snapshot.logLevel.toString(),
                    "anonymousId" to (snapshot.anonymousId ?: "—"),
                    "hasExternalId" to snapshot.hasExternalId.toString(),
                    "hasDeviceToken" to snapshot.hasDeviceToken.toString(),
                    "deviceTokenFingerprint" to (snapshot.deviceTokenFingerprint ?: "—"),
                    "trackingEnabled" to snapshot.trackingEnabled.toString(),
                    "notificationPermission" to snapshot.notificationPermission.toString(),
                    "eventQueueDepth" to snapshot.eventQueueDepth.toString(),
                    "lastDrainAt" to (snapshot.lastDrainAt?.toString() ?: "—"),
                )
            lines.forEach { (k, v) ->
                Text(
                    text = "$k = $v",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

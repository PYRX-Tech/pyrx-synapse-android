/*
 * PrivacyScreen.kt
 * PYRXSynapse — Android sample app
 *
 * Demo of the privacy surface (Phase 8.4b Task 8.4b.10):
 *   - Pyrx.setTrackingEnabled(true/false) — kill-switch toggle.
 *   - Pyrx.deleteUser() — GDPR cascade (local wipe + server-side delete).
 *
 * `setTrackingEnabled` is presented as a Material Switch so the toggle's
 * state matches the SDK's state exactly. `deleteUser` is a destructive
 * action — it always goes through a confirmation dialog so QA can't
 * accidentally wipe state during testing.
 */

package tech.pyrx.synapse.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx

@Composable
public fun PrivacyScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var trackingEnabled by remember { mutableStateOf(true) }
    var showConfirm by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { Pyrx.debugInfo() }
            .onSuccess { trackingEnabled = it.trackingEnabled }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(id = R.string.privacy_tracking_enabled))
            Switch(
                checked = trackingEnabled,
                onCheckedChange = { checked ->
                    trackingEnabled = checked
                    scope.launch {
                        runCatching { Pyrx.setTrackingEnabled(checked) }
                            .onSuccess { lastResult = "setTrackingEnabled($checked)" }
                            .onFailure { lastResult = "setTrackingEnabled failed: ${it.message}" }
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { showConfirm = true },
        ) {
            Text(text = stringResource(id = R.string.privacy_action_delete_user))
        }

        lastResult?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it)
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(text = stringResource(id = R.string.privacy_delete_confirm_title)) },
            text = { Text(text = stringResource(id = R.string.privacy_delete_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        scope.launch {
                            runCatching { Pyrx.deleteUser() }
                                .onSuccess { lastResult = "deleteUser() complete" }
                                .onFailure { lastResult = "deleteUser failed: ${it.message}" }
                        }
                    },
                ) {
                    Text(text = stringResource(id = R.string.privacy_action_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirm = false }) {
                    Text(text = stringResource(id = R.string.privacy_action_cancel))
                }
            },
        )
    }
}

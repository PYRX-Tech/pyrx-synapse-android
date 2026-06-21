/*
 * IdentityScreen.kt
 * PYRXSynapse — Android sample app
 *
 * Demo of the identity surface:
 *   - Pyrx.identify(externalId, traits)
 *   - Pyrx.alias(newExternalId)
 *   - Pyrx.logout()
 *
 * The screen reads the current externalId via Pyrx.debugInfo() so QA can
 * see state transitions immediately after each button press. The text
 * input is the externalId argument — typing "user_42" and pressing
 * `Identify` invokes `Pyrx.identify("user_42")`.
 */

package tech.pyrx.synapse.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import tech.pyrx.synapse.network.JSONValue

@Composable
public fun IdentityScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var externalId by remember { mutableStateOf("") }
    var currentExternalId by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    // Refresh the current externalId snapshot whenever the screen recomposes.
    // In a production app you'd surface this via a ViewModel + StateFlow;
    // for the demo, polling on entry is good enough.
    LaunchedEffect(Unit) {
        runCatching { Pyrx.debugInfo() }
            .onSuccess { info ->
                currentExternalId = if (info.hasExternalId) "(set)" else null
            }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(id = R.string.identity_current_label))
        Text(text = currentExternalId ?: stringResource(id = R.string.identity_none))

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = externalId,
            onValueChange = { externalId = it },
            label = { Text(text = stringResource(id = R.string.identity_hint_external_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val id = externalId.trim()
                    if (id.isEmpty()) return@Button
                    scope.launch {
                        runCatching {
                            Pyrx.identify(
                                externalId = id,
                                traits =
                                    mapOf(
                                        "source" to JSONValue.Str("sample-app"),
                                        "demo" to JSONValue.Bool(true),
                                    ),
                            )
                        }
                            .onSuccess { lastResult = "identify($id) → $it" }
                            .onFailure { lastResult = "identify failed: ${it.message}" }
                        currentExternalId = "(set)"
                    }
                },
            ) {
                Text(text = stringResource(id = R.string.identity_action_identify))
            }
            Button(
                onClick = {
                    val id = externalId.trim()
                    if (id.isEmpty()) return@Button
                    scope.launch {
                        runCatching { Pyrx.alias(newExternalId = id) }
                            .onSuccess { lastResult = "alias($id) → $it" }
                            .onFailure { lastResult = "alias failed: ${it.message}" }
                    }
                },
            ) {
                Text(text = stringResource(id = R.string.identity_action_alias))
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching { Pyrx.logout() }
                            .onSuccess { lastResult = "logout()" }
                            .onFailure { lastResult = "logout failed: ${it.message}" }
                        currentExternalId = null
                    }
                },
            ) {
                Text(text = stringResource(id = R.string.identity_action_logout))
            }
        }

        lastResult?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it)
        }
    }
}

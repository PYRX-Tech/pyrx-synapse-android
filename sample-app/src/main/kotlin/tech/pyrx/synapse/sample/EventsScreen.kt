/*
 * EventsScreen.kt
 * PYRXSynapse — Android sample app
 *
 * Demo of the events surface:
 *   - Pyrx.track(eventName, properties)
 *   - Pyrx.screen(screenName, properties)
 *
 * The screen lets QA build a one-property `Map<String, JSONValue>` on the
 * fly. This is intentionally minimal — the full SDK supports rich nested
 * JSON via the JSONValue sealed hierarchy; a real host app would build
 * properties at the call site. We expose the shape just so the wire body
 * QA captures has a non-empty `attributes` object.
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
public fun EventsScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var eventName by remember { mutableStateOf("button_tapped") }
    var propKey by remember { mutableStateOf("source") }
    var propValue by remember { mutableStateOf("sample-app") }
    var lastResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = eventName,
            onValueChange = { eventName = it },
            label = { Text(text = stringResource(id = R.string.events_hint_event_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = propKey,
                onValueChange = { propKey = it },
                label = { Text(text = stringResource(id = R.string.events_hint_property_key)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = propValue,
                onValueChange = { propValue = it },
                label = { Text(text = stringResource(id = R.string.events_hint_property_value)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val name = eventName.trim()
                    if (name.isEmpty()) return@Button
                    scope.launch {
                        runCatching {
                            Pyrx.track(
                                eventName = name,
                                properties = buildProps(propKey, propValue),
                            )
                        }
                            .onSuccess { lastResult = "track($name) → enqueued" }
                            .onFailure { lastResult = "track failed: ${it.message}" }
                    }
                },
            ) {
                Text(text = stringResource(id = R.string.events_action_track))
            }
            Button(
                onClick = {
                    val name = eventName.trim()
                    if (name.isEmpty()) return@Button
                    scope.launch {
                        runCatching {
                            Pyrx.screen(
                                screenName = name,
                                properties = buildProps(propKey, propValue),
                            )
                        }
                            .onSuccess { lastResult = "screen($name) → enqueued" }
                            .onFailure { lastResult = "screen failed: ${it.message}" }
                    }
                },
            ) {
                Text(text = stringResource(id = R.string.events_action_screen))
            }
        }

        lastResult?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it)
        }
    }
}

/**
 * Build a single-entry property map from the on-screen K/V fields. Returns
 * null when both fields are blank so we don't ship empty-object attributes
 * — the SDK accepts null and produces a cleaner wire body.
 */
private fun buildProps(
    key: String,
    value: String,
): Map<String, JSONValue>? {
    val k = key.trim()
    val v = value.trim()
    if (k.isEmpty() && v.isEmpty()) return null
    return mapOf(k.ifEmpty { "value" } to JSONValue.Str(v))
}

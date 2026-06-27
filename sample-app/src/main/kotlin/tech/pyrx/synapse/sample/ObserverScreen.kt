/*
 * ObserverScreen.kt
 * PYRXSynapse — Android sample app
 *
 * Phase 9.2.1 — demonstrates the public observer surface introduced in
 * `tech.pyrx.synapse:synapse-core:0.1.4`. Collects `Pyrx.events` inside
 * `lifecycleScope` (via `LaunchedEffect`) and shows received events as a
 * LazyColumn so QA + host-app developers can see exactly what every
 * fire-point publishes — push deliveries, taps, cold-start launches,
 * queue drains, and identity transitions.
 *
 * This screen is the SAME observer pattern an in-host-app `BoxNode` or
 * `Activity.lifecycleScope.launch { ... }` would use. The only adaptation
 * for Compose: collect inside a `LaunchedEffect(Unit)` so the collection
 * coroutine is tied to the composition lifetime; cancellation is
 * automatic when the screen leaves composition.
 *
 * Why also identify / trigger queue / show identityChanged here?
 *   The Identity screen and Events screen also demonstrate the SDK
 *   surfaces that PUBLISH these events. Here we just observe — keeping
 *   the demonstration of the observer-side contract independent of the
 *   producer-side surfaces.
 */

package tech.pyrx.synapse.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.observer.PyrxEvent

/**
 * Single Composable demonstrating `Pyrx.events.collect { ... }`. Shows
 * a reverse-chronological list of received observer events; the most
 * recent is at the top.
 */
@Composable
public fun ObserverScreen(modifier: Modifier = Modifier) {
    var entries by remember { mutableStateOf(listOf<ObserverEntry>()) }

    // Collect inside LaunchedEffect so the coroutine is tied to the
    // composition lifetime. When the screen leaves composition the
    // collection cancels automatically — no leak.
    LaunchedEffect(Unit) {
        Pyrx.events.collect { event ->
            // Prepend so the most recent is at the top. Cap at 50 to
            // keep the list bounded — the SharedFlow itself only
            // retains a 4-event replay buffer; this list is the demo's
            // per-screen history.
            entries = (listOf(ObserverEntry.from(event)) + entries).take(MAX_VISIBLE_EVENTS)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Observer — Pyrx.events",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Collects PyrxEvent emissions in real time. Trigger pushes / " +
                "identify / track on the other tabs to see events appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = "Waiting for events… trigger a push, identify, alias, " +
                        "logout, or track an event to see it land here.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(entries) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = entry.background(),
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = entry.kind,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Display-only projection of a [PyrxEvent]. Kept inside this file
 * because it is purely UI plumbing — the SDK does not export this
 * shape.
 */
private data class ObserverEntry(
    val kind: String,
    val detail: String,
) {
    @Composable
    fun background(): androidx.compose.ui.graphics.Color =
        when {
            kind.startsWith("PushReceived") -> MaterialTheme.colorScheme.primaryContainer
            kind.startsWith("PushClicked") -> MaterialTheme.colorScheme.tertiaryContainer
            kind.startsWith("IdentityChanged") -> MaterialTheme.colorScheme.secondaryContainer
            kind.startsWith("QueueDrained") -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        }

    companion object {
        fun from(event: PyrxEvent): ObserverEntry = when (event) {
            is PyrxEvent.PushReceived -> ObserverEntry(
                kind = "PushReceived",
                detail = "pushLogId=${event.event.pushLogId}\n" +
                    "title=${event.event.title.ifEmpty { "<empty>" }}\n" +
                    "attrs=${event.event.pyrxAttributes.keys}",
            )
            is PyrxEvent.PushClicked -> ObserverEntry(
                kind = "PushClicked",
                detail = "pushLogId=${event.event.pushLogId}\n" +
                    "deepLink=${event.event.deepLink ?: "<none>"}\n" +
                    "actionId=${event.event.actionId ?: "<body>"}",
            )
            is PyrxEvent.PushReceivedColdStart -> ObserverEntry(
                kind = "PushReceivedColdStart",
                detail = "pushLogId=${event.event.pushLogId}\n" +
                    "title=${event.event.title.ifEmpty { "<empty>" }}",
            )
            is PyrxEvent.QueueDrained -> ObserverEntry(
                kind = "QueueDrained",
                detail = "count=${event.count}",
            )
            is PyrxEvent.IdentityChanged -> ObserverEntry(
                kind = "IdentityChanged",
                detail = "before.externalId=${event.before?.externalId ?: "<none>"}\n" +
                    "after.externalId=${event.after.externalId ?: "<none>"}\n" +
                    "after.anonymousId=${event.after.anonymousId ?: "<none>"}",
            )
        }
    }
}

private const val MAX_VISIBLE_EVENTS: Int = 50

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
 */

package tech.pyrx.synapse.sample

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

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SampleAppShell()
                }
            }
        }
    }
}

private enum class BottomTab(val label: String, val icon: ImageVector) {
    Identity("Identity", Icons.Filled.Person),
    Events("Events", Icons.Filled.Bolt),
    Push("Push", Icons.Filled.NotificationsActive),
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
            BottomTab.Debug -> DebugInfoScreen(modifier = modifier)
            BottomTab.Privacy -> PrivacyScreen(modifier = modifier)
        }
    }
}

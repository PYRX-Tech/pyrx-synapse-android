/*
 * PushScreen.kt
 * PYRXSynapse — Android sample app
 *
 * Demo of the push surface:
 *   - Runtime POST_NOTIFICATIONS permission flow (Android 13+).
 *   - FCM token fetch via FirebaseMessaging.getInstance().token.
 *   - Display of the resulting token so a tester can paste it into
 *     `psql` or a backend job to send a manual push.
 *
 * The SDK side of push (registration with the backend, $push_received
 * telemetry, tap/click handlers) is wired up by SampleApplication's call
 * to `PyrxPush.install(this)`. Once a real FCM token is delivered to
 * PyrxMessagingService.onNewToken, `Pyrx.handleDeviceToken(token)` POSTs
 * /v1/devices automatically — no host-app code is needed for that path.
 */

package tech.pyrx.synapse.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

@Composable
public fun PushScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permissionState by remember { mutableStateOf(computePermissionLabel(context)) }
    var fcmToken by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    val permLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            permissionState = if (granted) "GRANTED" else "DENIED"
        }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(id = R.string.push_permission_label))
            Text(text = permissionState)
        }

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Pre-Android 13: notifications are granted on install.
                    permissionState = "GRANTED (pre-Android 13)"
                }
            },
        ) {
            Text(text = stringResource(id = R.string.push_action_request_permission))
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching {
                        // FCM token fetch — async, returns a Task<String>.
                        // We bridge to coroutines via the Tasks await helper
                        // pattern (callback for simplicity).
                        //
                        // `.token` is marked deprecated in firebase-messaging
                        // 25.x in favour of the broader-target getRegistration
                        // API; per PR 4 docs the legacy path remains the
                        // canonical one for every supported BoM and continues
                        // to fire onNewToken. PR 7 docs revisit when the
                        // replacement is universally available.
                        @Suppress("DEPRECATION")
                        FirebaseMessaging.getInstance().token
                            .addOnSuccessListener { token ->
                                fcmToken = token
                                lastResult = "Token fetched (${token.length} chars)"
                                // PR 4 — handing the token to the SDK
                                // triggers POST /v1/devices automatically.
                                // The PyrxMessagingService.onNewToken path
                                // also calls this, so this is for the
                                // explicit-fetch case.
                                scope.launch {
                                    runCatching {
                                        tech.pyrx.synapse.Pyrx.handleDeviceToken(token)
                                    }.onFailure {
                                        lastResult =
                                            "Token registration failed: ${it.message}"
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                lastResult = "FCM fetch failed: ${e.message}"
                            }
                    }.onFailure { lastResult = "FCM call threw: ${it.message}" }
                }
            },
        ) {
            Text(text = stringResource(id = R.string.push_action_show_token))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(text = stringResource(id = R.string.push_token_label))
        Text(
            text = fcmToken ?: stringResource(id = R.string.push_token_placeholder),
        )

        lastResult?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it)
        }
    }
}

private fun computePermissionLabel(context: android.content.Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return "GRANTED (pre-Android 13)"
    }
    val granted =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    return if (granted) "GRANTED" else "NOT_REQUESTED_or_DENIED"
}

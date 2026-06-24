# Push Notification Setup

End-to-end guide for wiring Android push notifications through PYRX Synapse. You'll do this once per app, then PYRX can deliver push from your dashboard, Flows, or the API.

There are six sides to push setup on Android:

1. **Firebase project** — create or reuse a Firebase project for your app.
2. **`google-services.json`** — download and drop into your app module.
3. **FCM service account JSON** — generate for backend access.
4. **PYRX dashboard** — upload the service account JSON.
5. **AndroidManifest.xml** — declare the runtime permission (Android 13+).
6. **Runtime permission request** — request `POST_NOTIFICATIONS` from your UI.

The first four are one-time provisioning. The fifth is one manifest line. The sixth is code.

---

## 1. Create or reuse a Firebase project

If your app already uses Firebase (Analytics, Crashlytics, Remote Config), reuse that project. Otherwise:

1. Visit [console.firebase.google.com](https://console.firebase.google.com/).
2. Click **Add project** → name it (e.g. `MyApp Production`) → continue.
3. Decide whether to enable Google Analytics (optional — push works without it).
4. Click **Create project**.

Inside the project, add your Android app:

1. Click the Android icon on the project overview page.
2. Enter your **package name** (e.g. `com.example.myapp`) — must match your app's `applicationId` in `build.gradle.kts` exactly.
3. (Optional) Enter app nickname + SHA-1 fingerprint (SHA-1 only required if you also use Firebase Authentication or App Check).
4. Click **Register app**.

---

## 2. Download `google-services.json`

After registering the app, Firebase Console offers `google-services.json` for download. Place it at:

```
<your-app-module>/google-services.json
```

For most apps that's `app/google-services.json`.

> **Treat this file as a sensitive build input.** It contains your project number and API key. The file is safe in private repos but should be re-generated or replaced with placeholders if your repo is public. CI builds typically pull this from a secret store and write it during the build job.

Then add the Google Services Gradle plugin:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

// Project-level build.gradle.kts
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// app/build.gradle.kts
plugins {
    id("com.google.gms.google-services")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-messaging")

    // PYRX SDK
    implementation("tech.pyrx.synapse:synapse-core:1.0.0")
    implementation("tech.pyrx.synapse:synapse-push:1.0.0")
}
```

Sync the project. You should see `firebase-messaging` resolve and the Google Services plugin process `google-services.json` at build time.

---

## 3. Generate an FCM service account JSON

PYRX talks to FCM HTTP v1 API on your behalf using a Google Cloud service account.

1. Visit [console.cloud.google.com/iam-admin/serviceaccounts](https://console.cloud.google.com/iam-admin/serviceaccounts).
2. Select the same Google Cloud project that backs your Firebase project (Firebase projects ARE Google Cloud projects — same project ID).
3. Click **Create service account**.
4. Name it (e.g. `pyrx-synapse-fcm`) → continue.
5. Grant the role **Firebase Cloud Messaging API Admin** (`roles/firebasecloudmessaging.admin`) — this is the minimum-privilege role that can call FCM HTTP v1's `messages:send` endpoint.
6. Skip the optional "Grant users access" step → done.
7. Find the new service account in the list → click the three-dot menu → **Manage keys** → **Add key** → **Create new key** → **JSON** → **Create**.
8. A JSON file downloads to your machine. **This is your only chance to download it** — save it somewhere safe.

> Treat the JSON like a password — it grants the ability to send push to any device registered against your Firebase project.

---

## 4. Upload the service account JSON to PYRX

1. Sign in at [synapse-app.pyrx.tech](https://synapse-app.pyrx.tech).
2. Go to **Settings → Push credentials** (`/settings/push-credentials`).
3. Click **Add Android credentials**.
4. Upload the service account JSON file you just downloaded.
5. Enter your app's package name (e.g. `com.example.myapp`) — must match the Android app registered in Firebase.
6. Choose the environment(s) this credential covers:
   - **Sandbox** — debug builds, internal testing.
   - **Production** — Play Store releases, internal/closed/open testing tracks.
   - You can register the same credential for both.
7. Save.

PYRX validates the credential by minting an OAuth token and calling FCM's `messages:send` endpoint with an `validate_only` flag. If validation fails, you'll see the specific reason (invalid JSON, wrong project ID, missing role, etc.).

> Full user-guide walkthrough with screenshots: [synapse.pyrx.tech/docs/user-guide/push-credentials](https://synapse.pyrx.tech/docs/user-guide/push-credentials).

---

## 5. AndroidManifest.xml additions

The `synapse-push` library auto-registers `PyrxMessagingService` via manifest merger — you do NOT need to declare the service yourself.

However, on Android 13+ (API 33) the runtime `POST_NOTIFICATIONS` permission must be declared in YOUR app's manifest (intentionally not in the library — see [synapse-push/src/main/AndroidManifest.xml](../synapse-push/src/main/AndroidManifest.xml) for why):

```xml
<!-- app/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".MyApp"
        ...>
        <!-- Your activities and other components — no service declaration needed. -->
    </application>
</manifest>
```

If you want to subclass `PyrxMessagingService` for custom notification styling, declare your subclass with `tools:replace="android:name"`:

```xml
<application>
    <service
        android:name=".MyMessagingService"
        android:exported="false"
        tools:replace="android:name">
        <intent-filter>
            <action android:name="com.google.firebase.MESSAGING_EVENT" />
        </intent-filter>
    </service>
</application>
```

Make sure your subclass calls `super.onCreate()`, `super.onNewToken(token)`, and `super.onMessageReceived(message)` so the SDK hooks still fire.

---

## 6. Request POST_NOTIFICATIONS at runtime (Android 13+)

Android 13+ requires the user to explicitly grant notification permission. Request it at point of use — after sign-in, after onboarding — NOT on first launch with no context.

In Jetpack Compose:

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun NotificationOptInButton() {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // The OS will deliver onNewToken to PyrxMessagingService shortly.
            // Surface in-app confirmation if you want.
        } else {
            // Show in-app explainer; user must visit Settings to re-enable.
        }
    }

    Button(onClick = {
        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }) {
        Text("Enable notifications")
    }
}
```

In a View-based Activity / Fragment:

```kotlin
private val requestPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    // handle granted/denied
}

// Later, on a user gesture:
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
}
```

The SDK does NOT auto-request `POST_NOTIFICATIONS`. Host apps own the permission UX — same contract as iOS where the host app calls `requestPushPermission`. The SDK only consults the current state and surfaces it via `debugInfo().notificationPermission` for diagnostics.

---

## 7. Wire cold-start attribution + tap handling

When the user taps a notification, Android delivers the FCM data payload as Intent string extras to your launcher Activity. To record telemetry and cold-start attribution, hook your launcher Activity:

```kotlin
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePushIntent(intent)
        // ...
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
    }

    private fun handlePushIntent(intent: Intent) {
        lifecycleScope.launch {
            // Fires $app_opened_from_push if intent carries pyrx_push_log_id.
            Pyrx.recordColdStartLaunch(intent)
            // Fires /v1/push/opened telemetry.
            Pyrx.handleNotificationTap(intent)
        }
    }
}
```

Both calls are safe to invoke on intents that don't carry Synapse payloads — they no-op silently.

> **Critical: wire BOTH `onCreate` AND `onNewIntent`.** Skipping either path silently loses telemetry. `onCreate` covers cold-launch (the OS started your process specifically to deliver this tap). `onNewIntent` covers warm-launch (your process was already in memory). If only `onCreate` is wired, taps that hit a backgrounded-but-alive app will deliver the notification on-device but `push_logs.opened_at` on the backend will stay `NULL`. The reference implementation lives at [sample-app/src/main/kotlin/tech/pyrx/synapse/sample/MainActivity.kt](../sample-app/src/main/kotlin/tech/pyrx/synapse/sample/MainActivity.kt) — copy that pattern verbatim.

For action buttons (e.g. "Reply", "Dismiss"), pull the action ID from your `PendingIntent` extras and call:

```kotlin
Pyrx.handleActionButton(intent = intent, actionId = "reply")
```

---

## 8. Test a push from the PYRX dashboard

1. Install and launch your app on a real device or emulator with Google Play Services.
2. Tap through the `POST_NOTIFICATIONS` prompt → allow notifications.
3. Run `adb logcat -s PYRXSynapse` and confirm:
   - `Initialized PYRXSynapse v1.0.0` (once per launch)
   - `onNewToken: registering with backend (len=…)` (once per first launch after install)
   - No `handleDeviceToken failed` errors
4. In your PYRX dashboard, go to **Contacts** → find the contact for the device's external ID (or anonymous ID — visible in your logs after the first event).
5. Click **Send test push** → fill title + body → **Send**.
6. The push lands on your device within a couple of seconds.

You can also send via the API for scripted testing:

```bash
curl -X POST https://synapse-events.pyrx.tech/v1/push/test \
  -H "X-WORKSPACE-ID: <workspace-uuid>" \
  -H "X-API-KEY: psk_live_..." \
  -H "Content-Type: application/json" \
  -d '{
    "external_id": "user_123",
    "title": "Hello from PYRX",
    "body": "Test push from the API."
  }'
```

---

## Troubleshooting

### "Default FirebaseApp is not initialized in this process"

- Confirm `google-services.json` is at `app/google-services.json` (not the project root).
- Confirm the Google Services Gradle plugin is applied: `plugins { id("com.google.gms.google-services") }` in `app/build.gradle.kts`.
- Clean build (`./gradlew clean`) and rebuild — the plugin processes the JSON at build time.

### `onNewToken` never fires

- Confirm `google-services.json`'s `package_name` matches your app's `applicationId`.
- Confirm the device has Google Play Services installed and updated (most non-Chinese OEMs do).
- Confirm the device has working internet — FCM requires HTTPS to `mtalk.google.com:5228`.
- For first-install behaviour: `onNewToken` is only called once per install/uninstall cycle. If you cleared the token, force-stop the app and relaunch.

### Device registers but pushes don't arrive

- Verify the credential is uploaded for the right environment in PYRX dashboard.
- Verify the package name on the credential matches your app's `applicationId` exactly (case-sensitive).
- Check the PYRX dashboard's **Push delivery logs** for the specific failure (`UNREGISTERED`, `INVALID_ARGUMENT`, `QUOTA_EXCEEDED`, etc.).
- On Android 13+: confirm the user granted `POST_NOTIFICATIONS`. Without it, FCM delivers the message but the OS suppresses the notification. Check `Pyrx.debugInfo().notificationPermission`.

### `handleDeviceToken` throws `PyrxError.Network(PyrxNetworkError.HttpStatus(401, ...))`

- The API key is invalid, expired, or has the wrong scope. Recreate the key in the dashboard with the `data` scope.

### Cold-start attribution not firing

- Confirm `Pyrx.recordColdStartLaunch(intent)` is called from `onCreate(savedInstanceState)` AND `onNewIntent(intent)` of your launcher Activity.
- Confirm `setIntent(intent)` is called in `onNewIntent` BEFORE `recordColdStartLaunch` so subsequent `getIntent()` calls return the new intent.
- This event only fires when the app was COLD-launched (or brought from background) by a push tap. A user opening the app manually, then receiving a push, will not fire this event.

### Multi-SDK conflicts with `FirebaseMessagingService`

Only ONE `FirebaseMessagingService` can be registered per app. If you use another SDK that ships its own service (OneSignal, Braze, Iterable, etc.), choose one of:

1. **Subclass approach** — pick one SDK's service as the primary, subclass it, and dispatch the callbacks to all other SDKs manually. Re-declare with `tools:replace="android:name"`.
2. **Custom dispatch service** — write your own `FirebaseMessagingService` subclass that calls into each SDK's bridge directly.

The PYRX SDK's `PyrxMessagingService` is `open` so you can subclass it. Remember to call `super.onCreate()`, `super.onNewToken(token)`, and `super.onMessageReceived(message)` to keep the SDK hooks firing.

---

## What to read next

- [API Reference](API_REFERENCE.md) — full push API surface.
- [Quickstart](QUICKSTART.md) — five-minute end-to-end setup.
- [Sample app](../sample-app) — runnable Jetpack Compose app with `PushScreen` for live testing.

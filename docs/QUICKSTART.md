# Quickstart

Get the PYRX Synapse Android SDK installed, initialised, identifying users, tracking events, and registered for push notifications in roughly five minutes.

This guide assumes a Jetpack Compose app on Android 5.0+ (API 21) for the core SDK, and Android 6.0+ (API 23) if you depend on `synapse-push`. View-based apps follow the same shape — initialise from `Application.onCreate` the same way.

---

## 1. Get your workspace credentials

You'll need two values from your PYRX dashboard:

- **Workspace ID** — a UUID v4. Visible at `synapse-app.pyrx.tech/settings/workspace`.
- **API key** — formatted `psk_live_…` (production) or `psk_test_…` (sandbox). Create one at `synapse-app.pyrx.tech/settings/api-keys` with the `data` scope (sufficient for events + identity + push registration).

> **Never ship a key with the `management` or `full` scope in your Android app.** Those scopes can read PII and rotate other keys. The SDK only needs `data`.

---

## 2. Add the SDK via Gradle

Add Maven Central if it isn't there already:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
```

Add the dependencies to your app module:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("tech.pyrx.synapse:synapse-core:1.0.0")
    implementation("tech.pyrx.synapse:synapse-push:1.0.0") // optional — only if you use push
}
```

Run `./gradlew :app:dependencies` to confirm resolution.

Maven coordinates if you're not on Gradle Kotlin DSL:

```xml
<dependency>
    <groupId>tech.pyrx.synapse</groupId>
    <artifactId>synapse-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 3. Initialise the SDK

Call `Pyrx.initialize(context, config)` as early as possible in your app lifecycle — typically from your `Application.onCreate`. The SDK is a Kotlin `object`, so `Pyrx.initialize(...)` is the global entry point.

```kotlin
// MyApp.kt
import android.app.Application
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.PyrxEnvironment
import tech.pyrx.synapse.LogLevel
import java.util.UUID

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MainScope().launch {
            try {
                Pyrx.initialize(
                    context = this@MyApp,
                    config = PyrxConfig(
                        workspaceId = UUID.fromString("<YOUR_WORKSPACE_UUID>"),
                        apiKey = "psk_live_<YOUR_API_KEY>",
                        environment = PyrxEnvironment.PRODUCTION,
                        logLevel = LogLevel.INFO,
                    ),
                )
            } catch (t: Throwable) {
                // Log + continue. Subsequent SDK calls will throw
                // PyrxError.NotInitialized until initialize succeeds.
                Log.w("MyApp", "PYRX initialize failed: ${t.message}", t)
            }
        }
    }
}
```

Register the Application class in your `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ... >
```

The SDK generates and persists an anonymous ID on first launch via EncryptedSharedPreferences, so events flow even before you call `identify`.

> Calling `initialize` a second time with the same config is a no-op. Calling with a **different** config throws `PyrxError.AlreadyInitialized` — pick one config per process launch.

---

## 4. Identify users

Once you know who the user is (after sign-in, on app launch if you have a session, etc.), call `identify`:

```kotlin
import tech.pyrx.synapse.network.JSONValue

Pyrx.identify(
    externalId = "user_123",
    traits = mapOf(
        "email" to JSONValue.Str("jane@example.com"),
        "first_name" to JSONValue.Str("Jane"),
        "plan" to JSONValue.Str("pro"),
    ),
)
```

The SDK:

1. Resolves the anonymous ID created at `initialize` time.
2. POSTs `/v1/identify` so the server merges the anonymous contact into the known contact and re-attributes past events + device rows.
3. Persists the `externalId` to EncryptedSharedPreferences. All future events and push registrations use it automatically.

On sign-out, call `logout` to clear the local externalId. The anonymous ID and FCM device token are preserved so the next `identify` can re-attribute cleanly:

```kotlin
Pyrx.logout()
```

---

## 5. Track events

```kotlin
Pyrx.track(
    eventName = "order_placed",
    properties = mapOf(
        "order_id" to JSONValue.Str("ord_abc123"),
        "total" to JSONValue.Num(49.99),
        "currency" to JSONValue.Str("USD"),
        "items" to JSONValue.Int(3),
    ),
)
```

Track returns once the event is durably in the Room-backed queue. The SDK drains the queue in the background with exponential-backoff retry. You don't need to await delivery.

For screen views, use `screen` — it stamps `event_name = "$screen"` and `attributes.screen_name = screenName`:

```kotlin
Pyrx.screen(
    screenName = "product_detail",
    properties = mapOf("product_id" to JSONValue.Str("prod_42")),
)
```

---

## 6. Wire push notifications (optional)

Skip this section if you're not using push.

### 6a. Add the synapse-push dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("tech.pyrx.synapse:synapse-push:1.0.0")
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-messaging")
}

plugins {
    id("com.google.gms.google-services")
}
```

### 6b. Drop `google-services.json` into `app/`

Download from Firebase Console → Project Settings → General → Your apps → Android app → `google-services.json`. Place it at `app/google-services.json`. Never commit production credentials — use a CI secret or `.gitignore` it.

### 6c. Install the push bridge

After `Pyrx.initialize` succeeds, call `PyrxPush.install`:

```kotlin
// MyApp.kt
import tech.pyrx.synapse.push.PyrxPush

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MainScope().launch {
            Pyrx.initialize(this@MyApp, config)
            PyrxPush.install(this@MyApp) // idempotent
        }
    }
}
```

If you forget this, `PyrxMessagingService.onCreate` installs lazily the first time FCM dispatches to the service — but explicit install is the recommended path because it surfaces config issues at app start.

### 6d. The PyrxMessagingService is auto-registered

The `synapse-push` AndroidManifest declares `PyrxMessagingService` via manifest merger. No host-app work required — FCM automatically dispatches `onNewToken` and `onMessageReceived` to it.

If you want a custom subclass (notification styling, multi-SDK routing), subclass `PyrxMessagingService` and re-declare it in your manifest with `tools:replace="android:name"`:

```xml
<service
    android:name=".MyMessagingService"
    android:exported="false"
    tools:replace="android:name">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

Inside `MyMessagingService.onNewToken` / `onMessageReceived`, call `super(...)` first so the SDK's hooks still fire.

### 6e. Request POST_NOTIFICATIONS on Android 13+

Add to your manifest:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Then request at runtime from your UI when it's time to prompt — after sign-in, after onboarding, NOT on first launch with no context:

```kotlin
// In a Composable
val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
) { granted ->
    // granted: Boolean — show in-app explainer if false
}

Button(onClick = { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) {
    Text("Enable notifications")
}
```

The SDK does NOT auto-request `POST_NOTIFICATIONS` — host apps own the permission UX. The SDK only consults the current state and surfaces it via `debugInfo().notificationPermission` for diagnostics.

### 6f. Wire cold-start attribution from your launcher Activity

When the user opens the app via a push tap, you want to record `$app_opened_from_push` so analytics joins the open with the campaign that fired the push:

```kotlin
// MainActivity.kt
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            Pyrx.recordColdStartLaunch(intent)
            Pyrx.handleNotificationTap(intent) // also fires /v1/push/opened
        }
        // ...
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch {
            Pyrx.recordColdStartLaunch(intent)
            Pyrx.handleNotificationTap(intent)
        }
    }
}
```

Safe to call before `Pyrx.initialize` completes — the intent is buffered and replayed when the SDK initialises.

### 6g. Provision PYRX with your FCM service account

Push delivery requires PYRX to talk to FCM on your behalf. Follow [docs/PUSH_SETUP.md](PUSH_SETUP.md) to:

1. Generate an FCM service account JSON in Google Cloud Console.
2. Upload the JSON to your PYRX workspace at `synapse-app.pyrx.tech/settings/push-credentials`.
3. Send a test push from the dashboard and confirm it lands on a real device.

---

## 7. You're done

Verify everything is working:

- Run `adb logcat -s PYRXSynapse` and confirm `Initialized PYRXSynapse v1.0.0` appears once per launch.
- Confirm `POST /v1/events`, `POST /v1/identify`, and `POST /v1/devices` requests return 2xx. Charles / mitmproxy with a user-installed CA work for inspection.
- Visit your PYRX dashboard → **Contacts** → search for your external ID → confirm events appear in the activity timeline.
- Trigger a test push from your PYRX dashboard and confirm it lands on your device.

For diagnostics, call `Pyrx.debugInfo()` and surface the result in a debug menu (the sample app at `sample-app/src/main/kotlin/tech/pyrx/synapse/sample/DebugInfoScreen.kt` has a ready-to-copy implementation).

---

## Where to go next

- [API Reference](API_REFERENCE.md) — every public type and method.
- [Push Setup](PUSH_SETUP.md) — full FCM + PYRX provisioning walkthrough.
- [Sample app](../sample-app) — every SDK surface in a runnable Jetpack Compose project.

# PYRX Synapse Android SDK — Sample App

Jetpack Compose reference implementation that exercises every public SDK
surface end-to-end. Mirrors the iOS `SwiftUIDemo` target case-for-case.

This sample is built as part of Phase 8.4b PR #6 (Task 8.4b.13). It is
**not published to Maven**; it is a build target only. Use it to:

- Verify the SDK boots on a real device with real credentials.
- Copy patterns into your own host app (the screens are deliberately
  written to be readable as documentation, not just demos).
- Run support repros — when a customer's host app misbehaves, asking
  them to reproduce in this sample is often the fastest triage path.

---

## Run requirements

- **Android Studio Hedgehog (2023.1.1) or newer**
- **JDK 17** (the build script enforces `JavaVersion.VERSION_17`)
- **Android SDK 34** (compileSdk) + Build Tools 34.0.0 or newer
- **Physical device or emulator** running **Android 6.0 (API 23) or newer**
  - API 33+ is recommended so you can verify the runtime
    `POST_NOTIFICATIONS` permission flow.

---

## First-run setup

### 1. Add placeholder credentials → real credentials

`sample-app/src/main/kotlin/tech/pyrx/synapse/sample/SampleApplication.kt`
ships with placeholder workspace + API-key constants:

```kotlin
private val SAMPLE_WORKSPACE_ID: UUID =
    UUID.fromString("00000000-0000-0000-0000-000000000000")
private const val SAMPLE_API_KEY: String =
    "psk_test_00000000000000000000000000000000"
```

Replace these with your real values from the Synapse dashboard
(**Settings → Developers → API keys** → "Create new API key" with the
`data` scope). The placeholder values let the app boot — config
validation passes — but every event POST will be rejected by the backend
until you swap them out.

> **Never commit real credentials.** Add a `local.properties` override
> or use Gradle's `secrets-gradle-plugin` if you want a workflow that
> keeps real keys out of source control.

### 2. Wire Firebase (only if you want to test push)

The Push screen will load and the FCM permission button will work
without Firebase. But the actual FCM token fetch will fail with a
configuration error until you wire up Firebase Cloud Messaging:

1. Create or open a Firebase project at
   <https://console.firebase.google.com>.
2. Click **Add app → Android** with package name
   `tech.pyrx.synapse.sample`.
3. Download the real `google-services.json` from the Firebase console.
4. Place it at `sample-app/google-services.json` (replacing the
   placeholder hint file `google-services.json.placeholder` — do NOT
   commit the real one).
5. Apply the Google Services Gradle plugin in
   `sample-app/build.gradle.kts`:

   ```kotlin
   plugins {
       // existing plugins ...
       id("com.google.gms.google-services")
   }
   ```

   And add the classpath at the root `build.gradle.kts` if not already
   present:

   ```kotlin
   buildscript {
       dependencies {
           classpath("com.google.gms:google-services:4.4.1")
       }
   }
   ```

6. Rebuild. `PyrxMessagingService.onNewToken(...)` now delivers a real
   FCM token and `Pyrx.handleDeviceToken(...)` registers it with the
   Synapse backend's `/v1/devices` endpoint.

### 3. Build & install

```bash
# From the repo root:
./gradlew :sample-app:assembleDebug

# Then either install via adb:
adb install sample-app/build/outputs/apk/debug/sample-app-debug.apk

# Or run directly from Android Studio (Run → app: sample-app).
```

---

## Screen-by-screen guide

The bottom navigation bar gives you five tabs, each focused on one SDK
subsystem:

### Identity

- **Text field**: external ID (e.g. `user_42`).
- **Identify**: calls `Pyrx.identify(externalId, traits)` with two demo
  traits.
- **Alias**: calls `Pyrx.alias(newExternalId)`.
- **Logout**: calls `Pyrx.logout()` — clears the external ID locally
  (does NOT call the server).

Verify with the **Debug** tab — `hasExternalId` flips with each action.

### Events

- **Event name** + **property K/V**: build a one-property `Map`.
- **Track**: calls `Pyrx.track(name, properties)`.
- **Screen**: calls `Pyrx.screen(screenName, properties)` (emits the
  canonical `$screen` event).

Verify with the **Debug** tab — `eventQueueDepth` ticks up on enqueue,
drops back to 0 once the drain succeeds.

### Push

- **Permission status**: shows current `POST_NOTIFICATIONS` grant.
- **Request notification permission**: triggers the runtime prompt
  (Android 13+). Pre-13 devices show "GRANTED (pre-Android 13)".
- **Fetch FCM token**: fetches the device's FCM token AND forwards it to
  `Pyrx.handleDeviceToken(...)`. Requires Firebase setup (see above).

Verify on the **Debug** tab — `hasDeviceToken` flips to `true` and
`deviceTokenFingerprint` shows the last 8 characters of the token.

### Debug

- **Refresh**: re-reads `Pyrx.debugInfo()`.
- Dumps every field of the snapshot so QA can inspect SDK state
  without attaching a debugger.

### Privacy

- **Tracking enabled** toggle: calls
  `Pyrx.setTrackingEnabled(true/false)`. When off, enqueue still
  persists but drain refuses to send.
- **Delete user (GDPR)**: confirmation dialog → `Pyrx.deleteUser()`
  cascade. Wipes local storage + queue, then POSTs
  `/v1/contacts/{external_id}/delete` to the backend.

Verify with the **Debug** tab — after `deleteUser`, `hasExternalId`,
`hasDeviceToken`, and `eventQueueDepth` all reset.

---

## End-to-end push test workflow (PR 7 verification)

When PR 7 lands the physical-device CI lane, this is the workflow QA
will use to confirm push works end-to-end. For now, it's documented
here so manual testing follows the same path:

1. Boot the sample on a real device (emulator FCM is unreliable).
2. **Identity** tab → enter `qa_push_test_<your_initials>` → tap
   **Identify**. Wait for Debug to show `hasExternalId = true`.
3. **Push** tab → **Request notification permission** → **Allow**.
4. **Push** tab → **Fetch FCM token**. Wait for the token to appear on
   screen. Copy it.
5. On a developer machine, POST a test push via the Synapse dashboard
   (**Settings → Push test send**) or via direct API:

   ```bash
   curl -X POST https://synapse-api.pyrx.tech/v1/push/test \
        -H "Authorization: Bearer <admin_token>" \
        -H "Content-Type: application/json" \
        -d '{"external_id": "qa_push_test_<your_initials>",
             "title": "PR 6 smoke", "body": "Tap to verify"}'
   ```

6. Confirm the notification appears in the device tray within ~5
   seconds.
7. Tap the notification — the sample's Activity launches.
8. On the **Debug** tab, refresh — verify `eventQueueDepth > 0` then
   drops to 0 (the SDK enqueued `$push_received` then `$push_opened`,
   and drained both).

---

## Files

| Path | Purpose |
|------|---------|
| `build.gradle.kts` | Compose + Material 3 build config |
| `src/main/AndroidManifest.xml` | App + activity + permissions declaration |
| `src/main/kotlin/.../SampleApplication.kt` | SDK initialise in `onCreate` |
| `src/main/kotlin/.../MainActivity.kt` | Compose entry + bottom nav |
| `src/main/kotlin/.../IdentityScreen.kt` | identify / alias / logout demo |
| `src/main/kotlin/.../EventsScreen.kt` | track / screen demo |
| `src/main/kotlin/.../PushScreen.kt` | permission + FCM token demo |
| `src/main/kotlin/.../DebugInfoScreen.kt` | `Pyrx.debugInfo()` viewer |
| `src/main/kotlin/.../PrivacyScreen.kt` | tracking toggle + deleteUser |
| `src/main/res/values/strings.xml` | All user-facing strings |
| `src/main/res/values/themes.xml` | Window/splash theme |
| `google-services.json.placeholder` | Firebase setup hint |
| `README.md` | This file |

---

## Out of scope (in this PR)

- **Physical device verification** — deferred to PR 7.
- **Snapshot tests** — the sample is a manual-test reference; future
  work could add Paparazzi screenshots if regression coverage becomes
  important.
- **Localisation** — strings are English-only. Adding `values-<locale>/`
  is a one-file change if a customer needs it.
- **Custom notification UI** — the sample uses the default FCM-rendered
  notification. Host apps that want custom large-icon / action-button
  rendering should subclass `PyrxMessagingService` per PR 4 docs.

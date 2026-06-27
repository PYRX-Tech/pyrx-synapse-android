# Observers — `Pyrx.events` SharedFlow

> **Available since:** `tech.pyrx.synapse:synapse-core:0.1.4` + `synapse-push:0.1.4`.
>
> **What's new in 0.1.4:** first public streaming surface on the SDK. Observe push deliveries, taps, cold-start launches, queue drains, and identity transitions from a single `SharedFlow<PyrxEvent>` — no delegation, no broadcast receivers, no FCM-internal coupling.

The observer surface is the SDK's first PUBLIC streaming API. It complements the existing imperative `Pyrx.identify(...)` / `Pyrx.track(...)` / `Pyrx.handleNotificationTap(...)` surfaces with a "tell me when something happens inside the SDK" capability that native Android apps + the React Native bridge both consume.

---

## TL;DR

```kotlin
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tech.pyrx.synapse.Pyrx
import tech.pyrx.synapse.observer.PyrxEvent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            Pyrx.events.collect { event ->
                when (event) {
                    is PyrxEvent.PushReceived          -> showInAppToast(event.event.title, event.event.body)
                    is PyrxEvent.PushClicked           -> routeToDeepLink(event.event.deepLink)
                    is PyrxEvent.PushReceivedColdStart -> handleColdStartRoute(event.event)
                    is PyrxEvent.QueueDrained          -> Log.d("Sync", "flushed ${event.count} events")
                    is PyrxEvent.IdentityChanged       -> refetchUserProfile(event.after.externalId)
                }
            }
        }
    }
}
```

Cancellation is automatic when `lifecycleScope` dies. No `unsubscribe` call needed.

---

## The shape

### `Pyrx.events: SharedFlow<PyrxEvent>`

A hot, multi-subscriber, replay-capable stream of every event the SDK publishes. Backed by `MutableSharedFlow(replay = 4, extraBufferCapacity = 16, onBufferOverflow = DROP_OLDEST)`.

**Multi-subscriber:** every collector gets every event. There is no arbitration — your `ViewModel`, your `Activity`, the React Native bridge (when wrapping this SDK) can all subscribe in parallel and each sees the full stream.

**Late-subscriber replay:** subscribers that attach after events have been published receive the most-recent **4** events from the replay buffer. This covers the realistic cold-start race window — one cold-start push + one `identify` on launch + one queue drain + one headroom slot — without inviting stale-event surprise. Events older than the most-recent 4 are lost; if your consumer must see every event from the very start, subscribe as early as possible (e.g., in `Application.onCreate` via a process-scoped `CoroutineScope`).

**Burst tolerance:** `extraBufferCapacity = 16` + `BufferOverflow.DROP_OLDEST` means fast-publishing fire-points (FCM service thread, queue drain success path) never block and never silently fail under realistic load — if the buffer ever fills, the oldest unconsumed event drops to make room.

### `Pyrx.publishEvent(event: PyrxEvent): Boolean`

The cross-module bridge seam. `synapse-push`'s `PushHandlers` and `synapse-core`'s `Pyrx.recordColdStartAttribution` + `EventQueue.drainLoop` call this from their fire-points. **Host apps NEVER call this directly** — the only reason it's `public` is that `synapse-push` lives in a separate Gradle module and needs cross-module visibility.

Returns `true` on successful emission, `false` only if the buffer overflowed AND `DROP_OLDEST` could not make room (impossible with the current buffer sizing — kept in the contract for forward-compat).

---

## The five events

```kotlin
public sealed interface PyrxEvent {
    public data class PushReceived(val event: PushReceivedEvent)            : PyrxEvent
    public data class PushClicked(val event: PushClickedEvent)              : PyrxEvent
    public data class PushReceivedColdStart(val event: PushReceivedEvent)   : PyrxEvent
    public data class QueueDrained(val count: Int)                          : PyrxEvent
    public data class IdentityChanged(
        val before: IdentitySnapshot?,
        val after: IdentitySnapshot,
    ) : PyrxEvent
}
```

### 1. `PushReceived`

Fires when a Synapse-originated push lands on the device while the app is in the foreground (FCM `onMessageReceived` path). Non-Synapse pushes (no `pyrx_push_log_id` payload key) are filtered upstream and do NOT publish.

```kotlin
data class PushReceivedEvent(
    val pushLogId: String,                              // canonical Synapse-side id (lowercase UUID)
    val title: String,                                  // empty for data-only pushes
    val body: String,                                   // empty for data-only pushes
    val pyrxAttributes: Map<String, PyrxAttributeValue>, // pyrx_attrs_* keys (prefix stripped)
    val userInfo: Map<String, PyrxAttributeValue>,       // complete raw FCM data map
    val receivedAt: Instant,
)
```

`pyrxAttributes` always contains a `push_log_id` key with the canonical id — the SDK re-stamps this so a campaign cannot spoof it.

### 2. `PushClicked`

Fires when the user taps a push notification — either the body or a custom action button (distinguished by `actionId`). Fires for foreground **and** warm-start (process-already-alive) taps.

```kotlin
data class PushClickedEvent(
    val pushLogId: String,
    val deepLink: String?,                              // pyrx_deep_link payload value
    val actionId: String?,                              // null for body taps
    val pyrxAttributes: Map<String, PyrxAttributeValue>,
    val clickedAt: Instant,
)
```

> **Cold-start dedup contract (non-negotiable):** if the SAME push (same `pushLogId`) caused a cold-start launch AND subsequently arrived through `handleNotificationTap`, ONLY `PushReceivedColdStart` fires — the paired `PushClicked` is suppressed for 5 seconds. This prevents double-routing in apps that handle deep links on `PushClicked`. A second tap of the same notification within the window legitimately fires `PushClicked` (the dedup consumes the entry on first read; user re-tap is treated as a fresh gesture). Action button taps (`handleActionButton`) are never suppressed — they cannot collide with the cold-start path.

### 3. `PushReceivedColdStart`

Fires when the app was launched from a tapped push (the OS started the process to deliver the tap). Publishes AFTER `Pyrx.initialize` completes and the buffered launch intent is replayed via `Pyrx.recordColdStartLaunch(intent)` → internal `recordColdStartAttribution`.

Carries the same `PushReceivedEvent` shape as `PushReceived` so consumer routing logic can reuse the same payload code path; the case distinction lets you branch on "we came from a push tap" vs "the app was already alive and got a push".

### 4. `QueueDrained(count)`

Fires after `EventQueue.drainLoop` successfully flushes `count` events to `/v1/events`. Debug-oriented — most apps won't subscribe, but it's useful for "synced" indicators and diagnostic dashboards. **No-op drains** (zero successfully flushed) do NOT publish.

### 5. `IdentityChanged(before, after)`

Fires after a successful `Pyrx.identify` / `Pyrx.alias` / `Pyrx.logout` call. Use the `before`/`after` delta to detect transitions:

```kotlin
when {
    event.before?.externalId == null && event.after.externalId != null ->
        "login transition (anonymous → identified)"
    event.before?.externalId != null && event.after.externalId == null ->
        "logout transition (identified → anonymous)"
    event.before?.externalId != event.after.externalId ->
        "user switch"
    else ->
        "trait-only change"
}
```

```kotlin
data class IdentitySnapshot(
    val externalId: String?,    // null when not identified (or after logout)
    val anonymousId: String?,   // SDK-minted UUIDv4; survives all transitions
    val resolvedAt: Instant,
)
```

`before` is `null` ONLY when there was no prior identity state in storage (very first identify after a fresh install). Otherwise both snapshots are non-null.

**Common use:** dashboard-style apps refetch user data on this event instead of polling for login state changes.

---

## Where to collect

`Pyrx.events` is process-scoped — it lives as long as the `Pyrx` singleton (i.e., the host process). Collect inside a coroutine scope that matches your screen/feature lifetime:

| Context | Scope to use |
|---|---|
| Activity | `lifecycleScope.launch { Pyrx.events.collect { ... } }` |
| Fragment view | `viewLifecycleOwner.lifecycleScope.launch { ... }` |
| ViewModel | `viewModelScope.launch { ... }` |
| Compose | `LaunchedEffect(Unit) { Pyrx.events.collect { ... } }` (see `sample-app/ObserverScreen.kt`) |
| Process-wide | A `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` held by your `Application` |

For Compose specifically:

```kotlin
@Composable
fun MyScreen() {
    LaunchedEffect(Unit) {
        Pyrx.events.collect { event ->
            // handle event
        }
    }
}
```

`LaunchedEffect`'s job is tied to the composition lifetime; when the composable leaves composition the collection cancels automatically — no leak.

---

## Threading

`Pyrx.events.collect { ... }` invokes your handler on whatever dispatcher the collecting coroutine uses. The SDK itself does NOT switch threads — if you collect on `lifecycleScope` (Main by default), your handler runs on Main; if you collect on `Dispatchers.IO`, your handler runs on IO.

If you need to touch UI from an off-main collect, switch with `withContext(Dispatchers.Main) { ... }` inside the handler.

`Pyrx.publishEvent` is non-suspending and thread-safe — it can be (and is) called from the FCM service thread without arranging a coroutine scope.

---

## Error handling

Exceptions thrown inside your `collect` handler will cancel the underlying flow's collection coroutine. **Wrap your handler in `try/catch`** if it does work that might throw — otherwise a single bad event teardown can stop your collector from observing subsequent events:

```kotlin
lifecycleScope.launch {
    Pyrx.events.collect { event ->
        try {
            handle(event)
        } catch (t: Throwable) {
            Log.w("Observer", "handler threw on $event", t)
        }
    }
}
```

Errors from the SDK's own publish path (e.g., a fire-point that itself throws) are caught defensively at the publish call site — observer failures cannot break the analytics path, the queue drain, or the wire-side telemetry. Same protection in the other direction.

---

## Forward-compatibility

`PyrxEvent` is a **sealed interface** — `when (event) { ... }` is exhaustive in Kotlin, so you get a compile error if you don't cover all five current cases. **Adding new cases in future minor versions WILL require updating consumer `when` blocks in source-compatible ways** — that's intentional. The compile error IS the signal that there's a new event worth handling.

If you want to be tolerant of future additions without updating immediately, include an `else` branch:

```kotlin
when (event) {
    is PyrxEvent.PushReceived -> { /* ... */ }
    is PyrxEvent.PushClicked -> { /* ... */ }
    is PyrxEvent.PushReceivedColdStart -> { /* ... */ }
    is PyrxEvent.QueueDrained -> { /* ... */ }
    is PyrxEvent.IdentityChanged -> { /* ... */ }
    else -> { /* future cases */ }
}
```

---

## Sample app

The `sample-app/` module includes a new "Observer" tab (`ObserverScreen.kt`) that demonstrates `Pyrx.events.collect { ... }` in Compose. Trigger pushes (Push tab), identify/alias/logout (Identity tab), or track events (Events tab), and watch them appear on the Observer tab in real time.

---

## Cross-platform parity

| Platform | Type | Available since |
|---|---|---|
| Android | `Pyrx.events: SharedFlow<PyrxEvent>` | `synapse-core:0.1.4` (this release) |
| iOS | `Pyrx.shared.observe { ... }` + `Pyrx.shared.events()` (`AsyncStream`) | `PYRXSynapse 0.1.2` (parallel release) |
| React Native | `usePushReceived` / `usePushClicked` / `useDeepLink` / `usePushReceivedColdStart` / `useIdentityChanged` hooks | `@pyrx/synapse-react-native 0.2.0` (downstream release) |

The three SDKs publish the same five events with the same field-for-field payloads, so cross-platform docs use one phrasing.

---

## Plan reference

This surface was designed in [Phase 9.2.1 — Native Callback Observer APIs Across the SDK Ecosystem](https://github.com/PYRX-Tech/pyrx.synapse/blob/master/docs/plans/phase-9.2.1-native-callback-observers-plan-2026-06-27.md). See:

- **D3** — Android observer shape (`SharedFlow<PyrxEvent>` over callback interface / RxJava / LiveData).
- **D4** — Event taxonomy (5 events including `IdentityChanged` per Q3 user override).
- **D6** — `PyrxAttributeValue` promotion from internal `JSONValue`.
- **D7** — Replay buffer of 4 (rationale: cold-start race window).
- **Risk Register item #1** — cold-start vs. click dedup invariant; JUnit-covered.

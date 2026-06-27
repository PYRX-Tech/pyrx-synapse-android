/*
 * IdentitySnapshot.kt
 * PYRXSynapse — Android
 *
 * Phase 9.2.1 (D4 + Q3 user override) — payload component of
 * [PyrxEvent.IdentityChanged], capturing the SDK's resolved identity
 * before / after a successful identify/alias/logout transition.
 *
 * Why this shape (not IdentityResult)?
 * ====================================
 * [tech.pyrx.synapse.identity.IdentityResult] is the SERVER's per-call
 * readout — it describes the merge path the backend took
 * (known_exists / first_sighting / no_anonymous), the count of events /
 * devices re-attributed, whether the anonymous contact was tombstoned.
 * It's the right shape for a return value from `Pyrx.identify(...)` (per-
 * call diagnostics), but it does NOT capture the SDK's resolved identity
 * after the call.
 *
 * Observer consumers want the OTHER shape — "what does this user look
 * like NOW, and what did they look like BEFORE". That's
 * [IdentitySnapshot] — `externalId`, `anonymousId`, the wall-clock at
 * which the snapshot resolved. Dashboard-style apps detect login by
 * `before?.externalId != after.externalId`; logout by `after.externalId
 * == null`; cross-device alias merge by `before?.anonymousId != after
 * .anonymousId` (rare).
 *
 * Lifetime
 * ========
 * Snapshots are taken at the boundary inside `Pyrx.identify/alias/logout`
 * — once before the underlying [IdentityManager] mutates storage, and
 * once after. Both reads go through the same EncryptedSharedPreferences
 * paths the rest of the SDK uses, so the values are consistent with what
 * subsequent `track` / `screen` calls will emit as `external_id`.
 *
 * Forward-compat shape
 * --------------------
 * Adding a future field (e.g., `email`, `traits`) is source-compatible
 * for consumers that use property access and `copy()`. Removing a field
 * would be a breaking change — we don't expect to.
 *
 * Mirrors iOS `IdentitySnapshot` field-for-field.
 */

package tech.pyrx.synapse.observer

import java.time.Instant

/**
 * Snapshot of the SDK's resolved identity at a point in time. Carried
 * pre- and post-transition by [PyrxEvent.IdentityChanged].
 *
 * @property externalId The canonical user identifier the host app called
 *   `identify(externalId, ...)` with, or `null` if no identify has
 *   completed yet (anonymous session). After `logout` this returns
 *   to `null` even if `identify` had previously set it.
 * @property anonymousId The SDK-minted anonymous device identifier (UUIDv4
 *   generated at first launch, persisted forever). Survives
 *   identify / alias / logout — the anonymous id never changes
 *   over the SDK's lifetime on a given install. May be `null`
 *   transiently if storage has not been seeded yet (e.g., the
 *   very first `IdentityChanged` emitted by a fresh-install
 *   `identify` carries `before.anonymousId` after seeding).
 * @property resolvedAt Wall-clock instant the snapshot was taken (UTC,
 *   millisecond precision). For `before`, this is the instant
 *   just before storage mutation; for `after`, just after.
 */
public data class IdentitySnapshot(
    val externalId: String?,
    val anonymousId: String?,
    val resolvedAt: Instant,
)

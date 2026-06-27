/*
 * PyrxAttributeValue.kt
 * PYRXSynapse — Android
 *
 * Phase 9.2.1 (D6) — promotes the internal JSON-shaped wire payload type
 * to a public observer-API alias.
 *
 * Background
 * ==========
 * `tech.pyrx.synapse.network.JSONValue` is already a `public sealed class`
 * (see `Codables.kt`) — it carries arbitrary JSON-shaped payloads across
 * the wire (event attributes, identify traits, device metadata, contact
 * properties). It mirrors the iOS SDK's `JSONValue` sum byte-for-byte and
 * predates the observer surface.
 *
 * Per Phase 9.2.1 D6 we expose the same type to observer consumers under
 * a friendlier name — [PyrxAttributeValue] — so callers reading observer
 * code don't have to reach into a `network.*` package for a type that has
 * nothing to do with HTTP from their perspective. The alias is a re-name,
 * not a re-shape: a [PyrxAttributeValue] IS a [tech.pyrx.synapse.network
 * .JSONValue]; the constructors (e.g. [PyrxAttributeValue.Str]) are
 * spelled identically because they resolve to the same nested classes.
 *
 * Why a typealias instead of a wrapper
 * ------------------------------------
 * A wrapper would force two-way conversions at every fire-point (push
 * handlers already build `JSONValue` maps from FCM payload, and the
 * observer surface needs to pass those same maps through). A typealias is
 * zero-cost, source-compatible, and keeps the existing kotlinx.serialization
 * machinery (`JSONValueSerializer`) intact for any consumer who wants to
 * `Json.encodeToString` an attribute map for diagnostics.
 *
 * Future-compat
 * -------------
 * If we ever need to add observer-only shape (e.g., an opaque "secret"
 * variant the wire JSON does not carry), we promote this alias into a
 * wrapper sealed class with a `.toWire()` extension in the same minor.
 * The public spelling `PyrxAttributeValue.Str("x")` stays the same; only
 * the internal storage changes. Source-compatible expansion path.
 *
 * Mirrors iOS `PyrxAttributeValue` (which is also a renamed `JSONValue`).
 */

package tech.pyrx.synapse.observer

import tech.pyrx.synapse.network.JSONValue

/**
 * Typed JSON-shaped value used for attribute maps on observer events
 * ([PushReceivedEvent.pyrxAttributes], [PushClickedEvent.pyrxAttributes],
 * [PushReceivedEvent.userInfo], [IdentitySnapshot.traits]).
 *
 * Variants — exhaustive `when` is safe and intentional:
 *   - [PyrxAttributeValue.Null]  — JSON null
 *   - [PyrxAttributeValue.Bool]  — boolean
 *   - [PyrxAttributeValue.Int]   — 64-bit signed integer
 *   - [PyrxAttributeValue.Num]   — double-precision floating point
 *   - [PyrxAttributeValue.Str]   — string
 *   - [PyrxAttributeValue.Arr]   — heterogeneous array of values
 *   - [PyrxAttributeValue.Obj]   — string-keyed nested object
 *
 * Implementation note: this is a `typealias` to the SDK's wire-level
 * [tech.pyrx.synapse.network.JSONValue]. The alias exists so observer
 * consumers can stay inside the `tech.pyrx.synapse.observer` package
 * without having to reach into `network.*` for a type whose name suggests
 * it's wire-only. Construct values via the same nested classes:
 *
 *     val attrs: Map<String, PyrxAttributeValue> = mapOf(
 *         "campaign_id" to PyrxAttributeValue.Str("welcome-2026"),
 *         "score"       to PyrxAttributeValue.Int(42),
 *         "premium"     to PyrxAttributeValue.Bool(true),
 *     )
 *
 * Forward-compatibility: if a future minor needs an observer-only variant
 * (e.g., redacted PII), we promote this alias to a wrapper sealed class
 * with a `.toWire()` bridge. The spelling above remains valid.
 */
public typealias PyrxAttributeValue = JSONValue

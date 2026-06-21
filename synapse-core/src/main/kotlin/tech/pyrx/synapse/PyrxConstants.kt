/*
 * PyrxConstants.kt
 * PYRXSynapse — Android
 *
 * Compile-time constants embedded into the SDK. Updated on release (PR 7
 * will wire a release script that bumps `SDK_VERSION` together with the
 * Gradle `version` declarations across all modules).
 *
 * Mirrors iOS `PyrxConstants.swift` — same semver scheme, same platform
 * identifier semantics. The `platform` value is sent verbatim on every
 * outbound request via the `X-PYRX-SDK-PLATFORM` header (wired in PR 2).
 */

package tech.pyrx.synapse

public object PyrxConstants {
    /**
     * SDK semantic version. Sent on `X-PYRX-SDK-VERSION` (header wired in PR 2).
     *
     * Bumped together with each module's Gradle `version` declaration. The
     * release tag (`vX.Y.Z`) in CI must equal this value.
     */
    public const val SDK_VERSION: String = "0.1.2"

    /**
     * Platform identifier. Sent on `X-PYRX-SDK-PLATFORM` (header wired in PR 2).
     * Always `"android"` regardless of underlying device class (phone, tablet,
     * foldable, Auto, Wear). Matches the iOS SDK's `"ios"` value semantics.
     */
    public const val PLATFORM: String = "android"
}

/*
 * PyrxEnvironment.kt
 * PYRXSynapse — Android
 *
 * Runtime environment selector for the SDK. Mirrors iOS `PyrxEnvironment`.
 *
 * `PRODUCTION` routes traffic to the live ingestion endpoint
 * (`https://synapse-events.pyrx.tech`). `SANDBOX` is reserved for
 * staging / QA traffic and currently shares the same default base URL until
 * a staging endpoint is provisioned in a later PR. The backend infers the
 * actual environment from the API key prefix (`psk_live_` vs `psk_test_`).
 */

package tech.pyrx.synapse

public enum class PyrxEnvironment {
    PRODUCTION,
    SANDBOX,
    ;

    /**
     * Wire-level discriminator used in identify / alias / devices request
     * bodies. `PRODUCTION` → `"live"`, `SANDBOX` → `"test"`. The string
     * literal is contractual with the Synapse backend — do not rename.
     */
    public val wireValue: String
        get() =
            when (this) {
                PRODUCTION -> "live"
                SANDBOX -> "test"
            }
}

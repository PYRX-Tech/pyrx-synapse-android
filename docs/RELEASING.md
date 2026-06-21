# Releasing pyrx-synapse-android

Maintainer-only guide for cutting a new SDK release. End users should not need to read this.

The release pipeline publishes to **Maven Central** (via Sonatype OSSRH). Gradle / Maven / Kotlin consumers resolve from Maven Central by declaring `mavenCentral()` in their repository list — no per-consumer extra configuration required.

The pipeline is driven by `.github/workflows/publish.yml`, triggered on push of any `v*` tag (e.g. `v1.0.0`).

---

## Prerequisites (one-time setup)

These items must be in place before the very first release. They do not need to be repeated for subsequent releases unless something rotates.

### Sonatype OSSRH account + DNS verification

Sonatype OSSRH (Open Source Software Repository Hosting) is the staging service that publishes to Maven Central. Our group ID is `tech.pyrx.synapse`.

1. **Create an OSSRH account** at [issues.sonatype.org](https://issues.sonatype.org/) (Jira-based).
2. **File a "New Project" ticket** requesting the `tech.pyrx.synapse` group ID.
3. **Verify DNS ownership** of `pyrx.tech` — Sonatype provides a TXT record value; add it to your `pyrx.tech` DNS as a `TXT` record and reply to the ticket once it propagates.
4. **Wait for approval** — Sonatype reviews tickets manually. Lead time is typically **3-5 business days**. Start this in parallel with PR 7 of the SDK release prep so the credentials are ready when you're ready to tag.

After approval, your OSSRH username + password (the Jira credentials, not Google SSO) can publish to the staging repository.

> Sonatype is migrating from "OSSRH" (s01.oss.sonatype.org) to "Central Portal" during 2026. Verify the current state at [central.sonatype.org](https://central.sonatype.org/) when provisioning — the GitHub Actions workflow in `publish.yml` uses the canonical Gradle task names (`publishReleasePublicationToSonatypeRepository` / `closeAndReleaseSonatypeStagingRepository`) that work on both endpoints.

### GPG signing key

Maven Central requires every artifact to be GPG-signed.

1. **Generate a key** (4096-bit RSA, no passphrase prompt for CI is fine if the key is stored in a CI secret):
   ```bash
   gpg --full-generate-key
   ```
   Choose RSA, 4096 bits, an indefinite expiry (or 1-2 years if you want forced rotation), and a passphrase you'll record in CI secrets.
2. **List your keys** and copy the key ID:
   ```bash
   gpg --list-secret-keys --keyid-format=long
   # sec   rsa4096/ABCDEF1234567890 2026-06-21 [SC]
   ```
3. **Publish your public key** to the standard keyservers (Maven Central verifies signatures against them):
   ```bash
   gpg --keyserver hkps://keys.openpgp.org --send-keys ABCDEF1234567890
   gpg --keyserver hkps://keyserver.ubuntu.com --send-keys ABCDEF1234567890
   ```
4. **Export the private key in ASCII-armored form** for CI:
   ```bash
   gpg --armor --export-secret-keys ABCDEF1234567890 > maven-signing-key.asc
   ```
   You'll paste the contents of this file into a GitHub secret next.

### GitHub repository secrets

Four secrets are required. **Recommended path:** promote them to **PYRX-Tech org-level secrets** (**GitHub Org settings → Secrets and variables → Actions → New organization secret**, scope to `pyrx-synapse-{ios,android,java,...}`). This avoids per-repo duplication when future SDK repos (Web Push, React Native, Flutter — Phase 9) need the same credentials.

**Alternative:** add as `PYRX-Tech/pyrx-synapse-android` repository secrets (**Settings → Secrets and variables → Actions → New repository secret**). This duplicates per-repo but works the same way.

The secret names match `PYRX-Tech/pyrx-synapse-java` (live since 2026-05-02) so any one of org-secrets / java-repo-copy / android-repo-copy will be picked up by the workflow:

| Secret | Value |
|---|---|
| `CENTRAL_USERNAME` | Your Sonatype Central Portal user token name (Account → "Generate User Token"). |
| `CENTRAL_PASSWORD` | The corresponding user token password (same screen). |
| `GPG_PRIVATE_KEY` | The full contents of the ASCII-armored private key (`gpg --armor --export-secret-keys <KEY_ID>`). |
| `GPG_PASSPHRASE` | The passphrase you chose when generating the GPG key. |

Also create a repository **variable** named `MAVEN_PUBLISH_ENABLED` with value `true` (the publish job is gated on this flag so accidentally-pushed tags don't publish before secrets are wired).

> Verify all four secrets are set and the variable is set BEFORE pushing a release tag. If any secret is missing, the publish job exits cleanly without partially publishing (see the `if:` guard in `publish.yml`), but the tag is already pushed and you'll need to clean it up.

---

## Release process

Repeat for each release.

### 1. Final pre-release verification on `main`

```bash
git checkout main
git pull origin main

JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleRelease
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew ktlintCheck detekt
```

All three must pass.

### 2. Update version metadata

Bump the version in `gradle/libs.versions.toml` (or whichever single source you've established):

```toml
# gradle/libs.versions.toml
[versions]
synapse-sdk = "1.0.0"   # change to the new version
```

Also bump `PyrxConstants.SDK_VERSION` in `synapse-core/src/main/kotlin/tech/pyrx/synapse/PyrxConstants.kt`:

```kotlin
public const val SDK_VERSION: String = "1.0.0"
```

> The Gradle `version` and `SDK_VERSION` must stay in lockstep. Future work: automate this via a `buildSrc` script that reads `libs.versions.toml` and generates `PyrxConstants.SDK_VERSION` at build time.

### 3. Update the CHANGELOG

Edit `CHANGELOG.md`:

- Move entries from the `[Unreleased]` section under a new `## [1.0.0] - YYYY-MM-DD` heading.
- Add a fresh empty `[Unreleased]` section at the top for future work.
- Update the comparison links at the bottom (`[Unreleased]: …compare/v1.0.0...HEAD`, `[1.0.0]: …releases/tag/v1.0.0`).
- Keep the format aligned with [Keep a Changelog](https://keepachangelog.com/).

### 4. Commit the version bump

```bash
git add gradle/libs.versions.toml synapse-core/src/main/kotlin/tech/pyrx/synapse/PyrxConstants.kt CHANGELOG.md
git commit -m "chore(release): v1.0.0"
git push origin main
```

### 5. Tag the release

```bash
git tag -a v1.0.0 -m "v1.0.0"
git push origin v1.0.0
```

> The leading `v` is required — the `publish.yml` workflow is triggered by `tags: ['v*']`.

### 6. Watch the publish workflow

Open the Actions tab on GitHub. The `Publish to Maven Central` workflow runs three jobs:

1. **verify** — `./gradlew assembleRelease`, `./gradlew test`, `./gradlew ktlintCheck detekt`. All must pass before the next two jobs run.
2. **publish** — `./gradlew publishReleasePublicationToSonatypeRepository closeAndReleaseSonatypeStagingRepository`. Gated on `vars.MAVEN_PUBLISH_ENABLED == 'true'` and the four signing/credential secrets being set.
3. **github-release** — creates a GitHub Release on the tag with auto-generated release notes.

If any job fails:
- **verify failure** — fix on `main`, push, delete the tag (`git push origin :refs/tags/v1.0.0`), recreate the tag from the new commit, push the tag.
- **publish failure** — the artifacts are NOT published if `closeAndReleaseSonatypeStagingRepository` failed. Common causes: invalid signing key, Sonatype staging repo already has artifacts for that version (drop them in Nexus UI), network blip. Re-run the job from the Actions UI.
- **github-release failure** — usually a permissions issue. The artifacts are already published; manually create the release in the GitHub UI as a fallback.

### 7. Verify the published artifacts

Maven Central propagation typically takes **15-30 minutes** after `closeAndReleaseSonatypeStagingRepository` succeeds.

**Search index:**

```bash
# Check Maven Central search
open "https://search.maven.org/search?q=g:tech.pyrx.synapse"
```

**Resolve from a fresh project:**

```bash
mkdir /tmp/pyrx-release-check && cd /tmp/pyrx-release-check
cat > build.gradle.kts <<'EOF'
plugins { id("java") }
repositories { mavenCentral() }
dependencies {
    implementation("tech.pyrx.synapse:synapse-core:1.0.0")
}
EOF
gradle dependencies --configuration runtimeClasspath
```

If Gradle resolves the dependency without errors, the release is live.

### 8. Announce

- Update the SDK section of the PYRX developer portal docs (`synapse.pyrx.tech/developers/sdks/android`).
- If the release adds a customer-facing feature, post in the PYRX changelog feed.
- Cross-link from the iOS SDK release notes if the version is part of a paired cross-platform release.

---

## Hotfix releases

For urgent bug fixes (e.g. `1.0.0 → 1.0.1`):

1. Branch from the tag of the broken version: `git checkout -b hotfix/1.0.1 v1.0.0`.
2. Apply the fix + tests.
3. Bump `synapse-sdk = "1.0.1"` in `gradle/libs.versions.toml` and `SDK_VERSION` in `PyrxConstants.kt`.
4. PR to `main`, merge.
5. Follow steps 5–8 of the standard release process from `main`.

If `main` has diverged with breaking changes that can't go in a patch, cherry-pick the fix onto a dedicated `release/1.0.x` branch and tag from there. Maven Central honours arbitrary semver tags — they don't require the tag to be on a specific branch.

---

## Rolling back a release

Maven Central does NOT allow unpublishing once `closeAndReleaseSonatypeStagingRepository` succeeds. Tags can be deleted, but consumers who already resolved against the version have the artifacts cached in their own Gradle/Maven caches.

If a release is broken:

1. Immediately publish a `1.0.x+1` patch that either fixes the bug or reverts the offending change.
2. Mark the bad version as deprecated in `CHANGELOG.md`.
3. Open a notice in the GitHub Release for the bad version pointing to the fix.

Do NOT delete the tag — that breaks anyone who pinned to the bad version. Always roll forward.

If the artifacts are still in the Sonatype staging repository (i.e. `closeAndReleaseSonatypeStagingRepository` failed before the release step), you CAN drop them from the Nexus UI before retrying.

---

## Versioning policy

We follow [Semantic Versioning](https://semver.org/):

- **Major** — breaking public API changes. Document migration in [MIGRATION.md](MIGRATION.md). Cut from `main` after a deprecation cycle in the prior minor.
- **Minor** — additive, backwards-compatible features. Cut from `main`.
- **Patch** — bug fixes, internal cleanup. Cut from `main` or a hotfix branch.

The SDK version is single-sourced in `gradle/libs.versions.toml` (`synapse-sdk`) and surfaced at runtime via `PyrxConstants.SDK_VERSION` — keep them in sync until automation lands.

Cross-platform release pairing: when the iOS and Android SDKs cut a major version together, the migration steps are intentionally symmetric. Coordinate the release announcements across both repos so customers see one paired changelog entry rather than two separate ones.

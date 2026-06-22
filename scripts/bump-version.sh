#!/usr/bin/env bash
#
# scripts/bump-version.sh — set every PYRX Synapse Android version reference in lockstep.
#
# Usage:
#   scripts/bump-version.sh <new-version>
#
# Example:
#   scripts/bump-version.sh 0.2.0
#
# Updates 7 spots across 6 files:
#   1. build.gradle.kts                                                   (root, version = "x.y.z")
#   2. synapse-core/build.gradle.kts                                      (module, version = "x.y.z")
#   3. synapse-push/build.gradle.kts                                      (module, version = "x.y.z")
#   4. synapse-inapp/build.gradle.kts                                     (module, version = "x.y.z")
#   5. synapse-core/src/main/kotlin/tech/pyrx/synapse/PyrxConstants.kt    (SDK_VERSION)
#   6. synapse-core/src/test/kotlin/tech/pyrx/synapse/PyrxConfigTest.kt   (assertion)
#   7. synapse-push/src/test/kotlin/tech/pyrx/synapse/push/PushRegistrationTest.kt
#                                                                        (2 hardcoded assertions)
#
# Then runs `./gradlew test` to confirm assertions still pass with the new version.
#
# Why this script exists: v0.1.x dry-runs caught lockstep drift twice. Test
# assertions hardcoded the old version while source was bumped, causing the
# verify job in publish.yml to fail and needing a v0.1.1 → v0.1.2 recovery.
# Manual sed across 6 files is error-prone; one command beats six.
#
# Note: requires JAVA_HOME pointing to JDK 17 (AGP 8.2.x requirement).
# Sample: JAVA_HOME=$(/usr/libexec/java_home -v 17) ./scripts/bump-version.sh 0.2.0

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <new-version>"
  echo "Example: $0 0.2.0"
  exit 1
fi

NEW_VERSION="$1"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# Capture current version so we can do a targeted in-place replacement
CURRENT_VERSION=$(grep -E "public const val SDK_VERSION" synapse-core/src/main/kotlin/tech/pyrx/synapse/PyrxConstants.kt | sed -E 's/.*"([^"]+)".*/\1/')

if [[ -z "$CURRENT_VERSION" ]]; then
  echo "ERROR: could not detect current version from synapse-core/src/main/kotlin/tech/pyrx/synapse/PyrxConstants.kt" >&2
  exit 1
fi

if [[ "$CURRENT_VERSION" == "$NEW_VERSION" ]]; then
  echo "Current version already $NEW_VERSION — nothing to do."
  exit 0
fi

echo "Bumping $CURRENT_VERSION → $NEW_VERSION across 6 source files (7 spots)…"

# 1-4: root + 3 module build.gradle.kts
for f in build.gradle.kts synapse-core/build.gradle.kts synapse-push/build.gradle.kts synapse-inapp/build.gradle.kts; do
  sed -i '' "s/version = \"$CURRENT_VERSION\"/version = \"$NEW_VERSION\"/" "$f"
done

# 5: PyrxConstants.kt
sed -i '' "s/SDK_VERSION: String = \"$CURRENT_VERSION\"/SDK_VERSION: String = \"$NEW_VERSION\"/" \
  synapse-core/src/main/kotlin/tech/pyrx/synapse/PyrxConstants.kt

# 6: PyrxConfigTest.kt — asserts SDK_VERSION matches the literal
sed -i '' "s/\"$CURRENT_VERSION\"/\"$NEW_VERSION\"/g" \
  synapse-core/src/test/kotlin/tech/pyrx/synapse/PyrxConfigTest.kt

# 7: PushRegistrationTest.kt — has 2 spots: a JSON body and an assertion
sed -i '' "s/\"sdk_version\":\"$CURRENT_VERSION\"/\"sdk_version\":\"$NEW_VERSION\"/g" \
  synapse-push/src/test/kotlin/tech/pyrx/synapse/push/PushRegistrationTest.kt
sed -i '' "s/assertEquals(\"$CURRENT_VERSION\", body\[\"sdk_version\"\]/assertEquals(\"$NEW_VERSION\", body[\"sdk_version\"]/" \
  synapse-push/src/test/kotlin/tech/pyrx/synapse/push/PushRegistrationTest.kt

# Verify by re-grepping the new value in each file
echo ""
echo "Verification (expect lines matching '$NEW_VERSION' across all 6 files):"
grep -rnE "version = \"$NEW_VERSION\"|SDK_VERSION.*\"$NEW_VERSION\"|\"sdk_version\":\"$NEW_VERSION\"|assertEquals\(\"$NEW_VERSION\"" \
  build.gradle.kts \
  synapse-core/build.gradle.kts \
  synapse-push/build.gradle.kts \
  synapse-inapp/build.gradle.kts \
  synapse-core/src/main/kotlin/tech/pyrx/synapse/PyrxConstants.kt \
  synapse-core/src/test/kotlin/tech/pyrx/synapse/PyrxConfigTest.kt \
  synapse-push/src/test/kotlin/tech/pyrx/synapse/push/PushRegistrationTest.kt \
  | grep -v "^[[:space:]]*//\|^[[:space:]]*\*"

# Sanity-check test (requires JDK 17)
echo ""
echo "Running ./gradlew test to confirm assertions still pass…"
if ! command -v java >/dev/null 2>&1; then
  echo "WARN: java not on PATH — skipping ./gradlew test. Run manually:" >&2
  echo "  JAVA_HOME=\$(/usr/libexec/java_home -v 17) ./gradlew test" >&2
else
  ./gradlew :synapse-core:testReleaseUnitTest :synapse-push:testReleaseUnitTest --no-daemon 2>&1 | tail -5
fi

echo ""
echo "Done. Next: commit + tag:"
echo "  git add -A && git commit -m 'chore(release): v$NEW_VERSION'"
echo "  git tag -a v$NEW_VERSION -m 'v$NEW_VERSION'"
echo "  git push origin main"
echo "  git push origin v$NEW_VERSION"

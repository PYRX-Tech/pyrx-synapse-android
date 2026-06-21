# Migration Guide

Version-to-version migration notes for the PYRX Synapse Android SDK.

---

## 1.0.0 — initial GA release

Nothing to migrate from. This is the first stable release.

If you're integrating the SDK for the first time, start with [QUICKSTART.md](QUICKSTART.md).

If you're coming from a different vendor (Segment, Mixpanel, Braze, MoEngage, OneSignal, Iterable, etc.), see [synapse.pyrx.tech/docs/migrating](https://synapse.pyrx.tech/docs/migrating) for the vendor-by-vendor swap-in guide.

---

## Future releases

Each subsequent major and minor release that breaks or changes the public SDK surface will document the migration here. Patch releases (e.g. `1.0.0 → 1.0.1`) never break compatibility.

We follow [Semantic Versioning](https://semver.org/):
- **Major** (`1.x.x → 2.0.0`) — incompatible API changes. Read the migration carefully.
- **Minor** (`1.0.x → 1.1.0`) — additive, backwards-compatible.
- **Patch** (`1.0.0 → 1.0.1`) — bug fixes only.

Major changes will always ship with:
- A migration section in this file.
- A deprecation cycle in the prior minor release where possible (one minor version of warnings before removal).
- A CHANGELOG entry calling out every removed or changed public symbol.

Cross-platform consistency: when the iOS and Android SDKs cut a major version together, the migration steps are intentionally symmetric — same field renames, same error-hierarchy changes, same wire-shape evolution. The iOS [MIGRATION.md](https://github.com/PYRX-Tech/pyrx-synapse-ios/blob/main/docs/MIGRATION.md) is a useful cross-reference.

# Skill: Release IntelliJ Plugin

Use this skill after a new feature or fix is complete to run the full release pipeline:
test → build → changelog → version bump → commit → push → git tag → publish to JetBrains Marketplace.

---

## Prerequisites (verify before starting)

- `PUBLISH_TOKEN` environment variable is set (JetBrains Marketplace token).
- The working tree is clean (no uncommitted changes other than what was just developed).
- The user is on the correct branch (usually `main` or `master`).
- `gradle.properties` contains the current version under the key `version` (or `build.gradle.kts` has `version = "x.y.z"`).

---

## Step-by-step procedure

### 1 — Run the full test suite

```bash
./gradlew test
```

- All tests must pass before proceeding.
- If any test fails, **stop** and fix the failure first.

### 2 — Determine the new version

Ask the user (or infer from context):

> What is the new version number? (current version is in `build.gradle.kts` → `version = "…"`)

Version format: `MAJOR.MINOR.PATCH` (e.g. `1.2.0`).

### 3 — Update `build.gradle.kts` version

In [`build.gradle.kts`](build.gradle.kts), change:

```kotlin
version = "OLD_VERSION"
```

to:

```kotlin
version = "NEW_VERSION"
```

### 4 — Update `CHANGELOG.md`

In [`CHANGELOG.md`](CHANGELOG.md) (create it if it doesn't exist), prepend a new section:

```markdown
## [NEW_VERSION] — YYYY-MM-DD

### Added / Changed / Fixed
- <bullet points summarising what changed in this release>
```

Also update the `<change-notes>` block in [`src/main/resources/META-INF/plugin.xml`](src/main/resources/META-INF/plugin.xml) with the same summary (IntelliJ Marketplace displays this).

### 5 — Build the plugin distribution

```bash
./gradlew buildPlugin
```

The distributable ZIP is produced at:
```
build/distributions/azure-templates-navigator-NEW_VERSION.zip
```

Verify the file exists before continuing.

### 6 — Commit all changes

```bash
git add build.gradle.kts CHANGELOG.md src/main/resources/META-INF/plugin.xml
git commit -m "chore: release vNEW_VERSION"
```

### 7 — Create and push the git tag

```bash
git tag -a "vNEW_VERSION" -m "Release vNEW_VERSION"
git push origin HEAD
git push origin "vNEW_VERSION"
```

### 8 — Publish to JetBrains Marketplace

```bash
./gradlew publishPlugin
```

This uses the `PUBLISH_TOKEN` environment variable configured in `build.gradle.kts`:

```kotlin
publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
}
```

If the token is not set in the environment, pass it explicitly:

```bash
PUBLISH_TOKEN=your_token_here ./gradlew publishPlugin
```

### 9 — Verify the release

- Open https://plugins.jetbrains.com/plugin/ and confirm the new version appears (may take a few minutes for Marketplace review).
- Check that the git tag is visible on the remote: `git ls-remote --tags origin`.

---

## Checklist summary

```
[ ] ./gradlew test                          — all tests green
[ ] Determine new version number
[ ] Update version in build.gradle.kts
[ ] Update CHANGELOG.md
[ ] Update plugin.xml <change-notes>
[ ] ./gradlew buildPlugin                   — ZIP produced
[ ] git add + git commit
[ ] git tag -a vNEW_VERSION + git push
[ ] ./gradlew publishPlugin                 — published to Marketplace
[ ] Verify on Marketplace website
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `publishPlugin` fails with 401 | `PUBLISH_TOKEN` is missing or expired — regenerate at https://plugins.jetbrains.com/author/me/tokens |
| `buildPlugin` fails | Run `./gradlew compileKotlin` first to see compilation errors |
| Tag already exists | Delete with `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z` then re-tag |
| Tests fail | Fix failures before releasing — never skip this step |

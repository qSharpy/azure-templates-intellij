# Skill: Release IntelliJ Plugin

Use this skill after a new feature or fix is complete to release the plugin.
The entire pipeline runs as **a single shell command** (one approval) via [`release.sh`](release.sh).

---

## Prerequisites (verify before starting)

- `PUBLISH_TOKEN` environment variable is set (JetBrains Marketplace token).
  It is already exported in the shell environment for this project.
- The working tree contains only the changes for this release (feature code, tests, updated `plugin.xml` change-notes).
- You are on the `main` branch.

---

## Step 1 — Update `plugin.xml` change-notes manually

Before running the script, update the `<change-notes>` block in
[`src/main/resources/META-INF/plugin.xml`](src/main/resources/META-INF/plugin.xml)
to describe what changed in this release. The script commits this file automatically.

---

## Step 2 — Run the release script (single command, one approval)

```bash
./release.sh <NEW_VERSION>
```

Example:
```bash
./release.sh 1.3.0
```

The script does everything in order:
1. `./gradlew test` — all tests must pass
2. Bumps `version` in `build.gradle.kts`
3. Prepends a new section to `CHANGELOG.md`
4. `./gradlew buildPlugin -x buildSearchableOptions` — produces the ZIP
5. `git add -u && git commit -m "chore: release vX.Y.Z"`
6. `git tag -a vX.Y.Z`
7. `git push origin HEAD && git push origin vX.Y.Z`
8. `./gradlew publishPlugin -x buildSearchableOptions` — publishes to Marketplace

> **Note on `-x buildSearchableOptions`:** this task requires a GUI display and
> always fails in a headless terminal. Skipping it is safe — this plugin has no
> custom Settings UI that needs searchable options indexed.

---

## After the script completes

- Edit `CHANGELOG.md` to replace the placeholder bullet with real release notes,
  then `git add CHANGELOG.md && git commit --amend --no-edit` (optional polish).
- Verify the new version appears at:
  https://plugins.jetbrains.com/plugin/com.bogdanbujor.azure-templates-navigator
  (Marketplace review takes a few minutes to a few hours.)

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `PUBLISH_TOKEN` not set | `export PUBLISH_TOKEN=perm-...` then re-run |
| Tests fail | Fix failures before releasing — the script aborts on any error |
| Tag already exists | `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z` then re-run |
| `publishPlugin` 401 | Token expired — regenerate at https://plugins.jetbrains.com/author/me/tokens |

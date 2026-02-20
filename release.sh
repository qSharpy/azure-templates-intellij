#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# release.sh — Full release pipeline for Azure Templates Navigator
#
# Usage:
#   ./release.sh <version>
#
# Example:
#   ./release.sh 1.3.0
#
# Prerequisites:
#   - PUBLISH_TOKEN env var must be set (JetBrains Marketplace token)
#   - Working tree must be clean (or only contain the new feature changes)
#   - Must be run from the repository root
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── 0. Validate arguments ────────────────────────────────────────────────────
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <version>  (e.g. $0 1.3.0)"
  exit 1
fi

NEW_VERSION="$1"
DATE=$(date +%Y-%m-%d)

if [[ -z "${PUBLISH_TOKEN:-}" ]]; then
  echo "ERROR: PUBLISH_TOKEN environment variable is not set."
  echo "       Export it before running: export PUBLISH_TOKEN=perm-..."
  exit 1
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Releasing Azure Templates Navigator v${NEW_VERSION}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ── 1. Run tests ─────────────────────────────────────────────────────────────
echo ""
echo "▶ Step 1/8 — Running tests…"
./gradlew test
echo "  ✓ All tests passed"

# ── 2. Bump version in build.gradle.kts ──────────────────────────────────────
echo ""
echo "▶ Step 2/8 — Bumping version to ${NEW_VERSION}…"
sed -i '' "s/^version = \".*\"/version = \"${NEW_VERSION}\"/" build.gradle.kts
echo "  ✓ build.gradle.kts updated"

# ── 3. Prepend CHANGELOG entry ───────────────────────────────────────────────
echo ""
echo "▶ Step 3/8 — Updating CHANGELOG.md…"
CHANGELOG_ENTRY="## [${NEW_VERSION}] — ${DATE}

### Changed
- See commit history for details of this release.

"
# Prepend after the first line (the "# Changelog" heading)
if [[ -f CHANGELOG.md ]]; then
  # Insert after line 1 (the heading) and a blank line
  awk -v entry="${CHANGELOG_ENTRY}" 'NR==3{print entry}1' CHANGELOG.md > CHANGELOG.tmp && mv CHANGELOG.tmp CHANGELOG.md
else
  printf "# Changelog\n\n${CHANGELOG_ENTRY}" > CHANGELOG.md
fi
echo "  ✓ CHANGELOG.md updated (edit manually to add bullet points)"

# ── 4. Build plugin ZIP ───────────────────────────────────────────────────────
echo ""
echo "▶ Step 4/8 — Building plugin distribution…"
./gradlew buildPlugin -x buildSearchableOptions
ZIP_PATH=$(ls build/distributions/*-${NEW_VERSION}.zip 2>/dev/null | head -1)
if [[ -z "$ZIP_PATH" ]]; then
  echo "ERROR: Expected ZIP not found in build/distributions/"
  exit 1
fi
echo "  ✓ Built: ${ZIP_PATH}"

# ── 5. Commit ─────────────────────────────────────────────────────────────────
echo ""
echo "▶ Step 5/8 — Committing release files…"
git add build.gradle.kts CHANGELOG.md src/main/resources/META-INF/plugin.xml
# Stage any other tracked modified files
git add -u
git commit -m "chore: release v${NEW_VERSION}"
echo "  ✓ Committed"

# ── 6. Tag ────────────────────────────────────────────────────────────────────
echo ""
echo "▶ Step 6/8 — Tagging v${NEW_VERSION}…"
git tag -a "v${NEW_VERSION}" -m "Release v${NEW_VERSION}"
echo "  ✓ Tagged v${NEW_VERSION}"

# ── 7. Push ───────────────────────────────────────────────────────────────────
echo ""
echo "▶ Step 7/8 — Pushing to origin…"
git push origin HEAD
git push origin "v${NEW_VERSION}"
echo "  ✓ Pushed branch and tag"

# ── 8. Publish to JetBrains Marketplace ──────────────────────────────────────
echo ""
echo "▶ Step 8/8 — Publishing to JetBrains Marketplace…"
PUBLISH_TOKEN="${PUBLISH_TOKEN}" ./gradlew publishPlugin -x buildSearchableOptions
echo "  ✓ Published"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  ✅  v${NEW_VERSION} released successfully!"
echo "  🔗  https://plugins.jetbrains.com/plugin/com.bogdanbujor.azure-templates-navigator"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

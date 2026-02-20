# Azure Templates Navigator — IntelliJ Plugin

A must-have plugin for anyone who develops, debugs, or reviews Azure Pipelines YAML files in IntelliJ-based IDEs (IntelliJ IDEA, PyCharm, WebStorm, Rider, etc.).

Hover over any `- template:` reference to instantly see its parameters. Get real-time diagnostics for missing or unknown parameters. Autocomplete parameter names as you type. Explore the full template dependency tree **and** an interactive workspace-wide dependency graph — all with zero runtime dependencies.

---

## Features

### 🔍 Template Parameter Hover
Hover over any `- template:` line to see a tooltip with all parameters declared in the referenced template — their types, default values, and which are required.

### 🔴 Parameter Validation Diagnostics
Real-time squiggly-line diagnostics on every template call site:
- **Error** — missing a required parameter
- **Warning** — passing an unknown parameter not declared in the template
- **Warning** — type mismatch (e.g. passing `'yes'` to a `boolean` parameter)

### 💡 IntelliSense Autocomplete
When typing inside the `parameters:` block under a `- template:` line, the plugin offers autocomplete suggestions for every parameter declared in the referenced template:
- Required parameters appear first
- Each suggestion shows the parameter type and default value
- Already-set parameters are shown at the bottom

### 🔎 Unused Parameter Detection
Template-side inspection detects parameters declared in `parameters:` that are never referenced via `${{ parameters.name }}` in the template body. Reported as a Warning with a one-click **"Remove unused parameter declaration"** quick-fix.

### ⚡ Quick-Fix Actions
One-click fixes directly in the editor:
- **Add missing parameter** — inserts the required parameter at the call site
- **Remove unknown parameter** — deletes the unrecognised parameter entry
- **Fix type mismatch** — corrects the value to match the declared type
- **Remove unused parameter** — deletes the full parameter declaration block from the template

### 🌲 Dependency Tree Panel
A dedicated tool window (**Azure Templates – Dependencies**) showing the full dependency tree for the currently active YAML file:
- **Called by** section — upstream callers shown as a trie-merged tree from root pipeline down to the direct caller
- **Is calling** section — downstream templates referenced by the active file, recursively expanded
- **Severity indicators** — tree nodes are coloured red/amber with ✖/⚠ suffixes based on their worst diagnostic severity
- **File header badge** — shows an error/warning icon and coloured filename for the currently active file
- **Fuzzy search bar** — type to find any indexed template; results appear in a popup navigable with Up/Down/Enter/Escape; typo-tolerant (e.g. `"templete"` finds `"template"`)
- **Toolbar actions**: Show Full Path · Expand/Collapse All · Errors Only (hide warnings) · Open in Diagnostics · Copy Path
- **Right-click context menu**: Open in Diagnostics (when the node has issues)
- Double-click any node to open the template file

### 🗺️ Interactive Graph Panel
A dedicated tool window (**Azure Templates – Graph**) showing a D3.js force-directed graph of template relationships:

| Node colour | Meaning |
|---|---|
| 🟢 Green | Pipeline root file (`trigger:` / `stages:` at top level) |
| 🔵 Blue | Local template |
| 🟠 Orange | External / cross-repo template |
| 🔴 Red | Missing file (not found on disk) |
| ⚫ Grey | Unknown `@alias` (not in `resources.repositories`) |

**Toolbar controls:**
- **Fit** — fits the graph into the visible area
- **Full Path** — toggles between filename and workspace-relative path labels
- **−/+ depth** — in file-scope mode, controls how many upstream/downstream levels are shown (1–10)
- **File Scope / Workspace** — toggle between a scoped view of the active file and the full workspace graph

**Interactions:**
- **Click** a node → opens the file in the editor
- **Drag** a node → repositions it; releasing snaps it back to the simulation
- **Scroll** → zoom in/out toward the mouse position
- **+/− buttons** → zoom in/out
- **Legend** → collapsible colour key in the bottom-right corner

### 🩺 Diagnostics Panel
A dedicated tool window (**Azure Templates – Diagnostics**) showing all template issues across the project in one place:
- Errors and warnings grouped by file
- Click any issue to jump to the exact line in the editor
- Programmatic `selectFile()` API used by the Dependency Tree's "Open in Diagnostics" action

### 🔗 Cross-Repository Template Support
Resolves `@alias` references using `resources.repositories` declarations. The plugin maps each alias to its repository name and resolves the template path as `{workspace}/../{repo-name}/{template-path}` on the local filesystem.

### ⌨️ Go-to-Declaration
Press **Ctrl+Click** (or **Cmd+Click** on macOS) on any `- template:` line to jump directly to the template file.

### ⚙️ Settings
Configure sibling repository paths and other options under **Settings → Tools → Azure Templates Navigator**.

---

## Cross-Repository Templates

Given a pipeline like this:

```yaml
resources:
  repositories:
    - repository: templates
      name: myorg/shared-templates

stages:
  - template: stages/build.yml@templates
```

The plugin reads the `resources.repositories` block, maps `templates` → `shared-templates`, and resolves the template path as `{workspace}/../shared-templates/stages/build.yml` on disk.

Clone the external repository **next to** your current workspace:

```
parent-directory/
├── your-pipeline-repo/     ← your workspace (open in IntelliJ)
│   └── pipelines/azure-pipelines.yml
└── shared-templates/       ← clone the template repo here
    └── stages/build.yml
```

---

## Local Development

### Prerequisites

- JDK 17+
- IntelliJ IDEA (Community or Ultimate)

### Run in a sandboxed IDE

```bash
./gradlew runIde
```

### Run tests

```bash
./gradlew test
```

### Build the plugin ZIP

```bash
./gradlew buildPlugin -x buildSearchableOptions
# Produces: build/distributions/azure-templates-intellij-X.Y.Z.zip
```

### Publish to JetBrains Marketplace

```bash
export PUBLISH_TOKEN=perm-...
./gradlew publishPlugin -x buildSearchableOptions
```

Or use the [`release.sh`](release.sh) script which runs tests, bumps the version, updates the changelog, builds, commits, tags, pushes, and publishes in one step:

```bash
./release.sh 1.4.0
```

---

## Known Limitations

- Only parses `parameters:` blocks at the top level of the template file
- Template references using variables (e.g. `- template: ${{ variables.templatePath }}`) are not resolved (skipped gracefully)
- Cross-repo resolution assumes the sibling repo is cloned locally; remote-only repos are not fetched automatically
- `buildSearchableOptions` requires a GUI display and is skipped in headless terminals (safe to skip — this plugin has no custom Settings UI that needs indexing)

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

---

## Support

[Buy me a merdenea ☕](https://ko-fi.com/bogdanbujor)

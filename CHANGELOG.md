# Changelog

All notable changes to **Azure Templates Navigator** are documented here.

## [1.8.1] — 2026-02-21

### Fixed
- **Object parameter passed as YAML sequence no longer triggers false "unknown parameter" warnings** — when an `object` parameter is passed as a list of mappings (e.g. `checkoutProperties:` followed by `- reference: …` / `- reference: … alias: …` items), keys inside those list items (such as `alias`) are no longer mistakenly collected as top-level parameters and reported as unknown. `PassedParameterParser` now treats YAML sequence items (`- …`) at the same indent as the object parameter entry as still being part of that object's value, not as sibling parameters.

## [1.8.0] — 2026-02-21

### Fixed
- **Object-valued parameters no longer flagged as unknown** — when a parameter of type `object` is passed as a multi-line YAML mapping (e.g. `deployConfig:` with nested `replicas`, `strategy` keys), the nested property lines are no longer mistakenly collected as top-level parameters and reported as "unknown parameter". `PassedParameterParser` now tracks `objectValueDepth` and skips all lines more deeply indented than the parameter entry until the indent returns to the parameter-entry level.
- **`${{ each parameter in parameters }}:` pass-through no longer causes false "missing required parameter" errors** — the call-site validator now detects the each-passthrough idiom via `PassedParameterParser.hasEachPassthrough()` and skips all missing/unknown/type-mismatch checks for that call site, since every declared parameter is implicitly forwarded.

## [1.7.0] — 2026-02-21

### Changed
- **VS Code-inspired hover popup design** — redesigned parameter popup with higher contrast, theme-aware `JBColor` pairs for type (blue), default value (rust/orange), and dash separator (muted grey); adapts correctly to both light and dark IDE themes.
- **Open / Open to side links** — the hover popup header now shows clickable "⎆ Open" and "⊞ Open to side" action links; "Open to side" splits the editor window vertically and opens the template file in the new right pane (previously both actions were identical).
- **Header separator** — a thin `JSeparator` visually divides the template path + action links from the parameters list.
- **HiDPI-aware borders** — all padding now uses `JBUI.Borders` for correct scaling on high-DPI displays.

## [1.6.0] — 2026-02-21

### Added
- **Errors Only filter in Dependency Tree** — new toolbar button hides all non-error nodes from the tree entirely; only files with ERROR-level diagnostics are shown, making it instant to spot every broken template at a glance.
- **Deep error surfacing** — when Errors Only is active, errors nested deep in the dependency chain are promoted to the nearest visible ancestor so no error is ever hidden.

### Changed
- **Hide Warnings renamed** — the previous "Errors Only" toggle (which only suppressed amber colouring) is now correctly labelled "Hide Warnings" to distinguish it from the new structural filter.
- **Empty group nodes suppressed** — "Called by" and "Is calling" section headers are omitted when all their children are filtered out, keeping the tree clean.

## [1.5.0] — 2026-02-21

### Added
- **Searchable parameter popup** — hovering over a template reference now opens a dedicated Swing popup with a live search field; type to instantly filter parameters by name (case-insensitive substring match).
- **Keyboard navigation in popup** — ↓/↑ arrow keys from the search field move through the parameter list; Enter or double-click jumps to the parameter declaration in the template file.
- **Go-to-parameter-declaration** — selecting a parameter in the hover popup and pressing Enter or double-clicking opens the template file at the exact line where that parameter is declared.

### Changed
- Hover tooltip no longer shows the absolute file path — only the relative template path is displayed.
- HTML documentation popup for template references is suppressed in favour of the new Swing popup (variable hover and unknown-alias error tooltips are unaffected).

## [1.4.0] — 2026-02-20

### Added
- **Fuzzy search in Dependency Tree** — new search bar at the top of the Dependencies panel lets you find any indexed template by typing; results appear in a popup with filename (bold) and directory path (grey), navigable with Up/Down/Enter/Escape.
- **Typo-tolerant matching** — the `FuzzySearch` engine combines subsequence scoring, word-boundary bonuses, camel-case bonuses, and Levenshtein-based segment fuzzy matching so queries like `"templete"` still find `"template"`.
- **Debounced search input** — search fires 150 ms after the last keystroke to avoid redundant work on fast typing.
- **Configurable graph depth** — the Graph tool window now shows −/+ depth controls in the toolbar; in file-scope mode you can expand or narrow how many upstream/downstream levels are rendered (1–10).
- **Multi-level BFS in file graph** — `GraphBuilder.buildFileGraph()` now performs a proper breadth-first traversal for both downstream templates and upstream callers up to the configured depth, instead of always stopping at depth 1.

## [1.3.0] — 2026-02-20

### Added
- **Severity indicators in Dependency Tree** — tree nodes are coloured red/amber and suffixed with ✖/⚠ based on their worst diagnostic severity, so issues are visible without opening the Diagnostics panel.
- **File header severity badge** — the header label in the Dependency Tree panel shows an error/warning icon and coloured filename for the currently active file.
- **Hide Warnings toggle** — new toolbar action suppresses amber warning highlights in the tree so only errors are shown in colour.
- **Open in Diagnostics action** — toolbar button jumps from the Dependency Tree panel directly to the matching file node in the Diagnostics tool window.
- **Copy Path action** — copies the workspace-relative path of the active file to the clipboard with a brief checkmark icon feedback.

### Changed
- `TemplateIndexService` now maintains a per-file worst-severity cache (`getFileSeverity()`) rebuilt on every index update, eliminating redundant re-validation in the UI layer.
- `DiagnosticsToolWindow` exposes a new `selectFile()` API that scrolls and expands the matching file node in the Diagnostics tree.

## [1.2.0] — 2026-02-20

### Added
- **Unused Parameter diagnostic** — new template-side inspection detects parameters declared in `parameters:` that are never referenced via `${{ parameters.name }}` in the template body. Reported as a Warning with a one-click quick-fix: *"Remove unused parameter declaration"* that deletes the entire entry (name, type, default, values) cleanly.
- **Diagnostics panel integration** — the "Azure Templates – Diagnostics" tool window now surfaces unused-parameter warnings project-wide alongside the existing caller-side errors, so you can find dead parameters across all templates without opening each file individually.
- **`RemoveUnusedParameterFix`** quick-fix removes the full `- name: …` block including all sub-properties, consuming the trailing newline to avoid blank lines.

### Changed
- `DiagnosticsToolWindow` refresh now scans **every** indexed YAML file for unused parameters, not just files that reference other templates.
- Inspection highlight now covers only the parameter **name token** (e.g. `publishArtifact`) rather than the leading whitespace on the declaration line.

## [1.1.0] — 2025-01-01

### Added
- **Quick-fix actions** — one-click fixes for missing required parameters, unknown parameters, and type mismatches directly in the editor.
- **Performance improvements** — background indexing prevents IDE freezes on large workspaces; VFS-based file reads replace blocking I/O.
- **Full path toggle** — switch between short and full file paths in the Dependency Tree and Graph tool windows.
- **Graph enhancements** — zoom controls, improved force-directed layout, and a legend for node types.
- **Improved documentation styling** — richer hover tooltips with better HTML formatting.
- **Better template resolution** — more robust go-to-declaration for cross-repo and relative template paths.
- **Robustness fixes** — regex constants extracted to companion objects, mutable data class fields eliminated, flexible indent detection in parameter parser.

## [1.0.0] — 2024-01-01

### Added
- Initial release.
- Template parameter hover documentation.
- Go-to-declaration for template references.
- Parameter autocompletion with type information.
- Real-time template parameter validation inspections.
- Gutter icons for template references.
- Dependency tree tool window.
- Interactive D3.js template graph visualization.
- Diagnostics tool window for project-wide template issues.
- Cross-repository template resolution.
- Configurable sibling repository paths.

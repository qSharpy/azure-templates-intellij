# Changelog

All notable changes to **Azure Templates Navigator** are documented here.

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

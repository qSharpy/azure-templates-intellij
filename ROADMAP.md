# Azure Templates Navigator — Improvement & Feature Roadmap

After a thorough review of every source file, here is a categorized analysis of improvements, new features, and technical debt items.

---

## 🔴 Critical Improvements (Bugs / Robustness)

### ✅ 1. File I/O is blocking the EDT *(DONE)*
Every provider (`TemplateDocumentationProvider`, `TemplateCompletionContributor`, `TemplateInspection`, `TemplateLineMarkerProvider`) calls `File(filePath).readText()` synchronously. This blocks the UI thread on large files or slow disks. Use IntelliJ's `VirtualFile.contentsToByteArray()` or read through the VFS with `ReadAction.compute()`.

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateDocumentationProvider.kt` (line 84)
- `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateCompletionContributor.kt` (line 55)
- `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateInspection.kt` (line 46)
- `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateLineMarkerProvider.kt` (line 45)

### ✅ 2. `TemplateIndexService` does a full re-scan on first access *(DONE)*
`DependencyTreeToolWindow.refresh()` calls `indexService.fullIndex()` if the index is empty. This is a synchronous full-workspace scan that can freeze the IDE on large monorepos. Move to a background `Task.Backgroundable` or use `StartupActivity` to index lazily.

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/ui/DependencyTreeToolWindow.kt` (line 121)
- `src/main/kotlin/com/bogdanbujor/azuretemplates/services/TemplateIndexService.kt` (line 56)

### ✅ 3. `DiagnosticsToolWindow.refresh()` re-scans every YAML file from disk *(DONE)*
`DiagnosticsPanel.refresh()` calls `GraphBuilder.collectYamlFiles()` + reads every file on every refresh. This should leverage the `TemplateIndexService` cache instead.

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/ui/DiagnosticsToolWindow.kt` (line 78)

### ✅ 4. `GraphNode.paramCount` / `requiredCount` are mutable `var` in a data class *(DONE)*
In `Models.kt` lines 63-64, `paramCount` and `requiredCount` are `var` while all other fields are `val`. This breaks the data class contract (hashCode/equals instability). Use `copy()` in `GraphBuilder` instead.

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/Models.kt` (lines 63-64)
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/GraphBuilder.kt` (line 206)

### ✅ 5. Regex objects are re-created on every call *(DONE)*
In `TemplateInspection.kt` lines 39-40 and `DiagnosticsToolWindow.kt` lines 96-97, `Regex(...)` is instantiated inside loops. Extract to companion object constants (like `TemplateResolver` already does correctly).

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateInspection.kt` (lines 39-40)
- `src/main/kotlin/com/bogdanbujor/azuretemplates/ui/DiagnosticsToolWindow.kt` (lines 96-97)

### ✅ 6. `PassedParameterParser` hardcodes indent offsets *(DONE)*
`PassedParameterParser.kt` line 60 checks `lineIndent == paramsIndent + 2 || lineIndent == paramsIndent + 4`. This breaks for files using tab indentation or non-standard indent widths. Should use "any indent deeper than `paramsIndent`" with a "first-seen child indent" approach.

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/PassedParameterParser.kt` (line 60)

---

## 🟡 Architecture Improvements

### 7. Use IntelliJ's YAML PSI instead of regex parsing
All core parsers (`ParameterParser`, `VariableParser`, `RepositoryAliasParser`, `PassedParameterParser`) use line-by-line regex. The CLAUDE.md even notes this: *"You MAY use IntelliJ's YAML PSI parser (YAMLFile, YAMLMapping, YAMLSequence) instead of regex."* PSI-based parsing would be:
- More robust against edge cases (multi-line strings, anchors, aliases)
- Automatically incremental (PSI trees are cached and updated incrementally)
- Required for proper `PsiReference` support (see #12 below)

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/ParameterParser.kt`
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/VariableParser.kt`
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/RepositoryAliasParser.kt`
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/PassedParameterParser.kt`

### 8. Implement proper `PsiReference` for template paths
Currently, go-to-declaration is handled by a raw `GotoDeclarationHandler` that manually resolves offsets. A proper `PsiReferenceContributor` would enable:
- Find Usages (Ctrl+Alt+F7) on template files
- Rename refactoring of template paths
- Reference highlighting
- Proper integration with IntelliJ's navigation stack

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateGotoDeclarationHandler.kt`
- New file: `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateReferenceContributor.kt`

### 9. Use `FileBasedIndex` or `StubIndex` for template indexing
`TemplateIndexService` maintains a manual in-memory index. IntelliJ's `FileBasedIndex` API would provide:
- Automatic invalidation on file changes (no manual `BulkFileListener`)
- Persistence across IDE restarts
- Incremental re-indexing
- Thread-safe access

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/services/TemplateIndexService.kt`

### 10. Settings should be project-scoped, not application-scoped
`PluginSettings` is registered as `applicationService` in `plugin.xml` line 97. Settings like `graphRootPath` are inherently project-specific. Split into application-level (color preferences) and project-level (graph root path, sibling repo paths).

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/settings/PluginSettings.kt`
- `src/main/kotlin/com/bogdanbujor/azuretemplates/settings/PluginSettingsConfigurable.kt`
- `src/main/resources/META-INF/plugin.xml` (line 97)

---

## 🟢 New Features

### 11. Quick-fix actions for diagnostics
`TemplateInspection` registers problems but provides no quick-fixes. Add `LocalQuickFix` implementations:
- **"Add missing parameter"** — inserts `paramName: ` with cursor placement
- **"Remove unknown parameter"** — deletes the line
- **"Fix type mismatch"** — suggests the correct literal format

### 12. Find Usages for template files
Allow right-clicking a template YAML file and choosing **Find Usages** to see all pipelines that reference it. This requires the `PsiReference` infrastructure from #8.

### 13. Inlay hints for template parameters
Use `InlayHintsProvider` to show inline annotations next to template references:
```yaml
- template: templates/deploy.yml  # 3 params, 2 required
  parameters:
    environment: Production
```

### 14. Live templates / file templates
Provide IntelliJ Live Templates for common Azure Pipelines patterns:
- `azparam` → expands to a parameter block skeleton
- `aztemplate` → expands to a template reference with parameters block
- `azresource` → expands to a resources/repositories block

### 15. Structure view contributor
Implement `StructureViewExtension` to show Azure Pipeline structure in the Structure tool window (Cmd+7):
```
📄 azure-pipelines.yml
  ├── 🔧 trigger: main
  ├── 📦 resources
  │   └── 🔗 templates → myorg/shared-templates
  ├── 📋 variables (3)
  └── 🏗️ stages
      ├── Build → templates/build.yml
      └── Deploy → templates/deploy.yml@templates
```

### 16. Breadcrumb provider
Implement `BreadcrumbsProvider` so the editor breadcrumb bar shows the Azure Pipeline hierarchy: `stages > Build > steps > template: build.yml`.

### 17. Color preview in settings
The `PluginSettingsConfigurable` uses a plain `JBTextField` for the hex color. Replace with `ColorPanel` or `ColorChooserService` for a proper color picker with preview.

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/settings/PluginSettingsConfigurable.kt` (line 27)

### 18. Template parameter "values" enum support
Azure Pipelines supports `values:` constraints on parameters:
```yaml
parameters:
  - name: environment
    type: string
    values: [dev, staging, production]
```
Extend `ParameterParser` to parse `values:`, then:
- Show allowed values in hover documentation
- Validate passed values against the enum
- Offer enum values in autocompletion

**Files affected:**
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/ParameterParser.kt`
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/Models.kt` (add `values` field to `TemplateParameter`)
- `src/main/kotlin/com/bogdanbujor/azuretemplates/core/CallSiteValidator.kt`
- `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateCompletionContributor.kt`
- `src/main/kotlin/com/bogdanbujor/azuretemplates/providers/TemplateDocumentationProvider.kt`

### 19. Template expression support (`${{ }}`)
Currently, template expressions like `${{ parameters.foo }}` are completely skipped. Add:
- Hover documentation for `${{ parameters.x }}` showing the parameter definition
- Autocompletion inside `${{ parameters. }}` and `${{ variables. }}`
- Go-to-definition for parameter/variable references in expressions

### 20. Conditional/loop template awareness
Azure Pipelines supports `${{ if }}`, `${{ each }}`, and `${{ else }}` expressions. The plugin currently ignores these. Add:
- Syntax-aware folding for conditional blocks
- Visual indicators in the dependency tree for conditional template inclusions
- Diagnostics that understand conditional parameter passing

### 21. Multi-root workspace support
The plugin assumes a single `project.basePath`. For IntelliJ projects with multiple content roots (common in monorepos), iterate over `ProjectRootManager.getInstance(project).contentRoots` instead.

### 22. Export graph as image/SVG
Add an "Export" button to the `GraphToolWindow` that captures the D3 SVG and saves it as PNG/SVG for documentation purposes.

### 23. Graph node grouping by directory
In large workspaces, the graph becomes cluttered. Add a "Group by directory" mode that collapses templates in the same folder into a single compound node.

### 24. Notification for unresolved cross-repo templates
When a `@alias` reference can't be resolved because the sibling repo isn't cloned, show an IntelliJ notification with an action button: *"Clone `shared-templates` repository next to this workspace"*.

---

## 🔵 Testing & Quality

### 25. Expand test coverage
Current tests only cover `CallSiteValidatorTest` (type inference only), `ParameterParserTest`, `TemplateResolverTest`, and `GraphBuilderTest`. Missing tests:
- `VariableParser` — map form, list form, groups, edge cases
- `RepositoryAliasParser` — multiple repos, missing name, nested blocks
- `PassedParameterParser` — various indent levels, empty blocks
- `CallSiteValidator.validate()` — full integration (not just `inferValueType`)
- Provider integration tests using `BasePlatformTestCase`

### 26. Add CI/CD pipeline
No GitHub Actions or CI configuration exists. Add:
- Build verification on PRs
- Automated test execution
- Plugin compatibility verification (`./gradlew verifyPlugin`)
- Automated marketplace publishing on tag

### 27. Plugin verifier
Add `runPluginVerifier` task to catch binary compatibility issues with target IDE versions before release.

---

## 📊 Priority Matrix

| Priority | Item | Effort | Impact |
|----------|------|--------|--------|
| **P0** | #5 Regex constants | Minutes | Medium |
| **P0** | #4 Mutable var fix | Minutes | Medium |
| **P1** | #2 Background indexing | Hours | High |
| **P1** | #3 Use index in diagnostics panel | Hours | High |
| **P1** | #6 Indent fix | Hours | Medium |
| **P1** | #1 VFS-based file reads | Hours | High |
| **P2** | #11 Quick-fixes | Days | High |
| **P2** | #18 Enum values support | Days | High |
| **P2** | #25 Test coverage | Days | Medium |
| **P2** | #26 CI/CD | Hours | Medium |
| **P3** | #17 Color picker | Hours | Low |
| **P3** | #14 Live templates | Hours | Medium |
| **P3** | #13 Inlay hints | Days | Medium |
| **P3** | #15 Structure view | Days | Medium |
| **P4** | #7 PSI migration | Weeks | High |
| **P4** | #8 PsiReference | Weeks | High |
| **P4** | #9 FileBasedIndex | Weeks | Medium |
| **P4** | #19 Expression support | Weeks | High |
| **P4** | #20 Conditional awareness | Weeks | Medium |
| **P5** | #10 Project-scoped settings | Days | Low |
| **P5** | #16 Breadcrumbs | Days | Low |
| **P5** | #21 Multi-root support | Days | Medium |
| **P5** | #22 Export graph | Days | Low |
| **P5** | #23 Graph grouping | Days | Low |
| **P5** | #24 Clone notification | Hours | Low |
| **P5** | #12 Find Usages | Weeks | Medium |
| **P5** | #27 Plugin verifier | Hours | Medium |

---

## Recommended Implementation Order

1. **#5, #4** — Trivial fixes (minutes each)
2. **#2, #3** — Background indexing (prevents IDE freezes)
3. **#6** — Indent fix (prevents silent failures)
4. **#1** — VFS-based file reads (prevents EDT blocking)
5. **#11** — Quick-fixes (highest user-visible value for moderate effort)
6. **#18** — Enum values support (extends existing parser)
7. **#25, #26** — Tests + CI (safety net before larger refactors)
8. **#17, #14** — Color picker + live templates (polish)
9. **#13, #15** — Inlay hints + structure view (IDE-native feel)
10. **#7, #8, #9** — PSI migration (large refactor, unlocks #12 and more)
11. **#19, #20** — Expression/conditional support (advanced features)

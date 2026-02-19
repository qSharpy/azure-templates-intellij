package com.bogdanbujor.azuretemplates.core

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Validates a single template call site and returns a list of diagnostic issues.
 *
 * Port of `validateCallSite()` and `inferValueType()` from the VS Code extension's
 * diagnosticProvider.js (lines 23-170).
 *
 * Three checks:
 * 1. **Missing required parameters** — parameter has no default and is not passed → ERROR
 * 2. **Unknown parameters** — passed parameter not declared in template → WARNING
 * 3. **Type mismatches** — inferred value type doesn't match declared parameter type → WARNING
 */
object CallSiteValidator {

    /**
     * Maps Azure Pipelines parameter types to the set of inferred value types
     * that are considered compatible.
     */
    private val COMPATIBLE_TYPES: Map<String, Set<String>> = mapOf(
        "string" to setOf("string"),
        "number" to setOf("number", "string"),       // numbers can be quoted
        "boolean" to setOf("boolean"),
        "object" to setOf("object", "string"),        // objects can be multi-line
        "step" to setOf("object", "string"),
        "steplist" to setOf("object", "string"),
        "job" to setOf("object", "string"),
        "joblist" to setOf("object", "string"),
        "deployment" to setOf("object", "string"),
        "deploymentlist" to setOf("object", "string"),
        "stage" to setOf("object", "string"),
        "stagelist" to setOf("object", "string"),
    )

    /**
     * Infers the "kind" of a YAML scalar value for basic type-checking.
     *
     * Returns one of: "boolean", "number", "object", "string"
     */
    fun inferValueType(value: String): String {
        if (value.isEmpty()) return "string"
        if (value.startsWith("[") || value.startsWith("{")) return "object"
        if (Regex("^(true|false|yes|no|on|off)$", RegexOption.IGNORE_CASE).matches(value)) return "boolean"
        if (Regex("^-?\\d+(\\.\\d+)?$").matches(value)) return "number"
        // Quoted strings are always strings
        if (value.startsWith("'") || value.startsWith("\"")) return "string"
        // Pipeline expressions like $(var) or ${{ ... }} — skip type checking
        if (value.startsWith("$")) return "string"
        return "string"
    }

    /**
     * Validates a single template call site.
     *
     * @param lines All lines of the document
     * @param templateLine 0-based line index of the "- template:" line
     * @param templateRef The raw template reference string
     * @param currentFile Absolute path of the document being validated
     * @param repoAliases alias → repo folder name
     * @return List of [DiagnosticIssue] found at this call site
     */
    fun validate(
        lines: List<String>,
        templateLine: Int,
        templateRef: String,
        currentFile: String,
        repoAliases: Map<String, String>
    ): List<DiagnosticIssue> {
        val diagnostics = mutableListOf<DiagnosticIssue>()

        // Resolve the template file
        val resolved = TemplateResolver.resolve(templateRef, currentFile, repoAliases)
        if (resolved == null || resolved.unknownAlias || resolved.filePath == null) {
            return diagnostics
        }

        val filePath = resolved.filePath

        // Read the template via VFS inside a ReadAction to avoid blocking the EDT.
        // Refresh the VFS entry first so we always read the latest saved content.
        val templateText = ReadAction.compute<String?, Throwable> {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
                ?: return@compute null
            try { String(vf.contentsToByteArray(), vf.charset) } catch (e: Exception) { null }
        } ?: return diagnostics // file not found — hoverProvider already handles this

        // Parse declared parameters from the template
        val declared = ParameterParser.parse(templateText)
        if (declared.isEmpty()) return diagnostics

        val declaredMap = declared.associateBy { it.name }

        // Parse parameters actually passed at this call site
        val passed = PassedParameterParser.parse(lines, templateLine)

        // ── Check 1: Missing required parameters ──────────────────────────────
        for (p in declared) {
            if (p.required && p.name !in passed) {
                val templateLineText = lines[templateLine]
                val templateKeyStart = templateLineText.indexOf("template:")
                val startCol = if (templateKeyStart >= 0) templateKeyStart else 0
                diagnostics.add(
                    DiagnosticIssue(
                        message = "Missing required parameter '${p.name}' (type: ${p.type}) for template '${templateRef.trim()}'",
                        severity = IssueSeverity.ERROR,
                        code = "missing-required-param",
                        line = templateLine,
                        startColumn = startCol,
                        endColumn = templateLineText.length
                    )
                )
            }
        }

        // ── Check 2: Unknown parameters ───────────────────────────────────────
        for ((name, info) in passed) {
            if (name !in declaredMap) {
                val passedLineText = lines[info.second]
                val nameStart = passedLineText.indexOf(name)
                val startCol = if (nameStart >= 0) nameStart else 0
                val endCol = if (nameStart >= 0) nameStart + name.length else passedLineText.length
                diagnostics.add(
                    DiagnosticIssue(
                        message = "Unknown parameter '$name' — not declared in template '${templateRef.trim()}'",
                        severity = IssueSeverity.WARNING,
                        code = "unknown-param",
                        line = info.second,
                        startColumn = startCol,
                        endColumn = endCol
                    )
                )
            }
        }

        // ── Check 3: Type mismatches ──────────────────────────────────────────
        for ((name, info) in passed) {
            val decl = declaredMap[name] ?: continue // already flagged as unknown

            val value = info.first
            // Skip type checking for pipeline expressions — they're runtime values
            if (value.startsWith("$")) continue
            // Skip empty values
            if (value.isEmpty()) continue

            val paramType = decl.type.lowercase()
            val compatible = COMPATIBLE_TYPES[paramType] ?: continue // unknown type — skip

            val inferredType = inferValueType(value)
            if (inferredType !in compatible) {
                val passedLineText = lines[info.second]
                val nameStart = passedLineText.indexOf(name)
                val startCol = if (nameStart >= 0) nameStart else 0
                diagnostics.add(
                    DiagnosticIssue(
                        message = "Type mismatch for parameter '$name': template expects '${decl.type}', got value '$value' (inferred as '$inferredType')",
                        severity = IssueSeverity.WARNING,
                        code = "type-mismatch",
                        line = info.second,
                        startColumn = startCol,
                        endColumn = passedLineText.length
                    )
                )
            }
        }

        return diagnostics
    }
}

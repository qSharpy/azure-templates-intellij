package com.bogdanbujor.azuretemplates.core

/**
 * Shared data classes used across all modules of the Azure Templates Navigator plugin.
 */

data class TemplateParameter(
    val name: String,
    val type: String = "string",
    val default: String? = null,
    val required: Boolean = default == null,
    val line: Int = 0
)

data class PipelineVariable(
    val name: String,
    val value: String,
    val line: Int
)

data class VariableGroup(
    val name: String,
    val line: Int
)

data class ParsedVariables(
    val variables: Map<String, PipelineVariable>,
    val groups: List<VariableGroup>
)

data class ResolvedTemplate(
    val filePath: String?,
    val repoName: String? = null,
    val alias: String? = null,
    val unknownAlias: Boolean = false
)

data class TemplateCallSite(
    val templateRef: String,
    val line: Int
)

data class DiagnosticIssue(
    val message: String,
    val severity: IssueSeverity,
    val code: String,
    val line: Int,
    val startColumn: Int,
    val endColumn: Int,
    // ── Quick-fix context ─────────────────────────────────────────────────────
    /** For "missing-required-param": the parameter name to insert. */
    val paramName: String? = null,
    /** For "missing-required-param": the declared parameter type (e.g. "boolean"). */
    val paramType: String? = null,
    /** For "type-mismatch": the value that was actually passed. */
    val passedValue: String? = null,
    /**
     * For "missing-required-param": the 0-based line index of the `parameters:` block
     * inside the call site where the new entry should be appended.
     * -1 means the `parameters:` block does not exist yet and must be created.
     */
    val insertAfterLine: Int = -1
)

enum class IssueSeverity { ERROR, WARNING }

// Graph data
data class GraphNode(
    val id: String,
    val label: String,
    val relativePath: String? = null,
    val kind: NodeKind,
    val filePath: String? = null,
    val repoName: String? = null,
    val alias: String? = null,
    val paramCount: Int = 0,
    val requiredCount: Int = 0,
    val isScope: Boolean = false
)

enum class NodeKind { PIPELINE, LOCAL, EXTERNAL, MISSING, UNKNOWN }

data class GraphEdge(
    val source: String,
    val target: String,
    val label: String? = null,
    val direction: String? = null  // "upstream" or "downstream"
)

data class GraphData(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>
)

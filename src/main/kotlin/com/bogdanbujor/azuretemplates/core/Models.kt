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
    val endColumn: Int
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
    var paramCount: Int = 0,
    var requiredCount: Int = 0,
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

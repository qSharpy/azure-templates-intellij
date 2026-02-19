package com.bogdanbujor.azuretemplates.core

import java.io.File

/**
 * Builds graph data (nodes + edges) by scanning YAML files in a workspace.
 *
 * Port of `buildWorkspaceGraph()`, `buildFileGraph()`, `collectYamlFiles()`,
 * `extractTemplateRefs()`, `isPipelineRoot()` from the VS Code extension's
 * graphDataBuilder.js.
 */
object GraphBuilder {

    private val SKIP_DIRS = setOf(".git", "node_modules", ".vscode", "dist", "out", "build", ".idea")
    private val YAML_EXTENSION = Regex("\\.(ya?ml)$", RegexOption.IGNORE_CASE)
    private val PIPELINE_ROOT_REGEX = Regex("^(?:trigger|pr|schedules|stages|jobs|steps)\\s*:", RegexOption.MULTILINE)
    private val TEMPLATE_REF_REGEX = Regex("(?:^|\\s)-?\\s*template\\s*:\\s*(.+)$")

    /**
     * Recursively collects all *.yml / *.yaml files under [dir],
     * skipping common non-pipeline directories.
     */
    fun collectYamlFiles(dir: File, acc: MutableList<String> = mutableListOf()): List<String> {
        val entries = try {
            dir.listFiles() ?: return acc
        } catch (e: Exception) {
            return acc
        }

        for (entry in entries.sortedBy { it.name }) {
            if (entry.name in SKIP_DIRS) continue
            if (entry.isDirectory) {
                collectYamlFiles(entry, acc)
            } else if (entry.isFile && YAML_EXTENSION.containsMatchIn(entry.name)) {
                acc.add(entry.absolutePath)
            }
        }
        return acc
    }

    /**
     * Determines whether a YAML file looks like an Azure Pipeline root file
     * (has `trigger:`, `pr:`, `schedules:`, or `stages:` at the top level).
     */
    fun isPipelineRoot(text: String): Boolean {
        return PIPELINE_ROOT_REGEX.containsMatchIn(text)
    }

    /**
     * Parses a single YAML file and returns the raw template references it contains.
     */
    fun extractTemplateRefs(filePath: String): List<TemplateCallSite> {
        val text = try {
            File(filePath).readText()
        } catch (e: Exception) {
            return emptyList()
        }

        val refs = mutableListOf<TemplateCallSite>()
        val lines = text.replace("\r\n", "\n").split("\n")

        for (i in lines.indices) {
            // Strip YAML line comments before matching
            val stripped = lines[i].replace(Regex("(^\\s*#.*|\\s#.*)$"), "")
            val m = TEMPLATE_REF_REGEX.find(stripped)
            if (m != null) {
                refs.add(TemplateCallSite(templateRef = m.groupValues[1].trim(), line = i))
            }
        }
        return refs
    }

    /**
     * Builds the full graph data (nodes + edges) by scanning every YAML file
     * in the workspace root (or a sub-directory of it).
     *
     * @param workspaceRoot Absolute path to the workspace folder
     * @param subPath Optional relative sub-path to scan instead of the full root
     * @return [GraphData] with nodes and edges
     */
    fun buildWorkspaceGraph(workspaceRoot: String, subPath: String? = null): GraphData {
        val scanRoot = if (!subPath.isNullOrBlank()) {
            File(workspaceRoot, subPath.trim().trimStart('/', '\\'))
        } else {
            File(workspaceRoot)
        }

        val yamlFiles = collectYamlFiles(scanRoot)
        val nodeMap = mutableMapOf<String, GraphNode>()
        val edgeKeys = mutableSetOf<String>()
        val edges = mutableListOf<GraphEdge>()

        // ── Pass 1: register every YAML file as a node ──────────────────────
        for (filePath in yamlFiles) {
            val text = try { File(filePath).readText() } catch (e: Exception) { "" }
            val kind = if (isPipelineRoot(text)) NodeKind.PIPELINE else NodeKind.LOCAL
            val relativePath = File(filePath).relativeTo(File(workspaceRoot)).path.replace("\\", "/")

            nodeMap[filePath] = GraphNode(
                id = filePath,
                label = File(filePath).name,
                relativePath = relativePath,
                kind = kind,
                filePath = filePath,
                paramCount = 0,
                requiredCount = 0
            )
        }

        // ── Pass 2: for each file, resolve its template references ──────────
        for (filePath in yamlFiles) {
            val text = try { File(filePath).readText() } catch (e: Exception) { continue }
            val repoAliases = RepositoryAliasParser.parse(text)
            val refs = extractTemplateRefs(filePath)

            for ((templateRef, _) in refs) {
                // Skip variable expressions
                if (templateRef.contains("\${") || templateRef.contains("\$(")) continue

                val resolved = TemplateResolver.resolve(templateRef, filePath, repoAliases) ?: continue

                var targetId: String
                var edgeLabel: String? = null

                if (resolved.unknownAlias) {
                    // Synthetic node for unknown alias
                    targetId = "UNKNOWN_ALIAS:${resolved.alias}:$templateRef"
                    if (targetId !in nodeMap) {
                        nodeMap[targetId] = GraphNode(
                            id = targetId,
                            label = File(templateRef.split("@")[0]).name,
                            kind = NodeKind.UNKNOWN,
                            alias = resolved.alias,
                            paramCount = 0,
                            requiredCount = 0
                        )
                    }
                    edgeLabel = "@${resolved.alias}"
                } else {
                    val resolvedPath = resolved.filePath ?: continue
                    val repoName = resolved.repoName
                    val alias = resolved.alias

                    if (!File(resolvedPath).exists()) {
                        // Missing file node
                        targetId = "MISSING:$resolvedPath"
                        if (targetId !in nodeMap) {
                            nodeMap[targetId] = GraphNode(
                                id = targetId,
                                label = File(resolvedPath).name,
                                kind = NodeKind.MISSING,
                                filePath = resolvedPath,
                                repoName = repoName,
                                paramCount = 0,
                                requiredCount = 0
                            )
                        }
                    } else {
                        targetId = resolvedPath

                        // Ensure the target node exists (may be outside workspace)
                        if (targetId !in nodeMap) {
                            val relativePath = try {
                                File(resolvedPath).relativeTo(File(workspaceRoot)).path.replace("\\", "/")
                            } catch (e: Exception) {
                                File(resolvedPath).name
                            }
                            nodeMap[targetId] = GraphNode(
                                id = targetId,
                                label = File(resolvedPath).name,
                                relativePath = relativePath,
                                kind = if (repoName != null) NodeKind.EXTERNAL else NodeKind.LOCAL,
                                filePath = resolvedPath,
                                repoName = repoName,
                                paramCount = 0,
                                requiredCount = 0
                            )
                        }

                        // Upgrade kind to 'external' if referenced via a repo alias
                        val existingNode = nodeMap[targetId]!!
                        if (repoName != null && existingNode.kind != NodeKind.EXTERNAL) {
                            nodeMap[targetId] = existingNode.copy(kind = NodeKind.EXTERNAL, repoName = repoName)
                        }

                        if (alias != null && alias != "self") {
                            edgeLabel = "@$alias"
                        }
                    }
                }

                // Add edge (deduplicated)
                val edgeKey = "$filePath→$targetId"
                if (edgeKey !in edgeKeys) {
                    edgeKeys.add(edgeKey)
                    edges.add(GraphEdge(source = filePath, target = targetId, label = edgeLabel))
                }
            }
        }

        // ── Pass 3: fill in paramCount for all resolvable nodes ─────────────
        for ((_, node) in nodeMap) {
            if (node.filePath != null && node.kind != NodeKind.MISSING) {
                try {
                    val tplText = File(node.filePath).readText()
                    val params = ParameterParser.parse(tplText)
                    node.paramCount = params.size
                    node.requiredCount = params.count { it.required }
                } catch (e: Exception) { /* ignore */ }
            }
        }

        return GraphData(
            nodes = nodeMap.values.toList(),
            edges = edges
        )
    }

    /**
     * Builds a scoped graph for a single file: the file itself as the root node,
     * plus all templates it directly references (downstream, depth = 1) AND all
     * workspace files that reference it (upstream callers, depth = 1).
     *
     * @param filePath Absolute path to the pipeline / template file
     * @param workspaceRoot Absolute path to the workspace root
     * @return [GraphData] with nodes and edges
     */
    fun buildFileGraph(filePath: String, workspaceRoot: String): GraphData {
        val nodeMap = mutableMapOf<String, GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        val edgeKeys = mutableSetOf<String>()

        // ── Root node ───────────────────────────────────────────────────────
        val rootText = try { File(filePath).readText() } catch (e: Exception) { "" }
        val rootKind = if (isPipelineRoot(rootText)) NodeKind.PIPELINE else NodeKind.LOCAL
        val rootParams = ParameterParser.parse(rootText)
        val relativePath = try {
            File(filePath).relativeTo(File(workspaceRoot)).path.replace("\\", "/")
        } catch (e: Exception) {
            File(filePath).name
        }

        nodeMap[filePath] = GraphNode(
            id = filePath,
            label = File(filePath).name,
            relativePath = relativePath,
            kind = rootKind,
            filePath = filePath,
            paramCount = rootParams.size,
            requiredCount = rootParams.count { it.required },
            isScope = true
        )

        // ── Downstream: direct children (templates called by this file) ─────
        val repoAliases = RepositoryAliasParser.parse(rootText)
        val refs = extractTemplateRefs(filePath)

        for ((templateRef, _) in refs) {
            if (templateRef.contains("\${") || templateRef.contains("\$(")) continue
            val resolved = TemplateResolver.resolve(templateRef, filePath, repoAliases) ?: continue
            addResolvedRef(filePath, templateRef, resolved, nodeMap, edges, edgeKeys, "downstream", workspaceRoot)
        }

        // ── Upstream: find all workspace YAML files that reference this file ─
        val allYaml = collectYamlFiles(File(workspaceRoot))
        for (callerFile in allYaml) {
            if (callerFile == filePath) continue

            val callerText = try { File(callerFile).readText() } catch (e: Exception) { continue }
            val callerAliases = RepositoryAliasParser.parse(callerText)
            val callerRefs = extractTemplateRefs(callerFile)

            for ((templateRef, _) in callerRefs) {
                if (templateRef.contains("\${") || templateRef.contains("\$(")) continue
                val resolved = TemplateResolver.resolve(templateRef, callerFile, callerAliases) ?: continue

                // Only care about refs that resolve to our focal file
                if (resolved.filePath != filePath) continue

                // Ensure the caller node exists
                if (callerFile !in nodeMap) {
                    val callerKind = if (isPipelineRoot(callerText)) NodeKind.PIPELINE else NodeKind.LOCAL
                    val callerParams = ParameterParser.parse(callerText)
                    val callerRelPath = try {
                        File(callerFile).relativeTo(File(workspaceRoot)).path.replace("\\", "/")
                    } catch (e: Exception) {
                        File(callerFile).name
                    }

                    nodeMap[callerFile] = GraphNode(
                        id = callerFile,
                        label = File(callerFile).name,
                        relativePath = callerRelPath,
                        kind = callerKind,
                        filePath = callerFile,
                        paramCount = callerParams.size,
                        requiredCount = callerParams.count { it.required }
                    )
                }

                // Add upstream edge: caller → focal file
                val edgeKey = "$callerFile→$filePath"
                if (edgeKey !in edgeKeys) {
                    edgeKeys.add(edgeKey)
                    val edgeLabel = if (resolved.alias != null && resolved.alias != "self") "@${resolved.alias}" else null
                    edges.add(GraphEdge(source = callerFile, target = filePath, label = edgeLabel, direction = "upstream"))
                }
            }
        }

        return GraphData(
            nodes = nodeMap.values.toList(),
            edges = edges
        )
    }

    private fun addResolvedRef(
        sourceId: String,
        templateRef: String,
        resolved: ResolvedTemplate,
        nodeMap: MutableMap<String, GraphNode>,
        edges: MutableList<GraphEdge>,
        edgeKeys: MutableSet<String>,
        direction: String,
        workspaceRoot: String
    ) {
        var targetId: String
        var edgeLabel: String? = null

        if (resolved.unknownAlias) {
            targetId = "UNKNOWN_ALIAS:${resolved.alias}:$templateRef"
            if (targetId !in nodeMap) {
                nodeMap[targetId] = GraphNode(
                    id = targetId,
                    label = File(templateRef.split("@")[0]).name,
                    kind = NodeKind.UNKNOWN,
                    alias = resolved.alias,
                    paramCount = 0,
                    requiredCount = 0
                )
            }
            edgeLabel = "@${resolved.alias}"
        } else {
            val resolvedPath = resolved.filePath ?: return
            val repoName = resolved.repoName
            val alias = resolved.alias

            if (!File(resolvedPath).exists()) {
                targetId = "MISSING:$resolvedPath"
                if (targetId !in nodeMap) {
                    val relPath = try {
                        File(resolvedPath).relativeTo(File(workspaceRoot)).path.replace("\\", "/")
                    } catch (e: Exception) {
                        File(resolvedPath).name
                    }
                    nodeMap[targetId] = GraphNode(
                        id = targetId,
                        label = File(resolvedPath).name,
                        relativePath = relPath,
                        kind = NodeKind.MISSING,
                        filePath = resolvedPath,
                        repoName = repoName,
                        paramCount = 0,
                        requiredCount = 0
                    )
                }
            } else {
                targetId = resolvedPath
                if (targetId !in nodeMap) {
                    val childParams = try {
                        ParameterParser.parse(File(resolvedPath).readText())
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val relPath = try {
                        File(resolvedPath).relativeTo(File(workspaceRoot)).path.replace("\\", "/")
                    } catch (e: Exception) {
                        File(resolvedPath).name
                    }
                    nodeMap[targetId] = GraphNode(
                        id = targetId,
                        label = File(resolvedPath).name,
                        relativePath = relPath,
                        kind = if (repoName != null) NodeKind.EXTERNAL else NodeKind.LOCAL,
                        filePath = resolvedPath,
                        repoName = repoName,
                        paramCount = childParams.size,
                        requiredCount = childParams.count { it.required }
                    )
                }

                // Upgrade to external if referenced via alias
                val existingNode = nodeMap[targetId]!!
                if (repoName != null && existingNode.kind != NodeKind.EXTERNAL) {
                    nodeMap[targetId] = existingNode.copy(kind = NodeKind.EXTERNAL, repoName = repoName)
                }

                if (alias != null && alias != "self") {
                    edgeLabel = "@$alias"
                }
            }
        }

        // For upstream direction the edge goes: caller → active file
        // For downstream direction the edge goes: active file → template
        val edgeSrc = if (direction == "upstream") targetId else sourceId
        val edgeTgt = if (direction == "upstream") sourceId else targetId

        val edgeKey = "$edgeSrc→$edgeTgt"
        if (edgeKey !in edgeKeys) {
            edgeKeys.add(edgeKey)
            edges.add(GraphEdge(source = edgeSrc, target = edgeTgt, label = edgeLabel, direction = direction))
        }
    }
}

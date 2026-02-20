package com.bogdanbujor.azuretemplates.services

import com.bogdanbujor.azuretemplates.core.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File

/**
 * Project-level service that maintains a cached index of all YAML files
 * and their template references.
 *
 * Provides fast lookups for upstream callers, downstream dependencies,
 * and feeds data to the tree view, graph, and diagnostics panel.
 *
 * Call [addIndexListener] to be notified whenever the index is updated
 * (e.g. after a YAML file is saved). Listeners are invoked on the EDT.
 */
@Service(Service.Level.PROJECT)
class TemplateIndexService(private val project: Project) {

    data class FileIndex(
        val filePath: String,
        val isPipeline: Boolean,
        val templateRefs: List<TemplateCallSite>,
        val parameters: List<TemplateParameter>,
        val repoAliases: Map<String, String>
    )

    private val index = mutableMapOf<String, FileIndex>()
    private val upstreamCache = mutableMapOf<String, List<String>>()

    /**
     * Worst-severity diagnostic per file path, rebuilt whenever the index changes.
     * Null means no issues were found for that file.
     */
    private val diagnosticsCache = mutableMapOf<String, IssueSeverity>()

    /** Listeners notified on the EDT after every index update. */
    private val indexListeners = mutableListOf<() -> Unit>()

    init {
        // Listen for file changes and re-index affected YAML files.
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val yamlEvents = events.filter { event ->
                        val path = event.path
                        path.endsWith(".yml") || path.endsWith(".yaml")
                    }
                    if (yamlEvents.isNotEmpty()) {
                        for (event in yamlEvents) {
                            reindexFile(event.path)
                        }
                        rebuildUpstreamCache()
                        rebuildDiagnosticsCache()
                        // Notify UI components (e.g. DiagnosticsPanel) that the index changed.
                        notifyIndexUpdated()
                    }
                }
            }
        )
    }

    /**
     * Registers a listener that will be called on the EDT after every index update.
     * Typically called by UI components during their initialization.
     */
    fun addIndexListener(listener: () -> Unit) {
        indexListeners.add(listener)
    }

    /**
     * Performs a full index of all YAML files in the project on a background thread.
     * [onComplete] is invoked on the EDT after indexing finishes (optional).
     */
    fun fullIndexAsync(onComplete: (() -> Unit)? = null) {
        object : Task.Backgroundable(project, "Indexing Azure Pipeline templates…", false) {
            override fun run(indicator: ProgressIndicator) {
                val basePath = project.basePath ?: return
                val yamlFiles = GraphBuilder.collectYamlFiles(File(basePath))

                indicator.isIndeterminate = false
                index.clear()
                yamlFiles.forEachIndexed { idx, filePath ->
                    indicator.fraction = idx.toDouble() / yamlFiles.size
                    indicator.text2 = File(filePath).name
                    indexFile(filePath)
                }
                rebuildUpstreamCache()
                rebuildDiagnosticsCache()
            }

            override fun onSuccess() {
                notifyIndexUpdated()
                onComplete?.invoke()
            }
        }.queue()
    }

    /**
     * Performs a full index synchronously (use only from background threads or tests).
     */
    fun fullIndex() {
        val basePath = project.basePath ?: return
        val yamlFiles = GraphBuilder.collectYamlFiles(File(basePath))

        index.clear()
        for (filePath in yamlFiles) {
            indexFile(filePath)
        }
        rebuildUpstreamCache()
        rebuildDiagnosticsCache()
    }

    /**
     * Indexes a single file.
     */
    private fun indexFile(filePath: String) {
        val text = try {
            File(filePath).readText()
        } catch (e: Exception) {
            return
        }

        val isPipeline = GraphBuilder.isPipelineRoot(text)
        val templateRefs = GraphBuilder.extractTemplateRefs(filePath)
        val parameters = ParameterParser.parse(text)
        val repoAliases = RepositoryAliasParser.parse(text)

        index[filePath] = FileIndex(
            filePath = filePath,
            isPipeline = isPipeline,
            templateRefs = templateRefs,
            parameters = parameters,
            repoAliases = repoAliases
        )
    }

    /**
     * Re-indexes a single file (called on file change).
     */
    private fun reindexFile(filePath: String) {
        if (File(filePath).exists()) {
            indexFile(filePath)
        } else {
            index.remove(filePath)
        }
    }

    /**
     * Rebuilds the upstream caller cache from the current index.
     */
    private fun rebuildUpstreamCache() {
        upstreamCache.clear()

        for ((callerPath, fileIndex) in index) {
            for (ref in fileIndex.templateRefs) {
                val resolved = TemplateResolver.resolve(ref.templateRef, callerPath, fileIndex.repoAliases)
                if (resolved != null && resolved.filePath != null && !resolved.unknownAlias) {
                    val targetPath = resolved.filePath
                    val existing = upstreamCache.getOrDefault(targetPath, emptyList())
                    if (callerPath !in existing) {
                        upstreamCache[targetPath] = existing + callerPath
                    }
                }
            }
        }
    }

    /**
     * Rebuilds the worst-severity diagnostic per file from the current index.
     * Uses the same checks as DiagnosticsToolWindow (caller-side + unused params).
     */
    private fun rebuildDiagnosticsCache() {
        diagnosticsCache.clear()

        val commentStripRegex = Regex("(^\\s*#.*|\\s#.*)$")
        val templateRefRegex = Regex("(?:^|\\s)-?\\s*template\\s*:\\s*(.+)$")

        for ((filePath, fileIndex) in index) {
            val text = try { File(filePath).readText() } catch (e: Exception) { continue }
            val rawLines = text.replace("\r\n", "\n").split("\n")

            var worstSeverity: IssueSeverity? = null

            fun record(severity: IssueSeverity) {
                if (worstSeverity == null || severity == IssueSeverity.ERROR) {
                    worstSeverity = severity
                }
            }

            // Caller-side: validate every template call site
            if (fileIndex.templateRefs.isNotEmpty()) {
                for (i in rawLines.indices) {
                    val stripped = rawLines[i].replace(commentStripRegex, "")
                    val match = templateRefRegex.find(stripped) ?: continue
                    val templateRef = match.groupValues[1].trim()
                    if (templateRef.contains("\${") || templateRef.contains("\$(")) continue
                    val issues = CallSiteValidator.validate(rawLines, i, templateRef, filePath, fileIndex.repoAliases)
                    for (issue in issues) record(issue.severity)
                    if (worstSeverity == IssueSeverity.ERROR) break
                }
            }

            // Template-side: unused parameter declarations
            if (worstSeverity != IssueSeverity.ERROR) {
                val unusedIssues = UnusedParameterChecker.check(text)
                if (unusedIssues.isNotEmpty()) record(IssueSeverity.WARNING)
            }

            if (worstSeverity != null) {
                diagnosticsCache[filePath] = worstSeverity!!
            }
        }
    }

    /**
     * Invokes all registered index listeners on the EDT.
     */
    private fun notifyIndexUpdated() {
        ApplicationManager.getApplication().invokeLater {
            for (listener in indexListeners) {
                listener()
            }
        }
    }

    /**
     * Returns the list of files that call the given template file (upstream callers).
     */
    fun getUpstreamCallers(filePath: String): List<String> {
        return upstreamCache[filePath] ?: emptyList()
    }

    /**
     * Returns the worst diagnostic severity for the given file, or null if no issues.
     */
    fun getFileSeverity(filePath: String): IssueSeverity? {
        return diagnosticsCache[filePath]
    }

    /**
     * Returns the list of template references from the given file (downstream dependencies).
     */
    fun getDownstreamRefs(filePath: String): List<TemplateCallSite> {
        return index[filePath]?.templateRefs ?: emptyList()
    }

    /**
     * Returns the file index for a given file path.
     */
    fun getFileIndex(filePath: String): FileIndex? {
        return index[filePath]
    }

    /**
     * Returns all indexed file paths.
     */
    fun getAllFiles(): Set<String> {
        return index.keys
    }

    companion object {
        fun getInstance(project: Project): TemplateIndexService {
            return project.getService(TemplateIndexService::class.java)
        }
    }
}

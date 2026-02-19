package com.bogdanbujor.azuretemplates.services

import com.bogdanbujor.azuretemplates.core.*
import com.intellij.openapi.components.Service
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

    init {
        // Listen for file changes
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
                    }
                }
            }
        )
    }

    /**
     * Performs a full index of all YAML files in the project.
     */
    fun fullIndex() {
        val basePath = project.basePath ?: return
        val yamlFiles = GraphBuilder.collectYamlFiles(File(basePath))

        index.clear()
        for (filePath in yamlFiles) {
            indexFile(filePath)
        }
        rebuildUpstreamCache()
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
        val basePath = project.basePath ?: return

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
     * Returns the list of files that call the given template file (upstream callers).
     */
    fun getUpstreamCallers(filePath: String): List<String> {
        return upstreamCache[filePath] ?: emptyList()
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

package com.bogdanbujor.azuretemplates.ui

import com.bogdanbujor.azuretemplates.core.*
import com.bogdanbujor.azuretemplates.services.TemplateIndexService
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Tool window showing the template dependency tree for the active YAML file.
 *
 * Port of treeViewProvider.js from the VS Code extension.
 */
class DependencyTreeToolWindow : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DependencyTreePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

data class TreeNodeData(
    val label: String,
    val filePath: String? = null,
    val icon: Icon = AllIcons.FileTypes.Yaml,
    val isGroup: Boolean = false,
    val description: String? = null
)

class DependencyTreePanel(private val project: Project) {

    private val root = DefaultMutableTreeNode(TreeNodeData("No file selected", isGroup = true))
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    val component: JBScrollPane

    init {
        tree.isRootVisible = false
        tree.cellRenderer = TemplateTreeCellRenderer()

        // Double-click to open file
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? TreeNodeData ?: return
                    val filePath = data.filePath ?: return
                    openFile(filePath)
                }
            }
        })

        component = JBScrollPane(tree)

        // Listen for editor changes
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    val file = event.newFile ?: return
                    if (file.extension == "yml" || file.extension == "yaml") {
                        refresh(file)
                    }
                }
            }
        )

        // Initial refresh
        val currentFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (currentFile != null && (currentFile.extension == "yml" || currentFile.extension == "yaml")) {
            refresh(currentFile)
        }
    }

    fun refresh(file: VirtualFile? = null) {
        val targetFile = file ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val filePath = targetFile.path
        val basePath = project.basePath ?: return

        root.removeAllChildren()

        // Ensure index is built
        val indexService = TemplateIndexService.getInstance(project)
        if (indexService.getAllFiles().isEmpty()) {
            indexService.fullIndex()
        }

        // Upstream callers — added as a top-level node ABOVE the file node
        val callers = indexService.getUpstreamCallers(filePath)
        if (callers.isNotEmpty()) {
            val callerCountText = if (callers.size == 1) "1 caller" else "${callers.size} callers"
            val callersNode = DefaultMutableTreeNode(
                TreeNodeData("Called by", isGroup = true, icon = AllIcons.General.ArrowDown, description = callerCountText)
            )
            for (callerPath in callers) {
                callersNode.add(DefaultMutableTreeNode(
                    TreeNodeData(
                        label = File(callerPath).name,
                        filePath = callerPath,
                        icon = AllIcons.FileTypes.Yaml
                    )
                ))
            }
            root.add(callersNode)
        }

        // File node with downstream dependencies as children
        val fileNode = DefaultMutableTreeNode(
            TreeNodeData(
                label = targetFile.name,
                filePath = filePath,
                icon = AllIcons.FileTypes.Yaml
            )
        )

        // Downstream dependencies
        val text = try { File(filePath).readText() } catch (e: Exception) { return }
        val repoAliases = RepositoryAliasParser.parse(text)
        val refs = GraphBuilder.extractTemplateRefs(filePath)

        if (refs.isNotEmpty()) {
            val visited = mutableSetOf<String>()
            addDownstreamNodes(fileNode, refs, filePath, repoAliases, basePath, visited)
        }

        root.add(fileNode)

        treeModel.reload()
        // Expand all nodes
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }

    private fun addDownstreamNodes(
        parentNode: DefaultMutableTreeNode,
        refs: List<TemplateCallSite>,
        callerFile: String,
        repoAliases: Map<String, String>,
        basePath: String,
        visited: MutableSet<String>
    ) {
        for (ref in refs) {
            if (ref.templateRef.contains("\${") || ref.templateRef.contains("\$(")) continue

            val resolved = TemplateResolver.resolve(ref.templateRef, callerFile, repoAliases) ?: continue

            if (resolved.unknownAlias) {
                parentNode.add(DefaultMutableTreeNode(
                    TreeNodeData(
                        label = "${ref.templateRef} (unknown alias @${resolved.alias})",
                        icon = AllIcons.General.Warning
                    )
                ))
                continue
            }

            val resolvedPath = resolved.filePath ?: continue

            if (!File(resolvedPath).exists()) {
                parentNode.add(DefaultMutableTreeNode(
                    TreeNodeData(
                        label = "${ref.templateRef} (not found)",
                        icon = AllIcons.General.Error
                    )
                ))
                continue
            }

            // Cycle detection
            if (resolvedPath in visited) {
                parentNode.add(DefaultMutableTreeNode(
                    TreeNodeData(
                        label = "${File(resolvedPath).name} (cycle)",
                        filePath = resolvedPath,
                        icon = AllIcons.Actions.Undo
                    )
                ))
                continue
            }

            val icon = if (resolved.repoName != null) AllIcons.Nodes.PpLib else AllIcons.FileTypes.Yaml
            val label = if (resolved.repoName != null) {
                "${File(resolvedPath).name} (@${resolved.alias})"
            } else {
                File(resolvedPath).name
            }

            val childNode = DefaultMutableTreeNode(
                TreeNodeData(label = label, filePath = resolvedPath, icon = icon)
            )

            // Recursively add children (with cycle detection)
            visited.add(resolvedPath)
            val childText = try { File(resolvedPath).readText() } catch (e: Exception) { "" }
            val childAliases = RepositoryAliasParser.parse(childText)
            val childRefs = GraphBuilder.extractTemplateRefs(resolvedPath)
            if (childRefs.isNotEmpty()) {
                addDownstreamNodes(childNode, childRefs, resolvedPath, childAliases, basePath, visited)
            }
            visited.remove(resolvedPath)

            parentNode.add(childNode)
        }
    }

    private fun openFile(filePath: String) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val descriptor = OpenFileDescriptor(project, virtualFile, 0, 0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
}

/**
 * Custom tree cell renderer for the dependency tree.
 */
class TemplateTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? TreeNodeData ?: return
        icon = data.icon
        append(data.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (data.description != null) {
            append("  ${data.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

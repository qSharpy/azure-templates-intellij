package com.bogdanbujor.azuretemplates.ui

import com.bogdanbujor.azuretemplates.core.*
import com.bogdanbujor.azuretemplates.services.TemplateIndexService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Tool window showing all template diagnostics grouped by file.
 *
 * Two categories of issues are surfaced:
 * 1. **Caller-side** — missing required params, unknown params, type mismatches
 *    (detected by [CallSiteValidator] for every file that references a template).
 * 2. **Template-side** — unused parameter declarations
 *    (detected by [UnusedParameterChecker] for every YAML file that has a
 *    `parameters:` block, regardless of whether it also calls other templates).
 *
 * Port of diagnosticsPanelProvider.js from the VS Code extension.
 */
class DiagnosticsToolWindow : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DiagnosticsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.putUserData(PANEL_KEY, panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        val PANEL_KEY = com.intellij.openapi.util.Key.create<DiagnosticsPanel>("DiagnosticsPanel")
    }
}

data class DiagnosticNodeData(
    val label: String,
    val filePath: String? = null,
    val line: Int = -1,
    val column: Int = 0,
    val icon: javax.swing.Icon = AllIcons.General.Information,
    val isFile: Boolean = false
)

class DiagnosticsPanel(private val project: Project) {

    companion object {
        private val COMMENT_STRIP_REGEX = Regex("(^\\s*#.*|\\s#.*)$")
        private val TEMPLATE_REF_REGEX = Regex("(?:^|\\s)-?\\s*template\\s*:\\s*(.+)$")
    }

    private val root = DefaultMutableTreeNode(DiagnosticNodeData("Diagnostics", icon = AllIcons.General.InspectionsEye))
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    val component: JBScrollPane

    init {
        tree.isRootVisible = false
        tree.cellRenderer = DiagnosticTreeCellRenderer()

        // Double-click to navigate
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? DiagnosticNodeData ?: return
                    val filePath = data.filePath ?: return
                    navigateTo(filePath, data.line, data.column)
                }
            }
        })

        component = JBScrollPane(tree)

        // Subscribe to index-updated events so the panel refreshes automatically
        // whenever a YAML file is saved or the index is rebuilt.
        TemplateIndexService.getInstance(project).addIndexListener { refresh() }

        // Initial refresh so the panel is populated on creation
        refresh()
    }

    fun refresh() {
        val basePath = project.basePath ?: return
        val indexService = TemplateIndexService.getInstance(project)

        // If the index is empty, trigger background indexing and re-run refresh when done.
        if (indexService.getAllFiles().isEmpty()) {
            indexService.fullIndexAsync(onComplete = { refresh() })
            return
        }

        // Collect diagnostics on a background thread, then update the tree on the EDT.
        object : Task.Backgroundable(project, "Refreshing Azure Pipeline diagnostics…", false) {
            private val fileNodes = mutableListOf<Pair<DefaultMutableTreeNode, String>>()

            override fun run(indicator: ProgressIndicator) {
                val allFiles = indexService.getAllFiles().toList()
                indicator.isIndeterminate = false

                allFiles.forEachIndexed { idx, filePath ->
                    indicator.fraction = idx.toDouble() / allFiles.size
                    indicator.text2 = File(filePath).name

                    val fileIndex = indexService.getFileIndex(filePath) ?: return@forEachIndexed

                    // Re-read the file once; used for both caller-side and template-side checks.
                    val text = try { File(filePath).readText() } catch (e: Exception) { return@forEachIndexed }
                    val rawLines = text.replace("\r\n", "\n").split("\n")

                    val fileIssues = mutableListOf<DiagnosticIssue>()

                    // ── Caller-side: validate every template call site ────────────────
                    if (fileIndex.templateRefs.isNotEmpty()) {
                        for (i in rawLines.indices) {
                            val stripped = rawLines[i].replace(COMMENT_STRIP_REGEX, "")
                            val match = TEMPLATE_REF_REGEX.find(stripped) ?: continue
                            val templateRef = match.groupValues[1].trim()
                            if (templateRef.contains("\${") || templateRef.contains("\$(")) continue
                            val issues = CallSiteValidator.validate(rawLines, i, templateRef, filePath, fileIndex.repoAliases)
                            fileIssues.addAll(issues)
                        }
                    }

                    // ── Template-side: detect unused parameter declarations ───────────
                    // Run on every YAML file that declares a parameters: block, not just
                    // those that also call other templates.
                    val unusedIssues = UnusedParameterChecker.check(text)
                    for (unused in unusedIssues) {
                        val declarationLine = unused.declarationLine
                        val lineText = if (declarationLine < rawLines.size) rawLines[declarationLine] else ""
                        val nameStart = lineText.indexOf(unused.paramName).coerceAtLeast(0)
                        fileIssues.add(
                            DiagnosticIssue(
                                message = "Unused parameter '${unused.paramName}' — declared but never referenced in the template body",
                                severity = IssueSeverity.WARNING,
                                code = "unused-param",
                                line = declarationLine,
                                startColumn = nameStart,
                                endColumn = (nameStart + unused.paramName.length).coerceAtMost(lineText.length),
                                paramName = unused.paramName
                            )
                        )
                    }

                    if (fileIssues.isNotEmpty()) {
                        val errors = fileIssues.count { it.severity == IssueSeverity.ERROR }
                        val warnings = fileIssues.count { it.severity == IssueSeverity.WARNING }

                        val relativePath = try {
                            File(filePath).relativeTo(File(basePath)).path
                        } catch (e: Exception) {
                            File(filePath).name
                        }

                        val fileIcon = if (errors > 0) AllIcons.General.Error else AllIcons.General.Warning
                        val fileLabel = "$relativePath ($errors errors, $warnings warnings)"

                        val fileNode = DefaultMutableTreeNode(
                            DiagnosticNodeData(
                                label = fileLabel,
                                filePath = filePath,
                                icon = fileIcon,
                                isFile = true
                            )
                        )

                        for (issue in fileIssues) {
                            val issueIcon = when (issue.severity) {
                                IssueSeverity.ERROR -> AllIcons.General.Error
                                IssueSeverity.WARNING -> AllIcons.General.Warning
                            }
                            fileNode.add(DefaultMutableTreeNode(
                                DiagnosticNodeData(
                                    label = "Line ${issue.line + 1}: ${issue.message}",
                                    filePath = filePath,
                                    line = issue.line,
                                    column = issue.startColumn,
                                    icon = issueIcon
                                )
                            ))
                        }

                        fileNodes.add(Pair(fileNode, filePath))
                    }
                }
            }

            override fun onSuccess() {
                // Update the tree on the EDT
                ApplicationManager.getApplication().invokeLater {
                    root.removeAllChildren()
                    for ((fileNode, _) in fileNodes) {
                        root.add(fileNode)
                    }
                    treeModel.reload()
                    for (i in 0 until tree.rowCount) {
                        tree.expandRow(i)
                    }
                }
            }
        }.queue()
    }

    /**
     * Selects and scrolls to the file node matching [filePath] in the diagnostics tree.
     * Expands the node so its issue children are visible.
     * Must be called on the EDT.
     */
    fun selectFile(filePath: String) {
        val rootNode = treeModel.root as? DefaultMutableTreeNode ?: return
        val children = rootNode.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as? DefaultMutableTreeNode ?: continue
            val data = child.userObject as? DiagnosticNodeData ?: continue
            if (data.filePath == filePath && data.isFile) {
                val path = javax.swing.tree.TreePath(child.path)
                tree.selectionPath = path
                tree.expandPath(path)
                tree.scrollPathToVisible(path)
                return
            }
        }
    }

    private fun navigateTo(filePath: String, line: Int, column: Int) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val targetLine = if (line >= 0) line else 0
        val descriptor = OpenFileDescriptor(project, virtualFile, targetLine, column)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
}

class DiagnosticTreeCellRenderer : javax.swing.tree.DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: javax.swing.JTree?,
        value: Any?,
        sel: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): java.awt.Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode ?: return this
        val data = node.userObject as? DiagnosticNodeData ?: return this
        text = data.label
        icon = data.icon
        return this
    }
}

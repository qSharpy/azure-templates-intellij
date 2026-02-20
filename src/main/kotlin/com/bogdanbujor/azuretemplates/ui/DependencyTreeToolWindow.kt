package com.bogdanbujor.azuretemplates.ui

import com.bogdanbujor.azuretemplates.core.*
import com.bogdanbujor.azuretemplates.services.TemplateIndexService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
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
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.util.Timer
import java.util.TimerTask
import java.io.File
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.MatteBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
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
    val fullPathLabel: String? = null,
    val filePath: String? = null,
    val icon: Icon? = AllIcons.FileTypes.Yaml,
    val isGroup: Boolean = false,
    val description: String? = null,
    val severity: IssueSeverity? = null
)

class DependencyTreePanel(private val project: Project) {

    private val root = DefaultMutableTreeNode(TreeNodeData("No file selected", isGroup = true))
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private var showFullPath = false
    private var treeExpanded = true
    private var hideWarnings = false
    private var currentFilePath: String? = null

    /** Label shown in the header row with the currently active file name. */
    private val fileHeaderLabel = JLabel("No file selected", AllIcons.FileTypes.Yaml, SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(0, 2, 0, 8)
    }

    // ── Search bar components ──────────────────────────────────────────
    private val searchField = JBTextField().apply {
        emptyText.text = "Search templates…"
        border = CompoundBorder(
            MatteBorder(0, 0, 1, 0, JBColor.border()),
            JBUI.Borders.empty(4, 6)
        )
    }

    /** List model backing the search results popup. */
    private val searchResultsModel = DefaultListModel<FuzzySearch.SearchResult>()

    /** The popup list showing search results beneath the search field. */
    private val searchResultsList = JList(searchResultsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SearchResultCellRenderer()
        fixedCellHeight = 28
    }

    /** Popup window that hosts the search results list. */
    private var searchPopup: JWindow? = null

    /** Debounce timer for search input. */
    private var searchDebounceTimer: Timer? = null

    val component: JPanel

    init {
        tree.isRootVisible = false
        tree.cellRenderer = TemplateTreeCellRenderer(
            showFullPathProvider = { showFullPath },
            hideWarningsProvider = { hideWarnings }
        )

        // Double-click to open file; right-click for context menu
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !SwingUtilities.isRightMouseButton(e)) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? TreeNodeData ?: return
                    val filePath = data.filePath ?: return
                    openFile(filePath)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    // Select the node under the cursor before showing the menu
                    val path = tree.getPathForLocation(e.x, e.y)
                    if (path != null) tree.selectionPath = path
                    showTreeContextMenu(e)
                }
            }
        })

        // ── Wire up search ─────────────────────────────────────────────
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchChanged()
        })

        // Keyboard handling for the search field
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        e.consume()
                        selectTopSearchResult()
                    }
                    KeyEvent.VK_DOWN -> {
                        e.consume()
                        moveSearchSelection(1)
                    }
                    KeyEvent.VK_UP -> {
                        e.consume()
                        moveSearchSelection(-1)
                    }
                    KeyEvent.VK_ESCAPE -> {
                        e.consume()
                        hideSearchPopup()
                        searchField.text = ""
                    }
                }
            }
        })

        // Hide popup when search field loses focus, but only if focus didn't go to the popup
        searchField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                // Check if focus went to the popup or its children — if so, keep it open
                val opposite = e.oppositeComponent
                if (opposite != null) {
                    val popupWindow = searchPopup
                    if (popupWindow != null && (opposite === searchResultsList ||
                            SwingUtilities.isDescendingFrom(opposite, popupWindow))) {
                        // Focus went to the popup — keep it visible and return focus to search
                        SwingUtilities.invokeLater { searchField.requestFocusInWindow() }
                        return
                    }
                }
                // Focus truly left the search area — hide after a short delay for click processing
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        SwingUtilities.invokeLater {
                            // Double-check: if search field regained focus, don't hide
                            if (!searchField.hasFocus()) {
                                hideSearchPopup()
                            }
                        }
                    }
                }, 250)
            }
        })

        // Click on a search result opens the file
        searchResultsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = searchResultsList.locationToIndex(e.point)
                if (idx >= 0) {
                    val result = searchResultsModel.getElementAt(idx)
                    openFileFromSearch(result.filePath)
                }
            }
        })

        // Build the main panel — no separate toolbar row; all controls live in the header row.
        val mainPanel = JPanel(BorderLayout())

        // Right-side action group: Show Full Path | Expand/Collapse | Hide Warnings | Open Diagnostics | Copy Path
        val actionGroup = DefaultActionGroup().apply {
            add(ToggleFullPathAction())
            addSeparator()
            add(ExpandCollapseAction())
            add(HideWarningsAction())
            addSeparator()
            add(OpenDiagnosticsAction())
            add(CopyPathAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("AzureTemplatesDependencyTree", actionGroup, true)
        toolbar.targetComponent = mainPanel

        // Header row: [icon + filename] LEFT  |  [toolbar buttons] RIGHT
        val fileHeaderPanel = JPanel(BorderLayout()).apply {
            border = CompoundBorder(
                MatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(2, 4)
            )
            add(fileHeaderLabel, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }

        // Top section: search bar on top, then file header below it
        val topPanel = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(fileHeaderPanel, BorderLayout.CENTER)
        }

        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(JBScrollPane(tree), BorderLayout.CENTER)

        component = mainPanel

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

    // ── Search logic ───────────────────────────────────────────────────

    /**
     * Called on every keystroke in the search field (debounced).
     * Queries the index via [FuzzySearch] and shows/hides the popup.
     */
    private fun onSearchChanged() {
        searchDebounceTimer?.cancel()
        searchDebounceTimer = Timer().also { timer ->
            timer.schedule(object : TimerTask() {
                override fun run() {
                    SwingUtilities.invokeLater { performSearch() }
                }
            }, 150) // 150ms debounce
        }
    }

    private fun performSearch() {
        val query = searchField.text.trim()
        if (query.isEmpty()) {
            hideSearchPopup()
            return
        }

        val basePath = project.basePath ?: return
        val indexService = TemplateIndexService.getInstance(project)
        val allFiles = indexService.getAllFiles()

        // Build candidates: absolute path → workspace-relative path
        val candidates = mutableMapOf<String, String>()
        for (absPath in allFiles) {
            val relPath = try {
                File(absPath).relativeTo(File(basePath)).path.replace("\\", "/")
            } catch (e: Exception) {
                File(absPath).name
            }
            candidates[absPath] = relPath
        }

        val results = FuzzySearch.search(query, candidates, maxResults = 15)

        searchResultsModel.clear()
        for (result in results) {
            searchResultsModel.addElement(result)
        }

        if (results.isNotEmpty()) {
            showSearchPopup()
            searchResultsList.selectedIndex = 0
        } else {
            hideSearchPopup()
        }
    }

    private fun showSearchPopup() {
        if (searchPopup?.isVisible == true) {
            // Just update the size
            updatePopupBounds()
            searchPopup?.revalidate()
            searchPopup?.repaint()
            return
        }

        val window = SwingUtilities.getWindowAncestor(searchField) ?: return
        val popup = JWindow(window)
        popup.type = java.awt.Window.Type.POPUP
        popup.focusableWindowState = false  // prevent the popup from stealing focus

        val scrollPane = JBScrollPane(searchResultsList).apply {
            border = MatteBorder(0, 1, 1, 1, JBColor.border())
            isFocusable = false
        }
        searchResultsList.isFocusable = false  // list itself should not grab focus
        popup.contentPane.add(scrollPane)

        searchPopup = popup
        updatePopupBounds()
        popup.isVisible = true
    }

    private fun updatePopupBounds() {
        val popup = searchPopup ?: return
        val locationOnScreen = try {
            searchField.locationOnScreen
        } catch (e: Exception) {
            return
        }
        val x = locationOnScreen.x
        val y = locationOnScreen.y + searchField.height
        val width = searchField.width
        val itemCount = searchResultsModel.size().coerceAtMost(10)
        val height = (itemCount * searchResultsList.fixedCellHeight).coerceAtLeast(28) + 4
        popup.setBounds(x, y, width, height)
    }

    private fun hideSearchPopup() {
        searchPopup?.isVisible = false
        searchPopup?.dispose()
        searchPopup = null
    }

    private fun selectTopSearchResult() {
        val idx = searchResultsList.selectedIndex
        if (idx >= 0 && idx < searchResultsModel.size()) {
            val result = searchResultsModel.getElementAt(idx)
            openFileFromSearch(result.filePath)
        }
    }

    private fun moveSearchSelection(delta: Int) {
        if (searchResultsModel.isEmpty) return
        val newIdx = (searchResultsList.selectedIndex + delta)
            .coerceIn(0, searchResultsModel.size() - 1)
        searchResultsList.selectedIndex = newIdx
        searchResultsList.ensureIndexIsVisible(newIdx)
    }

    /**
     * Opens a file from a search result, clears the search, and refreshes the tree.
     */
    private fun openFileFromSearch(filePath: String) {
        hideSearchPopup()
        searchField.text = ""
        openFile(filePath)
    }

    fun refresh(file: VirtualFile? = null) {
        val targetFile = file ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val filePath = targetFile.path
        val basePath = project.basePath ?: return

        root.removeAllChildren()
        currentFilePath = filePath

        // Ensure index is built — kick off background indexing if the index is empty,
        // then re-run refresh once it completes so the tree is populated.
        val indexService = TemplateIndexService.getInstance(project)
        if (indexService.getAllFiles().isEmpty()) {
            indexService.fullIndexAsync(onComplete = { refresh(file) })
            return
        }

        // Update the header panel to show the currently active file name + severity icon.
        val severity = indexService.getFileSeverity(filePath)
        updateFileHeaderLabel(filePath, targetFile.name, basePath)
        fileHeaderLabel.icon = when (severity) {
            IssueSeverity.ERROR -> AllIcons.General.Error
            IssueSeverity.WARNING -> AllIcons.General.Warning
            null -> AllIcons.FileTypes.Yaml
        }
        fileHeaderLabel.foreground = when (severity) {
            IssueSeverity.ERROR -> JBColor.RED
            IssueSeverity.WARNING -> JBColor(java.awt.Color(0xE6, 0xA0, 0x00), java.awt.Color(0xFF, 0xC6, 0x6D))
            null -> JBColor.foreground()
        }

        // Upstream callers — shown as a top-down tree: root pipeline → ... → direct caller
        // Each distinct root pipeline becomes a top-level child of "Called by".
        val callers = indexService.getUpstreamCallers(filePath)
        if (callers.isNotEmpty()) {
            // Collect all root-to-current paths (each path is ordered root-first, current-last).
            val allPaths = mutableListOf<List<String>>()
            collectUpstreamPaths(filePath, mutableListOf(), mutableSetOf(filePath), indexService, allPaths)

            val callerCountText = if (callers.size == 1) "1 caller" else "${callers.size} callers"
            val callersNode = DefaultMutableTreeNode(
                TreeNodeData("Called by", isGroup = true, description = callerCountText)
            )

            // Build a trie-like tree from the collected paths so shared prefixes are merged.
            buildUpstreamTree(callersNode, allPaths, basePath, indexService)

            root.add(callersNode)
        }

        // "Is calling" — downstream dependencies as a dedicated group node
        val text = try { File(filePath).readText() } catch (e: Exception) { return }
        val repoAliases = RepositoryAliasParser.parse(text)
        val refs = GraphBuilder.extractTemplateRefs(filePath)

        if (refs.isNotEmpty()) {
            val refCountText = if (refs.size == 1) "1 template" else "${refs.size} templates"
            val callingNode = DefaultMutableTreeNode(
                TreeNodeData("Is calling", isGroup = true, description = refCountText)
            )
            val visited = mutableSetOf<String>()
            addDownstreamNodes(callingNode, refs, filePath, repoAliases, basePath, visited, indexService)
            root.add(callingNode)
        }

        treeModel.reload()
        // Expand all nodes — loop until stable because expanding a row reveals new rows
        if (treeExpanded) {
            var prevRowCount = -1
            while (tree.rowCount != prevRowCount) {
                prevRowCount = tree.rowCount
                for (i in 0 until tree.rowCount) {
                    tree.expandRow(i)
                }
            }
        }
    }

    /**
     * DFS that collects every root-to-[current] path in the upstream caller graph.
     * [currentChain] accumulates callers in bottom-up order (direct caller first);
     * when a root is found the reversed chain (root-first) is appended to [results].
     * [visited] prevents cycles and is backtracked on each return.
     */
    private fun collectUpstreamPaths(
        current: String,
        currentChain: MutableList<String>,
        visited: MutableSet<String>,
        indexService: TemplateIndexService,
        results: MutableList<List<String>>
    ) {
        val callers = indexService.getUpstreamCallers(current)
        if (callers.isEmpty()) {
            // `current` is a root — record the full path root-first
            if (currentChain.isNotEmpty()) {
                results.add(currentChain.reversed())
            }
            return
        }
        for (caller in callers) {
            if (caller in visited) {
                // Cycle — treat this caller as a pseudo-root so the path is still recorded
                results.add((currentChain + caller).reversed())
                continue
            }
            visited.add(caller)
            currentChain.add(caller)
            collectUpstreamPaths(caller, currentChain, visited, indexService, results)
            currentChain.removeAt(currentChain.lastIndex)
            visited.remove(caller)
        }
    }

    /**
     * Builds a trie-like tree under [parentNode] from [paths], where each path is an ordered
     * list of file paths from root pipeline down to the direct caller of the current template.
     * Shared path prefixes are merged into a single branch.
     */
    private fun buildUpstreamTree(
        parentNode: DefaultMutableTreeNode,
        paths: List<List<String>>,
        basePath: String,
        indexService: TemplateIndexService
    ) {
        // Group paths by their first element (the root / next hop)
        val groups = LinkedHashMap<String, MutableList<List<String>>>()
        for (path in paths) {
            if (path.isEmpty()) continue
            groups.getOrPut(path.first()) { mutableListOf() }.add(path.drop(1))
        }

        for ((nodePath, subPaths) in groups) {
            val relativePath = try {
                File(nodePath).relativeTo(File(basePath)).path.replace("\\", "/")
            } catch (e: Exception) {
                File(nodePath).name
            }
            val node = DefaultMutableTreeNode(
                TreeNodeData(
                    label = File(nodePath).name,
                    fullPathLabel = relativePath,
                    filePath = nodePath,
                    icon = AllIcons.FileTypes.Yaml,
                    severity = indexService.getFileSeverity(nodePath)
                )
            )
            // Recurse for sub-paths that still have hops remaining
            val nonEmpty = subPaths.filter { it.isNotEmpty() }
            if (nonEmpty.isNotEmpty()) {
                buildUpstreamTree(node, nonEmpty, basePath, indexService)
            }
            parentNode.add(node)
        }
    }

    private fun addDownstreamNodes(
        parentNode: DefaultMutableTreeNode,
        refs: List<TemplateCallSite>,
        callerFile: String,
        repoAliases: Map<String, String>,
        basePath: String,
        visited: MutableSet<String>,
        indexService: TemplateIndexService? = null
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
                val cycleRelPath = try {
                    File(resolvedPath).relativeTo(File(basePath)).path.replace("\\", "/")
                } catch (e: Exception) {
                    File(resolvedPath).name
                }
                parentNode.add(DefaultMutableTreeNode(
                    TreeNodeData(
                        label = "${File(resolvedPath).name} (cycle)",
                        fullPathLabel = "$cycleRelPath (cycle)",
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
            val relativePath = try {
                File(resolvedPath).relativeTo(File(basePath)).path.replace("\\", "/")
            } catch (e: Exception) {
                File(resolvedPath).name
            }
            val fullPathLabel = if (resolved.repoName != null) {
                "$relativePath (@${resolved.alias})"
            } else {
                relativePath
            }

            val childNode = DefaultMutableTreeNode(
                TreeNodeData(
                    label = label,
                    fullPathLabel = fullPathLabel,
                    filePath = resolvedPath,
                    icon = icon,
                    severity = indexService?.getFileSeverity(resolvedPath)
                )
            )

            // Recursively add children (with cycle detection)
            visited.add(resolvedPath)
            val childText = try { File(resolvedPath).readText() } catch (e: Exception) { "" }
            val childAliases = RepositoryAliasParser.parse(childText)
            val childRefs = GraphBuilder.extractTemplateRefs(resolvedPath)
            if (childRefs.isNotEmpty()) {
                addDownstreamNodes(childNode, childRefs, resolvedPath, childAliases, basePath, visited, indexService)
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

    /**
     * Shows a right-click context menu for the tree node under the cursor.
     * "Open in Diagnostics" is only enabled when the node's file has at least one issue.
     */
    private fun showTreeContextMenu(e: MouseEvent) {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? TreeNodeData ?: return
        val filePath = data.filePath ?: return  // group nodes have no filePath — skip

        val indexService = TemplateIndexService.getInstance(project)
        val hasDiagnostics = indexService.getFileSeverity(filePath) != null

        val menu = JPopupMenu()
        val diagItem = JMenuItem("Open in Diagnostics", AllIcons.General.InspectionsEye).apply {
            isEnabled = hasDiagnostics
            addActionListener {
                val twManager = ToolWindowManager.getInstance(project)
                val tw = twManager.getToolWindow("Azure Templates - Diagnostics") ?: return@addActionListener
                tw.activate {
                    val content = tw.contentManager.getContent(0) ?: return@activate
                    val panel = content.getUserData(DiagnosticsToolWindow.PANEL_KEY) ?: return@activate
                    panel.selectFile(filePath)
                }
            }
        }
        menu.add(diagItem)
        menu.show(tree, e.x, e.y)
    }

    /**
     * Updates the header label text to either the filename or the workspace-relative path,
     * depending on the current [showFullPath] state.
     */
    private fun updateFileHeaderLabel(filePath: String, fileName: String, basePath: String) {
        fileHeaderLabel.text = if (showFullPath) {
            try {
                File(filePath).relativeTo(File(basePath)).path.replace("\\", "/")
            } catch (e: Exception) {
                fileName
            }
        } else {
            fileName
        }
    }

    /**
     * Toggle action for showing full paths in the dependency tree.
     */
    private inner class ToggleFullPathAction : ToggleAction(
        "Show Full Path",
        "Toggle between filename and full workspace-relative path labels",
        AllIcons.Actions.ShowAsTree
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = showFullPath

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showFullPath = state
            tree.repaint()
            // Also update the header label for the currently open file
            val fp = currentFilePath ?: return
            val bp = project.basePath ?: return
            updateFileHeaderLabel(fp, File(fp).name, bp)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * Toggles between fully expanded and fully collapsed tree.
     */
    private inner class ExpandCollapseAction : ToggleAction(
        "Expand / Collapse All",
        "Toggle between fully expanded and fully collapsed tree",
        AllIcons.Actions.Expandall
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = treeExpanded

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            treeExpanded = state
            if (state) {
                var prevRowCount = -1
                while (tree.rowCount != prevRowCount) {
                    prevRowCount = tree.rowCount
                    for (i in 0 until tree.rowCount) tree.expandRow(i)
                }
            } else {
                for (i in tree.rowCount - 1 downTo 0) tree.collapseRow(i)
            }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * When active, warning-severity nodes are rendered as plain (no amber colour, no ⚠ suffix).
     * Only errors remain highlighted, making it easier to focus on critical issues.
     */
    private inner class HideWarningsAction : ToggleAction(
        "Errors Only",
        "Hide warning highlights — only errors are shown in colour",
        AllIcons.General.Warning
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = hideWarnings

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            hideWarnings = state
            tree.repaint()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * Opens the Diagnostics tool window, then selects and expands the node for the current file.
     * Only enabled when the current file has at least one diagnostic issue.
     */
    private inner class OpenDiagnosticsAction : AnAction(
        "Open in Diagnostics",
        "Jump to this file's entry in the Diagnostics panel",
        AllIcons.General.InspectionsEye
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val fp = currentFilePath ?: return
            val twManager = ToolWindowManager.getInstance(project)
            val tw = twManager.getToolWindow("Azure Templates - Diagnostics") ?: return
            tw.activate {
                // After the tool window is shown, find the DiagnosticsPanel and select the file node.
                val content = tw.contentManager.getContent(0) ?: return@activate
                val panel = content.getUserData(DiagnosticsToolWindow.PANEL_KEY) ?: return@activate
                panel.selectFile(fp)
            }
        }

        override fun update(e: AnActionEvent) {
            val fp = currentFilePath
            val indexService = TemplateIndexService.getInstance(project)
            // Only enable when the current file actually has diagnostics issues
            e.presentation.isEnabled = fp != null && indexService.getFileSeverity(fp) != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * Copies the workspace-relative path of the currently open file to the clipboard.
     * Briefly shows a checkmark icon to confirm the copy succeeded.
     */
    private inner class CopyPathAction : AnAction(
        "Copy Path",
        "Copy the workspace-relative path of the current file to the clipboard",
        AllIcons.Actions.Copy
    ) {
        private var resetTimer: Timer? = null

        override fun actionPerformed(e: AnActionEvent) {
            val fp = currentFilePath ?: return
            val basePath = project.basePath ?: return
            val relativePath = try {
                File(fp).relativeTo(File(basePath)).path.replace("\\", "/")
            } catch (ex: Exception) {
                File(fp).name
            }
            val selection = StringSelection(relativePath)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)

            // Show checkmark feedback for 1.5 s, then restore the original icon.
            e.presentation.icon = AllIcons.Actions.Checked
            resetTimer?.cancel()
            resetTimer = Timer().also { timer ->
                timer.schedule(object : TimerTask() {
                    override fun run() {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            e.presentation.icon = AllIcons.Actions.Copy
                        }
                    }
                }, 500)
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentFilePath != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

}

/**
 * Custom tree cell renderer for the dependency tree.
 * Uses [showFullPathProvider] to determine whether to display full paths.
 */
class TemplateTreeCellRenderer(
    private val showFullPathProvider: () -> Boolean = { false },
    private val hideWarningsProvider: () -> Boolean = { false }
) : ColoredTreeCellRenderer() {
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
        icon = data.icon  // null means no icon (e.g. group header nodes)
        val displayLabel = if (showFullPathProvider() && data.fullPathLabel != null) {
            data.fullPathLabel
        } else {
            data.label
        }
        val effectiveSeverity = if (hideWarningsProvider() && data.severity == IssueSeverity.WARNING) null else data.severity
        val labelAttrs = when (effectiveSeverity) {
            IssueSeverity.ERROR -> SimpleTextAttributes.ERROR_ATTRIBUTES
            IssueSeverity.WARNING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color(0xE6, 0xA0, 0x00))
            null -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(displayLabel, labelAttrs)
        if (data.description != null) {
            append("  ${data.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

/**
 * Cell renderer for the fuzzy search results popup list.
 * Shows the file icon, filename (bold), and the full relative path (grey) beneath.
 */
class SearchResultCellRenderer : ListCellRenderer<FuzzySearch.SearchResult> {
    override fun getListCellRendererComponent(
        list: JList<out FuzzySearch.SearchResult>,
        value: FuzzySearch.SearchResult?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 6)
            isOpaque = true
            background = if (isSelected) {
                list.selectionBackground
            } else {
                list.background
            }
        }

        if (value == null) return panel

        val fileName = File(value.relativePath).name
        val dirPath = File(value.relativePath).parent?.replace("\\", "/") ?: ""

        val label = JLabel().apply {
            icon = AllIcons.FileTypes.Yaml
            text = buildString {
                append("<html><b>")
                append(escapeHtml(fileName))
                append("</b>")
                if (dirPath.isNotEmpty()) {
                    append("  <font color='#888888'>")
                    append(escapeHtml(dirPath))
                    append("</font>")
                }
                append("</html>")
            }
            foreground = if (isSelected) list.selectionForeground else list.foreground
        }

        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.TemplateParameter
import com.bogdanbujor.azuretemplates.settings.PluginSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * A Swing-based popup that shows template parameters with a live search field.
 *
 * Features:
 * - Live case-insensitive search via SearchTextField
 * - Arrow Up/Down to navigate the list from the search field
 * - Enter or double-click to jump to the parameter declaration in the template file
 * - Proper HTML rendering (bold names, colored required params, code spans)
 */
object TemplateParameterPopup {

    fun show(
        editor: Editor,
        templateRef: String,
        params: List<TemplateParameter>,
        repoName: String?,
        resolvedFilePath: String,
        locationOnScreen: Point
    ) {
        val settings = PluginSettings.getInstance()
        val requiredColor = parseColor(settings.requiredParameterColor) ?: Color(0xE06C75)
        val project = editor.project ?: return

        val panel = buildPanel(templateRef, params, repoName, requiredColor, resolvedFilePath, project)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel.getClientProperty("focusTarget") as? JComponent)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .setTitle(null)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(true)
            .createPopup()

        popup.showInScreenCoordinates(editor.component, locationOnScreen)
    }

    private fun buildPanel(
        templateRef: String,
        params: List<TemplateParameter>,
        repoName: String?,
        requiredColor: Color,
        resolvedFilePath: String,
        project: Project
    ): JPanel {
        val root = JPanel(BorderLayout(0, 0))
        root.border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        root.preferredSize = Dimension(560, if (params.size > 8) 440 else minOf(140 + params.size * 26 + 60, 440))

        // ── Header ────────────────────────────────────────────────────────────
        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
        headerPanel.isOpaque = false

        headerPanel.add(htmlLabel("<html><b>Template:</b> <code>${escapeHtml(templateRef.trim())}</code></html>", 0, 0, 4, 0))
        if (repoName != null) {
            headerPanel.add(htmlLabel("<html><b>External repository:</b> <code>${escapeHtml(repoName)}</code></html>", 0, 0, 4, 0))
        }
        root.add(headerPanel, BorderLayout.NORTH)

        // ── No params ─────────────────────────────────────────────────────────
        if (params.isEmpty()) {
            val noParams = htmlLabel("<html><i>No parameters defined</i></html>", 6, 0, 0, 0)
            root.add(noParams, BorderLayout.CENTER)
            return root
        }

        // ── Body ──────────────────────────────────────────────────────────────
        val bodyPanel = JPanel(BorderLayout(0, 4))
        bodyPanel.isOpaque = false
        bodyPanel.border = BorderFactory.createEmptyBorder(4, 0, 0, 0)

        bodyPanel.add(htmlLabel("<html><b>Parameters:</b></html>", 0, 0, 4, 0), BorderLayout.NORTH)

        // ── Search field ──────────────────────────────────────────────────────
        val searchField = SearchTextField(false)
        searchField.textEditor.emptyText.text = "Search parameters…"

        // ── Parameter list ────────────────────────────────────────────────────
        val listModel = DefaultListModel<TemplateParameter>()
        params.forEach { listModel.addElement(it) }

        val paramList = JList(listModel)
        paramList.cellRenderer = ParameterCellRenderer(requiredColor)
        paramList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        paramList.visibleRowCount = -1
        paramList.fixedCellHeight = -1

        val scrollPane = JBScrollPane(paramList)
        scrollPane.border = BorderFactory.createEmptyBorder()

        // ── No-match label ────────────────────────────────────────────────────
        val noMatchLabel = htmlLabel("<html><i>No matching parameters</i></html>", 4, 2, 0, 0)
        noMatchLabel.foreground = Color.GRAY
        noMatchLabel.isVisible = false

        // ── Navigate to parameter declaration ─────────────────────────────────
        fun navigateToSelected() {
            val selected = paramList.selectedValue ?: return
            val vf = LocalFileSystem.getInstance().findFileByPath(resolvedFilePath) ?: return
            val descriptor = OpenFileDescriptor(project, vf, selected.line, 0)
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }

        // ── Search filter ─────────────────────────────────────────────────────
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()

            fun filter() {
                val query = searchField.text.trim().lowercase()
                listModel.clear()
                val filtered = if (query.isEmpty()) params
                else params.filter { it.name.lowercase().contains(query) }
                filtered.forEach { listModel.addElement(it) }
                noMatchLabel.isVisible = filtered.isEmpty()
                scrollPane.isVisible = filtered.isNotEmpty()
                if (filtered.isNotEmpty()) paramList.selectedIndex = 0
            }
        })

        // ── Keyboard: arrow keys from search field move list selection ─────────
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        val next = (paramList.selectedIndex + 1).coerceAtMost(listModel.size - 1)
                        paramList.selectedIndex = next
                        paramList.ensureIndexIsVisible(next)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        val prev = (paramList.selectedIndex - 1).coerceAtLeast(0)
                        paramList.selectedIndex = prev
                        paramList.ensureIndexIsVisible(prev)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        navigateToSelected()
                        e.consume()
                    }
                }
            }
        })

        // ── Keyboard: Enter from list ──────────────────────────────────────────
        paramList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    navigateToSelected()
                    e.consume()
                }
            }
        })

        // ── Double-click on list item ──────────────────────────────────────────
        paramList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val idx = paramList.locationToIndex(e.point)
                    if (idx >= 0) {
                        paramList.selectedIndex = idx
                        navigateToSelected()
                    }
                }
            }
        })

        val centerPanel = JPanel(BorderLayout(0, 4))
        centerPanel.isOpaque = false
        centerPanel.add(searchField, BorderLayout.NORTH)
        centerPanel.add(scrollPane, BorderLayout.CENTER)
        centerPanel.add(noMatchLabel, BorderLayout.SOUTH)

        bodyPanel.add(centerPanel, BorderLayout.CENTER)
        root.add(bodyPanel, BorderLayout.CENTER)

        root.putClientProperty("focusTarget", searchField)
        return root
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun htmlLabel(html: String, top: Int, left: Int, bottom: Int, right: Int): JLabel {
        // Use a plain JLabel — IntelliJ's JBLabel strips some HTML; plain JLabel renders it fine
        val label = JLabel(html)
        label.border = BorderFactory.createEmptyBorder(top, left, bottom, right)
        return label
    }

    private fun parseColor(hex: String): Color? = try {
        Color(hex.trimStart('#').toInt(16))
    } catch (_: Exception) { null }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ── Cell renderer ──────────────────────────────────────────────────────────
    private class ParameterCellRenderer(private val requiredColor: Color) : ListCellRenderer<TemplateParameter> {

        // Reuse a single JEditorPane for rendering — it properly handles <b>, <i>, <code>
        private val pane = JEditorPane("text/html", "").apply {
            isOpaque = false
            isEditable = false
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            // Match the list's font so sizes are consistent
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
        private val selectedPane = JEditorPane("text/html", "").apply {
            isOpaque = true
            isEditable = false
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }

        override fun getListCellRendererComponent(
            list: JList<out TemplateParameter>,
            value: TemplateParameter,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val nameHtml = if (value.required) {
                "<b><font color='${colorToHex(requiredColor)}'>${escapeHtml(value.name)}</font></b>"
            } else {
                "<b>${escapeHtml(value.name)}</b>"
            }
            val badge = if (value.required) " <i>(required)</i>" else ""
            val defaultPart = if (value.default != null) " &mdash; default: <code>${escapeHtml(value.default)}</code>" else ""
            val html = "<html><body style='font-family:${list.font.family};font-size:${list.font.size}pt;'>$nameHtml: <code>${escapeHtml(value.type)}</code>$defaultPart$badge</body></html>"

            return if (isSelected) {
                selectedPane.font = list.font
                selectedPane.background = list.selectionBackground
                selectedPane.foreground = list.selectionForeground
                selectedPane.text = html
                selectedPane
            } else {
                pane.font = list.font
                pane.foreground = list.foreground
                pane.text = html
                pane
            }
        }

        private fun colorToHex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
        private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

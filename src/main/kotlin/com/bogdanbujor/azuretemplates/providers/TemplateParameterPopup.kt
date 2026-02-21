package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.TemplateParameter
import com.bogdanbujor.azuretemplates.settings.PluginSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
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
 * Design goals (inspired by VS Code hover):
 * - High-contrast dark-friendly colors for type, default, required
 * - "Open" / "Open to side" action links in the header
 * - Parameter format: •  name: type (required)  —  default: 'value'
 * - Live case-insensitive search via SearchTextField
 * - Arrow Up/Down to navigate the list from the search field
 * - Enter or double-click to jump to the parameter declaration
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
        root.border = JBUI.Borders.empty(10, 12, 10, 12)
        root.preferredSize = Dimension(580, if (params.size > 8) 460 else minOf(160 + params.size * 28 + 60, 460))

        // ── Header ────────────────────────────────────────────────────────────
        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
        headerPanel.isOpaque = false

        // Template path line
        val templateLabel = htmlLabel(
            "<html><b>Template:</b>&nbsp;<code>${escapeHtml(templateRef.trim())}</code></html>",
            top = 0, left = 0, bottom = 2, right = 0
        )
        headerPanel.add(templateLabel)

        // External repo line (if applicable)
        if (repoName != null) {
            val repoLabel = htmlLabel(
                "<html><b>Repository:</b>&nbsp;<code>${escapeHtml(repoName)}</code></html>",
                top = 0, left = 0, bottom = 2, right = 0
            )
            headerPanel.add(repoLabel)
        }

        // "Open · Open to side" action links
        val openLinksPanel = buildOpenLinksPanel(resolvedFilePath, project)
        openLinksPanel.alignmentX = Component.LEFT_ALIGNMENT
        headerPanel.add(Box.createVerticalStrut(4))
        headerPanel.add(openLinksPanel)
        headerPanel.add(Box.createVerticalStrut(6))

        // Thin separator line
        val separator = JSeparator(SwingConstants.HORIZONTAL)
        separator.maximumSize = Dimension(Int.MAX_VALUE, 1)
        separator.alignmentX = Component.LEFT_ALIGNMENT
        headerPanel.add(separator)
        headerPanel.add(Box.createVerticalStrut(6))

        root.add(headerPanel, BorderLayout.NORTH)

        // ── No params ─────────────────────────────────────────────────────────
        if (params.isEmpty()) {
            val noParams = htmlLabel("<html><i>No parameters defined</i></html>", 6, 0, 0, 0)
            noParams.foreground = UIUtil.getContextHelpForeground()
            root.add(noParams, BorderLayout.CENTER)
            return root
        }

        // ── Body ──────────────────────────────────────────────────────────────
        val bodyPanel = JPanel(BorderLayout(0, 6))
        bodyPanel.isOpaque = false

        val paramsLabel = htmlLabel("<html><b>Parameters:</b></html>", 0, 0, 4, 0)
        bodyPanel.add(paramsLabel, BorderLayout.NORTH)

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
        noMatchLabel.foreground = UIUtil.getContextHelpForeground()
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

    /**
     * Builds the "Open · Open to side" action link row shown below the template path.
     */
    private fun buildOpenLinksPanel(resolvedFilePath: String, project: Project): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        panel.isOpaque = false

        val linkColor = JBColor(Color(0x4A9EDB), Color(0x4FC1FF))

        fun makeLink(text: String, action: () -> Unit): JLabel {
            val label = JLabel("<html><a style='color:${colorToHex(linkColor)};text-decoration:none;'>${escapeHtml(text)}</a></html>")
            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            label.border = JBUI.Borders.empty(0, 0, 0, 0)
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = action()
                override fun mouseEntered(e: MouseEvent) {
                    label.text = "<html><a style='color:${colorToHex(linkColor)};text-decoration:underline;'>${escapeHtml(text)}</a></html>"
                }
                override fun mouseExited(e: MouseEvent) {
                    label.text = "<html><a style='color:${colorToHex(linkColor)};text-decoration:none;'>${escapeHtml(text)}</a></html>"
                }
            })
            return label
        }

        val openLink = makeLink("⎆ Open") {
            val vf = LocalFileSystem.getInstance().findFileByPath(resolvedFilePath) ?: return@makeLink
            FileEditorManager.getInstance(project).openFile(vf, true)
        }

        panel.add(openLink)
        return panel
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun htmlLabel(html: String, top: Int, left: Int, bottom: Int, right: Int): JLabel {
        val label = JLabel(html)
        label.border = JBUI.Borders.empty(top, left, bottom, right)
        return label
    }

    private fun parseColor(hex: String): Color? = try {
        Color(hex.trimStart('#').toInt(16))
    } catch (_: Exception) { null }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colorToHex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    // ── Cell renderer ──────────────────────────────────────────────────────────
    private class ParameterCellRenderer(private val requiredColor: Color) : ListCellRenderer<TemplateParameter> {

        // Theme-aware colors
        private val typeColor   = JBColor(Color(0x0070C1), Color(0x4FC1FF))   // blue  — light / dark
        private val defaultColor = JBColor(Color(0xA31515), Color(0xCE9178))  // rust  — light / dark
        private val dashColor   = JBColor(Color(0x555555), Color(0x888888))   // muted dash

        // Reuse a single JEditorPane for rendering
        private val pane = JEditorPane("text/html", "").apply {
            isOpaque = false
            isEditable = false
            border = JBUI.Borders.empty(3, 8, 3, 8)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
        private val selectedPane = JEditorPane("text/html", "").apply {
            isOpaque = true
            isEditable = false
            border = JBUI.Borders.empty(3, 8, 3, 8)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }

        override fun getListCellRendererComponent(
            list: JList<out TemplateParameter>,
            value: TemplateParameter,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val tc  = colorToHex(typeColor)
            val dc  = colorToHex(defaultColor)
            val dsh = colorToHex(dashColor)
            val rc  = colorToHex(requiredColor)

            // Parameter name — required ones in red/coral, optional in default foreground
            val nameHtml = if (value.required) {
                "<b><font color='$rc'>${escapeHtml(value.name)}</font></b>"
            } else {
                "<b>${escapeHtml(value.name)}</b>"
            }

            // Type token in blue
            val typeHtml = "<font color='$tc'>${escapeHtml(value.type)}</font>"

            // default: 'value' — in orange/rust, only if present
            val defaultHtml = if (value.default != null) {
                "&nbsp;<font color='$dsh'>&mdash;</font>&nbsp;default:&nbsp;<font color='$dc'>${escapeHtml(value.default)}</font>"
            } else ""

            // (required) badge — italic, same red as name, at the end (after default)
            val requiredBadge = if (value.required) "&nbsp;<i><font color='$rc'>(required)</font></i>" else ""

            val fontFamily = list.font.family
            val fontSize   = list.font.size

            // Layout: •  name: type  —  default: value (required)
            val html = """
                <html><body style='font-family:$fontFamily;font-size:${fontSize}pt;white-space:nowrap;'>
                  <span style='color:$dsh;'>&#8226;</span>&nbsp;$nameHtml:<span>&nbsp;</span>$typeHtml$defaultHtml$requiredBadge
                </body></html>
            """.trimIndent()

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

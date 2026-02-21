package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Mouse motion listener attached to each editor that detects when the cursor
 * hovers over a template reference line and shows a searchable parameter popup.
 *
 * A 600 ms debounce timer prevents the popup from firing on every pixel of movement.
 * The popup is dismissed automatically when the mouse moves away from the trigger line.
 */
class TemplateHoverMouseListener(private val editor: Editor) : EditorMouseMotionListener {

    private var debounceTimer: Timer? = null
    private var lastHoveredLine: Int = -1
    private var currentPopupLine: Int = -1

    override fun mouseMoved(e: EditorMouseEvent) {
        val mouseEvent: MouseEvent = e.mouseEvent
        val logicalPos = editor.xyToLogicalPosition(mouseEvent.point)
        val line = logicalPos.line

        // If we moved to a different line, cancel any pending popup for the old line
        if (line != lastHoveredLine) {
            debounceTimer?.stop()
            lastHoveredLine = line
        }

        // Don't re-trigger if the popup for this line is already showing
        if (line == currentPopupLine) return

        val document = editor.document
        if (line < 0 || line >= document.lineCount) return

        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))

        // Quick pre-check before doing any real work
        if (!lineText.contains("template")) return

        val templateRef = TemplateResolver.extractTemplateRef(lineText) ?: return

        // Debounce: wait 600 ms of stillness before showing the popup
        debounceTimer?.stop()
        val screenPoint = mouseEvent.locationOnScreen.let { Point(it.x + 16, it.y + 16) }

        debounceTimer = Timer(600) {
            showPopup(templateRef, line, screenPoint)
        }.also {
            it.isRepeats = false
            it.start()
        }
    }

    private fun showPopup(templateRef: String, line: Int, screenPoint: Point) {
        // Run file I/O off the EDT, then show popup on EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val psiFile = editor.project?.let { project ->
                com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getPsiFile(editor.document)
            } ?: return@executeOnPooledThread

            val filePath = psiFile.virtualFile?.path ?: return@executeOnPooledThread
            val docText = editor.document.text

            val repoAliases = RepositoryAliasParser.parse(docText)
            val resolved = TemplateResolver.resolve(templateRef, filePath, repoAliases)
                ?: return@executeOnPooledThread

            if (resolved.unknownAlias || resolved.filePath == null) return@executeOnPooledThread

            val templateText = try {
                val vf = LocalFileSystem.getInstance().findFileByPath(resolved.filePath)
                    ?: return@executeOnPooledThread
                String(vf.contentsToByteArray(), vf.charset)
            } catch (_: Exception) {
                return@executeOnPooledThread
            }

            val params = ParameterParser.parse(templateText)

            SwingUtilities.invokeLater {
                // Only show if the user is still hovering the same line
                if (lastHoveredLine != line) return@invokeLater

                currentPopupLine = line
                TemplateParameterPopup.show(
                    editor = editor,
                    templateRef = templateRef,
                    params = params,
                    repoName = resolved.repoName,
                    resolvedFilePath = resolved.filePath,
                    locationOnScreen = screenPoint
                )
                // Reset after popup is shown so it can be re-triggered
                currentPopupLine = -1
            }
        }
    }
}

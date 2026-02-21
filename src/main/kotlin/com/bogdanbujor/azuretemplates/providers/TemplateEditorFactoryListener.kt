package com.bogdanbujor.azuretemplates.providers

import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Listens for editor creation events and attaches a [TemplateHoverMouseListener]
 * to every YAML editor so that hovering over template references shows the
 * searchable parameter popup.
 */
class TemplateEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        // Only attach to YAML files
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (vf.extension?.lowercase() != "yml" && vf.extension?.lowercase() != "yaml") return

        editor.addEditorMouseMotionListener(TemplateHoverMouseListener(editor))
    }
}

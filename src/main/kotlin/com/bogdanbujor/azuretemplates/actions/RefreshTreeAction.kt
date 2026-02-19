package com.bogdanbujor.azuretemplates.actions

import com.bogdanbujor.azuretemplates.services.TemplateIndexService
import com.bogdanbujor.azuretemplates.ui.DependencyTreePanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Action to refresh the template dependency tree.
 */
class RefreshTreeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Re-index
        TemplateIndexService.getInstance(project).fullIndex()
        // Refresh the tool window
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Azure Templates - Dependencies")
        val content = toolWindow?.contentManager?.getContent(0)
        val component = content?.component
        // The panel refresh is handled by the editor listener
    }
}

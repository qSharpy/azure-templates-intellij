package com.bogdanbujor.azuretemplates.actions

import com.bogdanbujor.azuretemplates.ui.DiagnosticsToolWindow
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Action to refresh the diagnostics panel.
 */
class RefreshDiagnosticsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Azure Templates - Diagnostics") ?: return
        val content = toolWindow.contentManager.getContent(0) ?: return
        val panel = content.getUserData(DiagnosticsToolWindow.PANEL_KEY) ?: return
        panel.refresh()
    }
}

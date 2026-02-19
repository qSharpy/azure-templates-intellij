package com.bogdanbujor.azuretemplates.actions

import com.bogdanbujor.azuretemplates.ui.DiagnosticsPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Action to refresh the diagnostics panel.
 */
class RefreshDiagnosticsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Diagnostics refresh is handled by the DiagnosticsPanel
    }
}

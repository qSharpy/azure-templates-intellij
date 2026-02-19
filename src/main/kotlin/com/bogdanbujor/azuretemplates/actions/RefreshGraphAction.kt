package com.bogdanbujor.azuretemplates.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to refresh the template graph.
 */
class RefreshGraphAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Graph refresh is handled by the GraphPanel
    }
}

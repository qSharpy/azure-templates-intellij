package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.RepositoryAliasParser
import com.bogdanbujor.azuretemplates.core.TemplateResolver
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement

/**
 * Line marker provider that shows gutter icons on "- template:" lines
 * for quick navigation. This is an IntelliJ-specific enhancement not
 * present in the VS Code version.
 */
class TemplateLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only process leaf elements to avoid duplicate markers
        if (element.firstChild != null) return null

        val text = element.text ?: return null
        // Quick check: does this element contain "template"?
        if (!text.contains("template")) return null

        val psiFile = element.containingFile ?: return null
        val document = psiFile.viewProvider.document ?: return null
        val lineNumber = document.getLineNumber(element.textOffset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))

        val templateRef = TemplateResolver.extractTemplateRef(lineText) ?: return null

        val docText = document.text
        val currentFilePath = psiFile.virtualFile?.path ?: return null
        val repoAliases = RepositoryAliasParser.parse(docText)
        val resolved = TemplateResolver.resolve(templateRef, currentFilePath, repoAliases) ?: return null

        if (resolved.unknownAlias || resolved.filePath == null) return null
        // Use VFS to check existence — avoids blocking disk I/O on the EDT
        val targetVirtualFile = LocalFileSystem.getInstance().findFileByPath(resolved.filePath)
            ?: return null
        if (!targetVirtualFile.isValid) return null

        val icon = if (resolved.repoName != null) AllIcons.Nodes.PpLib else AllIcons.FileTypes.Yaml
        val tooltip = if (resolved.repoName != null) {
            "Navigate to template: $templateRef (${resolved.repoName})"
        } else {
            "Navigate to template: $templateRef"
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            { _, elt ->
                val project = elt.project
                val descriptor = OpenFileDescriptor(project, targetVirtualFile, 0, 0)
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            },
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }
}

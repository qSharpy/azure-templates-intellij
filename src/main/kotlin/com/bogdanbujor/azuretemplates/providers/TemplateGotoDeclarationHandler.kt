package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.*
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.io.File

/**
 * Go-to-declaration handler for YAML template references.
 *
 * Port of definitionProvider.provideDefinition() from the VS Code extension.
 *
 * Behavior:
 * 1. Cmd+Click on a "template:" line -> open the template file at line 0
 * 2. Cmd+Click on a parameter key inside a "parameters:" block -> open the template
 *    file and jump to the matching "- name:" line
 */
class TemplateGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        val psiFile = sourceElement.containingFile ?: return null
        val document = editor.document
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
        val docText = document.text
        val lines = docText.replace("\r\n", "\n").split("\n")
        val currentFilePath = psiFile.virtualFile?.path ?: return null

        // 1. Template line go-to-definition
        val templateRef = TemplateResolver.extractTemplateRef(lineText)
        if (templateRef != null) {
            return resolveTemplateTarget(templateRef, docText, currentFilePath, 0, psiFile)
        }

        // 2. Parameter key go-to-definition
        val strippedLine = lineText.replace(Regex("(^\\s*#.*|\\s#.*)$"), "")
        val paramKeyMatch = Regex("^(\\s+)([\\w-]+)\\s*:").find(strippedLine) ?: return null

        val keyStart = paramKeyMatch.groupValues[1].length
        val keyEnd = keyStart + paramKeyMatch.groupValues[2].length
        val charInLine = offset - lineStart
        if (charInLine > keyEnd) return null

        val paramName = paramKeyMatch.groupValues[2]

        // Walk upward to find the owning "- template:" line
        val templateLineIdx = TemplateResolver.findOwningTemplateLine(lines, lineNumber)
        if (templateLineIdx == -1) return null

        // Verify the cursor is inside the "parameters:" sub-block
        val templateIndent = lines[templateLineIdx].length - lines[templateLineIdx].trimStart().length
        var foundParamsBlock = false
        for (i in (templateLineIdx + 1)..lineNumber) {
            val t = lines[i].trimEnd()
            val s = t.trimStart()
            if (s.isEmpty()) continue
            val ind = t.length - s.length
            if (ind > templateIndent && Regex("^parameters\\s*:").containsMatchIn(s)) {
                foundParamsBlock = true
                break
            }
            if (ind <= templateIndent) break
        }
        if (!foundParamsBlock) return null

        // Resolve the template file
        val templateLineText = lines[templateLineIdx]
        val templateRefFromLine = TemplateResolver.extractTemplateRef(templateLineText) ?: return null

        val repoAliases = RepositoryAliasParser.parse(docText)
        val resolved = TemplateResolver.resolve(templateRefFromLine, currentFilePath, repoAliases)
        if (resolved == null || resolved.unknownAlias || resolved.filePath == null) return null

        val filePath = resolved.filePath
        if (!File(filePath).exists()) return null

        // Parse the template's parameters to find the target line
        val templateText = try { File(filePath).readText() } catch (e: Exception) { return null }
        val templateParams = ParameterParser.parse(templateText)
        val paramDef = templateParams.find { it.name == paramName } ?: return null

        return resolveTemplateTarget(templateRefFromLine, docText, currentFilePath, paramDef.line, psiFile)
    }

    /**
     * Resolves a template reference to a PsiElement target at the given line.
     *
     * Returns a PsiElement at the target line offset so IntelliJ handles navigation
     * automatically. Does NOT call openTextEditor() — that would require the EDT
     * and getGotoDeclarationTargets() runs on a background read-action thread.
     */
    private fun resolveTemplateTarget(
        templateRef: String,
        docText: String,
        currentFilePath: String,
        targetLine: Int,
        contextFile: PsiElement
    ): Array<PsiElement>? {
        val repoAliases = RepositoryAliasParser.parse(docText)
        val resolved = TemplateResolver.resolve(templateRef, currentFilePath, repoAliases)
        if (resolved == null || resolved.unknownAlias || resolved.filePath == null) return null

        val filePath = resolved.filePath
        if (!File(filePath).exists()) return null

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val project = contextFile.project
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null

        // Find the PsiElement at the target line so IntelliJ navigates there automatically.
        // If targetLine is 0, return the file itself (navigates to top of file).
        if (targetLine > 0) {
            val targetDocument = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile)
            if (targetDocument != null && targetLine < targetDocument.lineCount) {
                val targetOffset = targetDocument.getLineStartOffset(targetLine)
                val elementAtTarget = psiFile.findElementAt(targetOffset)
                if (elementAtTarget != null) {
                    return arrayOf(elementAtTarget)
                }
            }
        }

        return arrayOf(psiFile)
    }

    override fun getActionText(context: DataContext): String? {
        return "Go to Template"
    }
}

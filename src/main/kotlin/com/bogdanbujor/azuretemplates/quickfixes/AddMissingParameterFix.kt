package com.bogdanbujor.azuretemplates.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * Quick-fix: "Add missing parameter 'paramName'"
 *
 * Inserts a new `paramName: ` entry at the end of the existing `parameters:` block
 * (or creates the block if it does not exist yet), then places the caret right after
 * the colon so the user can type the value immediately.
 *
 * @param paramName  The name of the missing parameter to insert.
 * @param paramType  The declared type of the parameter (used in the placeholder comment).
 * @param insertAfterLine  0-based line index after which the new line is inserted.
 *                         Pass -1 when no `parameters:` block exists yet.
 * @param templateLine  0-based line index of the `- template:` line (used to derive
 *                      indentation when the block must be created from scratch).
 */
class AddMissingParameterFix(
    private val paramName: String,
    private val paramType: String,
    private val insertAfterLine: Int,
    private val templateLine: Int
) : LocalQuickFix {

    override fun getFamilyName(): String = "Azure Templates Navigator"

    override fun getName(): String = "Add missing parameter '$paramName'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiFile = descriptor.psiElement?.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        // Commit any pending PSI changes so line offsets are accurate.
        PsiDocumentManager.getInstance(project).commitDocument(document)

        val docText = document.text
        val lines = docText.replace("\r\n", "\n").split("\n")

        // ── Determine indentation ─────────────────────────────────────────────
        // The template line looks like "    - template: foo.yml".
        // The parameters block is indented one level deeper than the template line.
        // Individual parameter entries are indented one level deeper than "parameters:".
        val templateRaw = if (templateLine < lines.size) lines[templateLine] else ""
        val templateIndent = templateRaw.length - templateRaw.trimStart().length
        // Use 2-space indent steps (standard Azure Pipelines YAML style).
        val paramsBlockIndent = " ".repeat(templateIndent + 2)
        val entryIndent = " ".repeat(templateIndent + 4)

        val newEntry = "$entryIndent$paramName: "

        val insertOffset: Int
        val textToInsert: String

        if (insertAfterLine < 0) {
            // No `parameters:` block exists — append it right after the template line.
            val afterTemplateLine = if (templateLine < lines.size) templateLine else lines.size - 1
            val lineEndOffset = document.getLineEndOffset(afterTemplateLine)
            insertOffset = lineEndOffset
            textToInsert = "\n${paramsBlockIndent}parameters:\n$newEntry"
        } else {
            val lineEndOffset = document.getLineEndOffset(insertAfterLine)
            insertOffset = lineEndOffset
            textToInsert = "\n$newEntry"
        }

        // ── Write the text ────────────────────────────────────────────────────
        document.insertString(insertOffset, textToInsert)
        PsiDocumentManager.getInstance(project).commitDocument(document)

        // ── Move caret to end of inserted line ────────────────────────────────
        val caretOffset = insertOffset + textToInsert.length
        val editor = (FileEditorManager.getInstance(project)
            .getSelectedEditor(psiFile.virtualFile) as? TextEditor)?.editor
        editor?.caretModel?.moveToOffset(caretOffset)
        editor?.scrollingModel?.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }
}

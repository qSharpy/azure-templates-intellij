package com.bogdanbujor.azuretemplates.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * Quick-fix: "Remove unknown parameter 'paramName'"
 *
 * Deletes the entire line that contains the unknown parameter key, including
 * the trailing newline, so no blank line is left behind.
 *
 * @param paramName  The name of the unknown parameter whose line should be removed.
 */
class RemoveUnknownParameterFix(
    private val paramName: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Azure Templates Navigator"

    override fun getName(): String = "Remove unknown parameter '$paramName'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiElement = descriptor.psiElement ?: return
        val psiFile = psiElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        PsiDocumentManager.getInstance(project).commitDocument(document)

        // Locate the line that contains the flagged element.
        val elementOffset = psiElement.textOffset
        val lineNumber = document.getLineNumber(elementOffset)

        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)

        // Include the trailing newline character so no blank line is left.
        val deleteEnd = if (lineEnd < document.textLength) lineEnd + 1 else lineEnd

        document.deleteString(lineStart, deleteEnd)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

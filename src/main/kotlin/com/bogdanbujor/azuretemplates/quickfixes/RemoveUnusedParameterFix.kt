package com.bogdanbujor.azuretemplates.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * Quick-fix: "Remove unused parameter declaration '<paramName>'"
 *
 * Deletes the entire `- name: <paramName>` entry from the `parameters:` block,
 * including any sub-properties (`type:`, `default:`, `values:`, etc.) that belong
 * to that entry, and the trailing newline so no blank line is left behind.
 *
 * The fix operates purely on the document text so it works even when the PSI
 * tree is not fully resolved (e.g. during on-the-fly inspection passes).
 *
 * @param paramName  The name of the unused parameter whose declaration should be removed.
 */
class RemoveUnusedParameterFix(
    private val paramName: String
) : LocalQuickFix {

    override fun getFamilyName(): String = "Azure Templates Navigator"

    override fun getName(): String = "Remove unused parameter declaration '$paramName'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiElement = descriptor.psiElement ?: return
        val psiFile = psiElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        PsiDocumentManager.getInstance(project).commitDocument(document)

        val docText = document.text
        val lines = docText.replace("\r\n", "\n").split("\n")

        // ── Find the declaration line ─────────────────────────────────────────
        // Pattern: optional leading spaces, then "- name: <paramName>"
        val nameLineRegex = Regex("""^(\s*)-\s+name\s*:\s*${Regex.escape(paramName)}\s*$""")
        val declarationLineIndex = lines.indexOfFirst { nameLineRegex.containsMatchIn(it) }
        if (declarationLineIndex < 0) return

        val declarationIndent = nameLineRegex.find(lines[declarationLineIndex])!!.groupValues[1].length

        // ── Find the last sub-property line belonging to this entry ───────────
        // Sub-properties are lines whose indent is strictly greater than the
        // declaration indent (the "- name:" line itself).  We stop when we hit
        // a blank line followed by a sibling entry, or a sibling entry directly.
        var lastLineIndex = declarationLineIndex
        for (j in (declarationLineIndex + 1) until lines.size) {
            val sub = lines[j]
            if (sub.trim().isEmpty()) {
                // Peek ahead: if the next non-blank line is a sibling or parent, stop.
                val nextNonBlank = lines.drop(j + 1).firstOrNull { it.trim().isNotEmpty() }
                if (nextNonBlank != null) {
                    val nextIndent = nextNonBlank.length - nextNonBlank.trimStart().length
                    if (nextIndent <= declarationIndent) break
                }
                // Otherwise the blank line is still part of this entry's block.
                lastLineIndex = j
                continue
            }
            val subIndent = sub.length - sub.trimStart().length
            if (subIndent <= declarationIndent) break
            lastLineIndex = j
        }

        // ── Delete from start of declaration line to end of last sub-property ─
        val deleteStart = document.getLineStartOffset(declarationLineIndex)
        val rawEnd = document.getLineEndOffset(lastLineIndex)
        // Include the trailing newline so no blank line is left.
        val deleteEnd = if (rawEnd < document.textLength) rawEnd + 1 else rawEnd

        document.deleteString(deleteStart, deleteEnd)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.UnusedParameterChecker
import com.bogdanbujor.azuretemplates.quickfixes.RemoveUnusedParameterFix
import com.bogdanbujor.azuretemplates.settings.PluginSettings
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * Template-side inspection: **Unused Parameter Declaration**.
 *
 * Complements [TemplateInspection] (which validates the *caller* side) by
 * inspecting the *template* itself.  Any parameter declared in the top-level
 * `parameters:` block that is never referenced via `${{ parameters.name }}`
 * anywhere in the template body is reported as a **Warning**.
 *
 * A one-click quick-fix ([RemoveUnusedParameterFix]) removes the entire
 * `- name: …` entry (including sub-properties such as `type:`, `default:`,
 * and `values:`) from the declaration block.
 *
 * ### When is a parameter considered "used"?
 * The checker scans for the pattern `${{ parameters.<name> }}` (with optional
 * surrounding whitespace) anywhere in the file, including inside `if`
 * expressions such as `${{ if eq(parameters.runTests, true) }}`.
 *
 * ### Scope
 * Only `.yml` / `.yaml` files are inspected.  The inspection is skipped when
 * the user has disabled diagnostics in the plugin settings.
 */
class UnusedParameterInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Unused template parameter declaration"

    override fun getGroupDisplayName(): String = "Azure Templates Navigator"

    override fun getShortName(): String = "AzureTemplatesUnusedParameter"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (!PluginSettings.getInstance().diagnosticsEnabled) return

                val virtualFile = file.virtualFile ?: return
                val path = virtualFile.path
                if (!path.endsWith(".yml") && !path.endsWith(".yaml")) return

                val document = file.viewProvider.document ?: return
                val docText = document.text

                val issues = UnusedParameterChecker.check(docText)

                for (issue in issues) {
                    val lineIndex = issue.declarationLine
                    if (lineIndex < 0 || lineIndex >= document.lineCount) continue

                    val lineStart = document.getLineStartOffset(lineIndex)
                    val lineEnd = document.getLineEndOffset(lineIndex)
                    if (lineStart >= lineEnd) continue

                    // Highlight only the parameter name token (e.g. "publishArtifact"),
                    // not the leading "  - name: " prefix or any whitespace.
                    // Find the name's character offset within the line text, then
                    // resolve the PSI element at that exact document offset.
                    val lineText = document.text.substring(lineStart, lineEnd)
                    val nameIndexInLine = lineText.indexOf(issue.paramName)
                    val nameOffset = if (nameIndexInLine >= 0) {
                        lineStart + nameIndexInLine
                    } else {
                        // Fallback: first non-whitespace character on the line
                        lineStart + (lineText.length - lineText.trimStart().length)
                    }

                    val element = file.findElementAt(nameOffset)
                        ?: file.findElementAt(lineStart)
                        ?: continue

                    holder.registerProblem(
                        element,
                        "Parameter '${issue.paramName}' is declared but never used in the template body",
                        ProblemHighlightType.WARNING,
                        RemoveUnusedParameterFix(issue.paramName)
                    )
                }
            }
        }
    }
}

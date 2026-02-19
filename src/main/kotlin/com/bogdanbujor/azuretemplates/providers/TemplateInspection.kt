package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.*
import com.bogdanbujor.azuretemplates.quickfixes.AddMissingParameterFix
import com.bogdanbujor.azuretemplates.quickfixes.FixTypeMismatchFix
import com.bogdanbujor.azuretemplates.quickfixes.RemoveUnknownParameterFix
import com.bogdanbujor.azuretemplates.settings.PluginSettings
import com.intellij.codeInspection.*
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * Local inspection that validates template parameter usage in YAML files.
 *
 * Port of getDiagnosticsForDocument() + validateCallSite() from the VS Code extension.
 *
 * Three checks per call site:
 * 1. Missing required parameters (ERROR)  → quick-fix: AddMissingParameterFix
 * 2. Unknown parameters (WARNING)         → quick-fix: RemoveUnknownParameterFix
 * 3. Type mismatches (WARNING)            → quick-fix: FixTypeMismatchFix
 */
class TemplateInspection : LocalInspectionTool() {

    companion object {
        private val COMMENT_STRIP_REGEX = Regex("(^\\s*#.*|\\s#.*)$")
        private val TEMPLATE_REF_REGEX = Regex("(?:^|\\s)-?\\s*template\\s*:\\s*(.+)$")
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (!PluginSettings.getInstance().diagnosticsEnabled) return

                val virtualFile = file.virtualFile ?: return
                val path = virtualFile.path
                if (!path.endsWith(".yml") && !path.endsWith(".yaml")) return

                val document = file.viewProvider.document ?: return
                val docText = document.text
                val lines = docText.replace("\r\n", "\n").split("\n")
                val currentFile = virtualFile.path
                val repoAliases = RepositoryAliasParser.parse(docText)

                for (i in lines.indices) {
                    val line = lines[i]
                    // Strip YAML line comments before matching
                    val stripped = line.replace(COMMENT_STRIP_REGEX, "")
                    val match = TEMPLATE_REF_REGEX.find(stripped) ?: continue

                    val templateRef = match.groupValues[1].trim()
                    // Skip template expressions with variables
                    if (templateRef.contains("\${") || templateRef.contains("\$(")) continue

                    val issues = CallSiteValidator.validate(lines, i, templateRef, currentFile, repoAliases)

                    for (issue in issues) {
                        val lineStartOffset = document.getLineStartOffset(issue.line)
                        val lineEndOffset = document.getLineEndOffset(issue.line)
                        val lineLength = lineEndOffset - lineStartOffset

                        val startOffset = lineStartOffset + issue.startColumn.coerceAtMost(lineLength)
                        val endOffset = lineStartOffset + issue.endColumn.coerceAtMost(lineLength)

                        if (startOffset >= endOffset) continue

                        val element = file.findElementAt(startOffset) ?: continue

                        val severity = when (issue.severity) {
                            IssueSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR
                            IssueSeverity.WARNING -> ProblemHighlightType.WARNING
                        }

                        // ── Attach the appropriate quick-fix ──────────────────
                        val fixes: Array<LocalQuickFix> = when (issue.code) {
                            "missing-required-param" -> {
                                val name = issue.paramName ?: ""
                                val type = issue.paramType ?: "string"
                                if (name.isNotEmpty()) {
                                    arrayOf(
                                        AddMissingParameterFix(
                                            paramName = name,
                                            paramType = type,
                                            insertAfterLine = issue.insertAfterLine,
                                            templateLine = issue.line
                                        )
                                    )
                                } else {
                                    emptyArray()
                                }
                            }

                            "unknown-param" -> {
                                val name = issue.paramName ?: ""
                                if (name.isNotEmpty()) {
                                    arrayOf(RemoveUnknownParameterFix(paramName = name))
                                } else {
                                    emptyArray()
                                }
                            }

                            "type-mismatch" -> {
                                val name = issue.paramName ?: ""
                                val type = issue.paramType ?: ""
                                val value = issue.passedValue ?: ""
                                if (name.isNotEmpty() && type.isNotEmpty() && value.isNotEmpty()) {
                                    arrayOf(
                                        FixTypeMismatchFix(
                                            paramName = name,
                                            paramType = type,
                                            currentValue = value
                                        )
                                    )
                                } else {
                                    emptyArray()
                                }
                            }

                            else -> emptyArray()
                        }

                        holder.registerProblem(
                            element,
                            issue.message,
                            severity,
                            *fixes
                        )
                    }
                }
            }
        }
    }
}

package com.bogdanbujor.azuretemplates.quickfixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * Quick-fix: "Fix type mismatch for 'paramName' — use <correctedValue>"
 *
 * Rewrites the passed value on the flagged line to the canonical literal format
 * expected by the declared parameter type:
 *
 * | Declared type | Correction applied                                      |
 * |---------------|---------------------------------------------------------|
 * | boolean       | Strips surrounding quotes → bare `true` / `false`       |
 * | number        | Strips surrounding quotes → bare numeric literal        |
 * | string        | Wraps the value in single quotes if not already quoted  |
 * | object/list   | Wraps the value in single quotes (scalar → string hint) |
 *
 * @param paramName     The parameter name (for the action label).
 * @param paramType     The declared type of the parameter.
 * @param currentValue  The raw value string that was passed (as parsed from YAML).
 */
class FixTypeMismatchFix(
    private val paramName: String,
    private val paramType: String,
    private val currentValue: String
) : LocalQuickFix {

    /** The corrected value string that will replace [currentValue] on the line. */
    private val correctedValue: String = buildCorrectedValue(paramType, currentValue)

    override fun getFamilyName(): String = "Azure Templates Navigator"

    override fun getName(): String =
        "Fix type mismatch for '$paramName' — use: $correctedValue"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiElement = descriptor.psiElement ?: return
        val psiFile = psiElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

        PsiDocumentManager.getInstance(project).commitDocument(document)

        val elementOffset = psiElement.textOffset
        val lineNumber = document.getLineNumber(elementOffset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))

        // Find the value portion after "paramName:" and replace it.
        val keyPattern = Regex("(^.*?\\b${Regex.escape(paramName)}\\s*:\\s*)(.*?)\\s*$")
        val match = keyPattern.find(lineText) ?: return

        val valueStart = lineStart + match.groups[2]!!.range.first
        val valueEnd = lineStart + match.groups[2]!!.range.last + 1

        document.replaceString(valueStart, valueEnd, correctedValue)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    companion object {
        /**
         * Derives the corrected literal value for the given declared [type] and raw [value].
         */
        fun buildCorrectedValue(type: String, value: String): String {
            val unquoted = value.trimStart('\'', '"').trimEnd('\'', '"')
            return when (type.lowercase()) {
                "boolean" -> {
                    // Normalise to bare true/false
                    when (unquoted.lowercase()) {
                        "true", "yes", "on" -> "true"
                        "false", "no", "off" -> "false"
                        else -> unquoted
                    }
                }
                "number" -> {
                    // Strip quotes from a numeric literal
                    unquoted
                }
                "string" -> {
                    // Wrap in single quotes if not already quoted
                    if (value.startsWith("'") && value.endsWith("'")) value
                    else if (value.startsWith("\"") && value.endsWith("\"")) "'$unquoted'"
                    else "'$value'"
                }
                else -> {
                    // object / step / job / stage / *list types — suggest quoting as a string
                    if (value.startsWith("'") || value.startsWith("\"")) value
                    else "'$value'"
                }
            }
        }
    }
}

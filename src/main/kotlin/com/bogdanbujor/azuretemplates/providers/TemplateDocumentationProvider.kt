package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.*
import com.bogdanbujor.azuretemplates.settings.PluginSettings
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.io.File

/**
 * Documentation provider for YAML files that shows hover tooltips for:
 * 1. Template references - shows parameter info with types, defaults, required markers
 * 2. Variable references - shows variable value/source
 *
 * Port of hoverProvider.provideHover() + buildHoverMarkdown() + buildVariableHoverMarkdown()
 * from the VS Code extension.
 */
class TemplateDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val psiFile = element.containingFile ?: return null
        val document = psiFile.viewProvider.document ?: return null
        val offset = element.textOffset
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
        val docText = document.text
        val filePath = psiFile.virtualFile?.path ?: return null

        // Try variable hover first
        val variableDoc = tryVariableHover(lineText, docText, offset - lineStart)
        if (variableDoc != null) return variableDoc

        // Try template hover
        return tryTemplateHover(lineText, docText, filePath)
    }

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int
    ): PsiElement? {
        // Return the context element to trigger generateDoc
        return contextElement
    }

    private fun tryVariableHover(lineText: String, docText: String, charOffset: Int): String? {
        val varPatterns = listOf(
            Regex("\\$\\(([\\w.]+)\\)"),                        // $(varName)
            Regex("\\$\\{\\{\\s*variables\\.([\\w.]+)\\s*\\}\\}")  // ${{ variables.varName }}
        )

        for (pattern in varPatterns) {
            for (match in pattern.findAll(lineText)) {
                val start = match.range.first
                val end = match.range.last + 1
                if (charOffset in start..end) {
                    val varName = match.groupValues[1]
                    val parsed = VariableParser.parse(docText)
                    val varInfo = parsed.variables[varName]
                    return buildVariableHoverHtml(varName, varInfo, parsed.groups)
                }
            }
        }
        return null
    }

    private fun tryTemplateHover(lineText: String, docText: String, currentFilePath: String): String? {
        val templateRef = TemplateResolver.extractTemplateRef(lineText) ?: return null

        val repoAliases = RepositoryAliasParser.parse(docText)
        val resolved = TemplateResolver.resolve(templateRef, currentFilePath, repoAliases) ?: return null

        // Unknown alias
        if (resolved.unknownAlias) {
            return buildUnknownAliasHtml(resolved.alias ?: "")
        }

        val filePath = resolved.filePath ?: return null

        // Read the template file
        val text = try {
            File(filePath).readText()
        } catch (e: Exception) {
            return buildFileNotFoundHtml(filePath, resolved.repoName)
        }

        val settings = PluginSettings.getInstance()
        val requiredColor = settings.requiredParameterColor
        val params = ParameterParser.parse(text)

        return buildTemplateHoverHtml(templateRef, params, requiredColor, resolved.repoName, filePath)
    }

    private fun buildTemplateHoverHtml(
        templateRef: String,
        params: List<TemplateParameter>,
        requiredColor: String,
        repoName: String?,
        filePath: String
    ): String {
        val sb = StringBuilder()
        sb.append("<html><head><style>")
        sb.append("body { min-width: 500px; }")
        sb.append("li { white-space: nowrap; }")
        sb.append("</style></head><body>")

        // Header
        sb.append("<b>Template:</b> <code>${templateRef.trim()}</code><br/><br/>")

        if (repoName != null) {
            sb.append("<b>External repository:</b> <code>$repoName</code><br/><br/>")
        }

        // File path
        sb.append("<b>File:</b> <code>$filePath</code><br/><br/>")

        if (params.isEmpty()) {
            sb.append("<i>No parameters defined</i>")
        } else {
            sb.append("<b>Parameters:</b><br/>")
            sb.append("<ul>")
            for (p in params) {
                val nameHtml = if (p.required) {
                    "<span style=\"color:$requiredColor;\"><b>${p.name}</b></span>"
                } else {
                    "<b>${p.name}</b>"
                }
                val badge = if (p.required) " <i>(required)</i>" else ""
                val defaultPart = if (p.default != null) " &mdash; default: <code>${p.default}</code>" else ""
                sb.append("<li>$nameHtml: <code>${p.type}</code>$defaultPart$badge</li>")
            }
            sb.append("</ul>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun buildVariableHoverHtml(
        varName: String,
        varInfo: PipelineVariable?,
        groups: List<VariableGroup>
    ): String {
        val sb = StringBuilder()
        sb.append("<html><head><style>body { min-width: 500px; }</style></head><body>")

        val systemPrefixes = listOf("Build.", "System.", "Agent.", "Pipeline.", "Environment.", "Release.", "Deployment.", "Strategy.")
        val isSystem = systemPrefixes.any { varName.startsWith(it) }

        if (varInfo != null) {
            sb.append("<b>Variable:</b> <code>$varName</code><br/><br/>")
            if (varInfo.value.isNotEmpty()) {
                sb.append("<b>Value:</b> <code>${varInfo.value}</code><br/><br/>")
            } else {
                sb.append("<b>Value:</b> <i>(empty string)</i><br/><br/>")
            }
            sb.append("<b>Source:</b> pipeline <code>variables:</code> block (line ${varInfo.line + 1})")
        } else if (isSystem) {
            sb.append("<b>System variable:</b> <code>$varName</code><br/><br/>")
            sb.append("Azure DevOps predefined variable &mdash; available at runtime.<br/><br/>")
            sb.append("<a href=\"https://learn.microsoft.com/en-us/azure/devops/pipelines/build/variables\">View predefined variables</a>")
        } else {
            sb.append("<b>Variable:</b> <code>$varName</code><br/><br/>")
            sb.append("<i>Not found in the pipeline <code>variables:</code> block.</i><br/><br/>")
            if (groups.isNotEmpty()) {
                val groupNames = groups.joinToString(", ") { "<code>${it.name}</code>" }
                sb.append("May be defined in variable group(s): $groupNames")
            }
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun buildUnknownAliasHtml(alias: String): String {
        return """
            <html><head><style>body { min-width: 500px; }</style></head><body>
            <b>Repository alias not found:</b> <code>@$alias</code><br/><br/>
            <i>Add a <code>resources.repositories</code> entry with <code>repository: $alias</code>
            to enable cross-repo template resolution.</i>
            </body></html>
        """.trimIndent()
    }

    private fun buildFileNotFoundHtml(filePath: String, repoName: String?): String {
        val sb = StringBuilder()
        sb.append("<html><head><style>body { min-width: 500px; }</style></head><body>")
        sb.append("<b>Template not found:</b><br/><br/><code>$filePath</code>")
        if (repoName != null) {
            sb.append("<br/><br/><i>Make sure the <code>$repoName</code> repository is cloned next to this workspace.</i>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }
}

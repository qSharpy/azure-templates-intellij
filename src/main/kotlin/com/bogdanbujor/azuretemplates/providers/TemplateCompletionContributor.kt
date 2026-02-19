package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.*
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Completion contributor for YAML template parameter names.
 *
 * Port of completionProvider.provideCompletionItems() from the VS Code extension.
 *
 * When typing inside a "parameters:" block under a "- template:" line,
 * offers completion items for each parameter declared in the referenced template.
 */
class TemplateCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    val psiFile = parameters.originalFile
                    val document = psiFile.viewProvider.document ?: return
                    val offset = parameters.offset
                    val docText = document.text
                    val lines = docText.replace("\r\n", "\n").split("\n")
                    val cursorLine = document.getLineNumber(offset)
                    val currentFilePath = psiFile.virtualFile?.path ?: return

                    // Step 1: Find the enclosing template: line
                    val enclosing = findEnclosingTemplate(lines, cursorLine) ?: return
                    val (templateRef, templateLine) = enclosing

                    // Skip template expressions with variables
                    if (templateRef.contains("\${") || templateRef.contains("\$(")) return

                    // Step 2: Confirm cursor is inside the parameters: block
                    if (!isCursorInParametersBlock(lines, cursorLine, templateLine)) return

                    // Step 3: Resolve the template file
                    val repoAliases = RepositoryAliasParser.parse(docText)
                    val resolved = TemplateResolver.resolve(templateRef, currentFilePath, repoAliases)
                    if (resolved == null || resolved.unknownAlias || resolved.filePath == null) return

                    val filePath = resolved.filePath
                    val templateText = ReadAction.compute<String?, Throwable> {
                        val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
                            ?: return@compute null
                        try { String(vf.contentsToByteArray(), vf.charset) } catch (e: Exception) { null }
                    } ?: return

                    // Step 4: Parse declared parameters
                    val declared = ParameterParser.parse(templateText)
                    if (declared.isEmpty()) return

                    // Step 5: Find already-passed parameters to avoid duplicates
                    val passed = PassedParameterParser.parse(lines, templateLine)
                    val alreadyPassed = passed.keys

                    // Step 6: Build CompletionItems
                    for (param in declared) {
                        val alreadySet = param.name in alreadyPassed

                        val priority = when {
                            alreadySet -> 0.0
                            param.required -> 100.0
                            else -> 50.0
                        }

                        val icon = if (param.required) AllIcons.Nodes.PropertyWrite else AllIcons.Nodes.Property

                        val tailText = buildString {
                            append(": ${param.type}")
                            if (param.default != null) append(" = ${param.default}")
                            if (alreadySet) append(" (already set)")
                        }

                        val element = LookupElementBuilder.create(param.name)
                            .withIcon(icon)
                            .withTailText(tailText, true)
                            .withTypeText(if (param.required) "required" else "optional")
                            .withInsertHandler { ctx, _ ->
                                ctx.document.insertString(ctx.tailOffset, ": ")
                                ctx.editor.caretModel.moveToOffset(ctx.tailOffset)
                            }

                        result.addElement(PrioritizedLookupElement.withPriority(element, priority))
                    }
                }
            }
        )
    }

    /**
     * Walks backwards from cursorLine to find the nearest "- template:" line.
     */
    private fun findEnclosingTemplate(lines: List<String>, cursorLine: Int): Pair<String, Int>? {
        val cursorRaw = lines.getOrNull(cursorLine) ?: return null
        val cursorIndent = cursorRaw.length - cursorRaw.trimStart().length

        var shallowestSeen = cursorIndent

        for (i in (cursorLine - 1) downTo 0) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()
            val stripped = trimmed.trimStart()
            if (stripped.isEmpty()) continue

            val lineIndent = trimmed.length - stripped.length

            if (lineIndent < cursorIndent) {
                val cleaned = trimmed.replace(Regex("(^\\s*#.*|\\s#.*)$"), "")
                val match = Regex("(?:^|\\s)-?\\s*template\\s*:\\s*(.+)$").find(cleaned)
                if (match != null) {
                    return Pair(match.groupValues[1].trim(), i)
                }

                if (lineIndent < shallowestSeen) {
                    shallowestSeen = lineIndent
                }

                if (lineIndent == 0) break
            }
        }

        return null
    }

    /**
     * Determines whether the cursor is inside the "parameters:" sub-block of a template call.
     */
    private fun isCursorInParametersBlock(lines: List<String>, cursorLine: Int, templateLine: Int): Boolean {
        val templateRaw = lines[templateLine]
        val templateIndent = templateRaw.length - templateRaw.trimStart().length

        for (i in (templateLine + 1)..cursorLine) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()
            val stripped = trimmed.trimStart()
            if (stripped.isEmpty()) continue

            val lineIndent = trimmed.length - stripped.length

            if (lineIndent <= templateIndent) return false

            if (Regex("^\\s+parameters\\s*:").containsMatchIn(trimmed)) {
                return true
            }

            break
        }

        return false
    }
}

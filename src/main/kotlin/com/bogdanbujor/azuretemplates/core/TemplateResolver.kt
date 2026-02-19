package com.bogdanbujor.azuretemplates.core

import java.io.File

/**
 * Resolves template file paths from Azure Pipeline template references.
 *
 * Port of `resolveTemplatePath()`, `resolveLocalPath()`, `findRepoRoot()`,
 * and `findOwningTemplateLine()` from the VS Code extension's hoverProvider.js (lines 444-520, 127-163).
 *
 * Azure Pipelines path rules:
 * - Contains `@alias` → external repository; resolved as `{repoRoot}/../{repo-name}/{templatePath}`
 * - `@self` → treat as local
 * - Starts with `/` → relative to the repository root (where .git lives)
 * - Otherwise → relative to the directory of the current file
 */
object TemplateResolver {

    private val TEMPLATE_LINE_REGEX = Regex("(?:^|\\s)-?\\s*template\\s*:\\s*(.+)$")
    private val PARAMETERS_KEY_REGEX = Regex("^parameters\\s*:")

    /**
     * Walks up the directory tree from [startDir] to find the nearest directory
     * that contains a `.git` folder (i.e. the repo root).
     * Falls back to [startDir] if no `.git` is found.
     */
    fun findRepoRoot(startDir: File): File {
        var dir = startDir
        while (true) {
            if (File(dir, ".git").exists()) return dir
            val parent = dir.parentFile ?: return startDir
            if (parent == dir) return startDir
            dir = parent
        }
    }

    /**
     * Resolves the absolute path of a template reference.
     *
     * @param templateRef The raw string after "template:"
     * @param currentFile Absolute path of the file being hovered
     * @param repoAliases alias → repo folder name map
     * @return [ResolvedTemplate] with the resolved file path, or null if the ref is empty
     */
    fun resolve(templateRef: String, currentFile: String, repoAliases: Map<String, String>): ResolvedTemplate? {
        val ref = templateRef.trim()
        if (ref.isEmpty()) return null

        // Check for cross-repo reference: "path/to/template.yml@alias"
        val atIndex = ref.lastIndexOf('@')
        if (atIndex != -1) {
            val templatePath = ref.substring(0, atIndex).trim()
            val alias = ref.substring(atIndex + 1).trim()

            // Special alias "self" means the current repository — treat as normal
            if (alias == "self") {
                return resolveLocalPath(templatePath, currentFile)
            }

            val repoName = repoAliases[alias]
            if (repoName == null) {
                // Alias not found in resources.repositories
                return ResolvedTemplate(filePath = null, repoName = null, alias = alias, unknownAlias = true)
            }

            // Resolve: {repoRoot}/../{repo-name}/{templatePath}
            val repoRoot = findRepoRoot(File(currentFile).parentFile)
            val parentDir = repoRoot.parentFile
            val cleanPath = if (templatePath.startsWith("/")) templatePath.substring(1) else templatePath
            val filePath = File(parentDir, "$repoName/$cleanPath").absolutePath
            return ResolvedTemplate(filePath = filePath, repoName = repoName, alias = alias)
        }

        return resolveLocalPath(ref, currentFile)
    }

    /**
     * Resolves a local (non-cross-repo) template path.
     */
    private fun resolveLocalPath(ref: String, currentFile: String): ResolvedTemplate {
        if (ref.startsWith("/")) {
            // Absolute path: resolve from the repo root (nearest .git ancestor)
            val repoRoot = findRepoRoot(File(currentFile).parentFile)
            val filePath = File(repoRoot, ref.substring(1)).absolutePath
            return ResolvedTemplate(filePath = filePath, repoName = null)
        }

        // Relative path: resolve from the directory of the current file
        val filePath = File(File(currentFile).parentFile, ref).absolutePath
        return ResolvedTemplate(filePath = filePath, repoName = null)
    }

    /**
     * Given a document's lines and a cursor line number, walks upward to find the
     * nearest `- template:` line that "owns" the cursor position (i.e. the cursor
     * is inside the parameters sub-block of that template call).
     *
     * Returns the 0-based line index of the `- template:` line, or -1 if not found.
     *
     * @param lines All lines of the document (0-based)
     * @param cursorLine 0-based line index of the cursor
     * @return 0-based line index of the owning template line, or -1
     */
    fun findOwningTemplateLine(lines: List<String>, cursorLine: Int): Int {
        val cursorRaw = lines[cursorLine]
        val cursorIndent = cursorRaw.length - cursorRaw.trimStart().length

        // Track the minimum indent we've accepted so far
        var minIndentSeen = cursorIndent

        for (i in (cursorLine - 1) downTo 0) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()
            val stripped = trimmed.trimStart()

            if (stripped.isEmpty()) continue

            val lineIndent = trimmed.length - stripped.length

            // Only consider lines that are shallower than what we've seen so far
            if (lineIndent >= minIndentSeen) continue

            minIndentSeen = lineIndent

            // Is this the template line?
            val cleanLine = trimmed.replace(Regex("(^\\s*#.*|\\s#.*)$"), "")
            val templateMatch = TEMPLATE_LINE_REGEX.find(cleanLine)
            if (templateMatch != null) return i

            // Is this a "parameters:" key? — allowed intermediate ancestor, keep going
            if (PARAMETERS_KEY_REGEX.containsMatchIn(stripped)) continue

            // Any other shallower line means we've left the template call block
            return -1
        }

        return -1
    }

    /**
     * Extracts the template reference string from a line of YAML text.
     * Returns null if the line doesn't contain a template reference.
     *
     * @param lineText The raw line text
     * @return The template reference string, or null
     */
    fun extractTemplateRef(lineText: String): String? {
        // Strip YAML line comments first to avoid matching "# ── Step template: ..."
        val cleaned = lineText.replace(Regex("(^\\s*#.*|\\s#.*)$"), "")
        val match = TEMPLATE_LINE_REGEX.find(cleaned) ?: return null
        return match.groupValues[1].trim()
    }
}

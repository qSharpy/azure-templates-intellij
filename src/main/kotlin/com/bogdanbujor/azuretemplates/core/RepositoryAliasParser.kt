package com.bogdanbujor.azuretemplates.core

/**
 * Parses the `resources.repositories` block from a pipeline YAML document and
 * returns a map of alias → repo-name (the last segment of `name: org/repo`).
 *
 * Port of `parseRepositoryAliases()` in the VS Code extension's hoverProvider.js (lines 181-260).
 *
 * Example YAML:
 * ```yaml
 * resources:
 *   repositories:
 *     - repository: templates
 *       name: myorg/template-repo-name
 *       type: git
 * ```
 *
 * Returns: `{ "templates" to "template-repo-name" }`
 */
object RepositoryAliasParser {

    private val RESOURCES_KEY = Regex("^resources\\s*:")
    private val REPOSITORIES_KEY = Regex("^\\s+repositories\\s*:")
    private val REPO_ENTRY = Regex("^(\\s*)-\\s+repository\\s*:\\s*(.+)$")
    private val NAME_PROP = Regex("^\\s+name\\s*:\\s*(.+)$")

    /**
     * Parses repository aliases from the given YAML text.
     *
     * @param text Raw file contents of the pipeline YAML
     * @return Map of alias → repo folder name
     */
    fun parse(text: String): Map<String, String> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val aliases = mutableMapOf<String, String>()

        var inResources = false
        var inRepositories = false
        var repoIndent = -1
        var currentAlias: String? = null

        for (i in lines.indices) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()
            val stripped = trimmed.trimStart()

            // Detect top-level "resources:" key
            if (!inResources) {
                if (RESOURCES_KEY.containsMatchIn(trimmed)) {
                    inResources = true
                }
                continue
            }

            // If we hit another top-level key, we've left the resources block
            if (trimmed.isNotEmpty() && !trimmed.startsWith(" ") && !trimmed.startsWith("\t")) {
                break
            }

            // Detect "  repositories:" inside resources
            if (!inRepositories) {
                if (REPOSITORIES_KEY.containsMatchIn(trimmed)) {
                    inRepositories = true
                }
                continue
            }

            val lineIndent = trimmed.length - stripped.length

            // Detect a new repository list item: "    - repository: alias"
            val repoMatch = REPO_ENTRY.find(trimmed)
            if (repoMatch != null) {
                val indent = repoMatch.groupValues[1].length
                if (repoIndent == -1) repoIndent = indent

                // A list item at a shallower indent means we've left repositories
                if (indent < repoIndent) break

                // Only process items at the base repository list indent
                if (indent == repoIndent) {
                    currentAlias = repoMatch.groupValues[2].trim()
                }
                continue
            }

            // If we're inside a repository item, look for "name: org/repo"
            if (currentAlias != null) {
                val nameMatch = NAME_PROP.find(trimmed)
                if (nameMatch != null) {
                    val fullName = nameMatch.groupValues[1].trim()
                    // Extract just the repo name (last segment after "/")
                    val repoName = if (fullName.contains("/")) {
                        fullName.split("/").last()
                    } else {
                        fullName
                    }
                    aliases[currentAlias!!] = repoName
                    continue
                }

                // If we hit a line at the same or shallower indent as the list item
                // that isn't a sub-property, reset currentAlias
                if (repoIndent != -1 && lineIndent <= repoIndent && stripped.isNotEmpty()) {
                    currentAlias = null
                }
            }
        }

        return aliases
    }
}

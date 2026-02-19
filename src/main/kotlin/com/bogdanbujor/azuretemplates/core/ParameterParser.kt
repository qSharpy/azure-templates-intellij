package com.bogdanbujor.azuretemplates.core

/**
 * Parses Azure Pipeline template parameters from raw YAML text.
 *
 * Port of `parseParameters()` in the VS Code extension's hoverProvider.js (lines 25-105).
 *
 * Azure Pipeline parameter blocks are well-structured:
 * ```yaml
 * parameters:
 *   - name: myParam
 *     type: string
 *     default: 'foo'
 * ```
 *
 * A parameter is considered **required** when it has no `default:` key —
 * exactly how Azure Pipelines itself treats parameters at runtime.
 */
object ParameterParser {

    private val PARAMETERS_KEY = Regex("^parameters\\s*:")
    private val NAME_ENTRY = Regex("^(\\s*)-\\s+name\\s*:\\s*(.+)$")
    private val TYPE_PROP = Regex("^\\s+type\\s*:\\s*(.+)$")
    private val DEFAULT_PROP = Regex("^\\s+default\\s*:\\s*(.*)$")

    /**
     * Parses the top-level `parameters:` block from YAML text.
     *
     * @param text Raw file contents
     * @return List of [TemplateParameter] found in the parameters block
     */
    fun parse(text: String): List<TemplateParameter> {
        // Normalize CRLF → LF so that regex $ anchors work on Windows-authored files
        val lines = text.replace("\r\n", "\n").split("\n")
        val params = mutableListOf<TemplateParameter>()

        var inParamsBlock = false
        var baseIndent = -1

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()

            // Detect the top-level "parameters:" key
            if (!inParamsBlock) {
                if (PARAMETERS_KEY.containsMatchIn(trimmed)) {
                    inParamsBlock = true
                }
                i++
                continue
            }

            // Once inside the block, a non-indented non-empty line that is NOT a list
            // item or a comment means we've left the parameters block.
            // We must NOT break on:
            //   - "- name: foo" lines at column 0 (baseIndent = 0 is valid YAML)
            //   - "# comment" lines at column 0 (common in repos that annotate params)
            if (trimmed.isNotEmpty() &&
                !trimmed.startsWith(" ") && !trimmed.startsWith("\t") &&
                !trimmed.startsWith("-") &&
                !trimmed.startsWith("#") &&
                !PARAMETERS_KEY.containsMatchIn(trimmed)
            ) {
                break
            }

            // Match a parameter entry: "  - name: foo"
            val nameMatch = NAME_ENTRY.find(trimmed)
            if (nameMatch == null) {
                i++
                continue
            }

            val indent = nameMatch.groupValues[1].length
            if (baseIndent == -1) baseIndent = indent

            // Only process items at the same indent level (direct children)
            if (indent != baseIndent) {
                i++
                continue
            }

            val paramName = nameMatch.groupValues[2].replace(Regex("\\s*#.*$"), "").trim()

            // Scan forward for type and default within this parameter's sub-block
            var type = "string"
            var defaultValue: String? = null

            for (j in (i + 1) until lines.size) {
                val sub = lines[j].trimEnd()
                if (sub.trim().isEmpty()) continue

                // If we hit another list item at the same indent, stop
                val nextName = NAME_ENTRY.find(sub)
                if (nextName != null && nextName.groupValues[1].length == baseIndent) break

                // If we hit a line with less or equal indent that isn't a sub-property, stop
                val subIndent = sub.length - sub.trimStart().length
                if (subIndent <= baseIndent && sub.trim().isNotEmpty()) break

                val typeMatch = TYPE_PROP.find(sub)
                if (typeMatch != null) {
                    type = typeMatch.groupValues[1].replace(Regex("\\s*#.*$"), "").trim()
                    continue
                }

                val defaultMatch = DEFAULT_PROP.find(sub)
                if (defaultMatch != null) {
                    defaultValue = defaultMatch.groupValues[1].replace(Regex("\\s*#.*$"), "").trim()
                    continue
                }
            }

            // A parameter is required when it has no default value — this matches
            // Azure Pipelines runtime behaviour exactly.
            val required = defaultValue == null

            params.add(
                TemplateParameter(
                    name = paramName,
                    type = type,
                    default = defaultValue,
                    required = required,
                    line = i
                )
            )

            i++
        }

        return params
    }
}

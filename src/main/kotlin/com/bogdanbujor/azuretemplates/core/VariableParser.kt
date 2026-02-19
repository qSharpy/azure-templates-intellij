package com.bogdanbujor.azuretemplates.core

/**
 * Parses the top-level `variables:` block from a pipeline YAML document.
 *
 * Port of `parseVariables()` in the VS Code extension's hoverProvider.js (lines 284-364).
 *
 * Handles both map form and list form:
 * ```yaml
 * # Map form
 * variables:
 *   buildConfiguration: Release
 *   dotnetVersion: 8.0.x
 *
 * # List form
 * variables:
 *   - name: buildConfiguration
 *     value: Release
 *   - group: my-variable-group
 * ```
 */
object VariableParser {

    private val VARIABLES_KEY = Regex("^variables\\s*:")
    private val MAP_ENTRY = Regex("^(\\s*)(\\w[\\w.-]*)\\s*:\\s*(.*)$")
    private val GROUP_ENTRY = Regex("^\\s*-\\s+group\\s*:\\s*(.+)$")
    private val NAME_ENTRY = Regex("^\\s*-\\s+name\\s*:\\s*(.+)$")
    private val VALUE_PROP = Regex("^\\s+value\\s*:\\s*(.*)$")

    /**
     * Parses variables from the given YAML text.
     *
     * @param text Raw file contents of the pipeline YAML
     * @return [ParsedVariables] containing variables map and variable groups
     */
    fun parse(text: String): ParsedVariables {
        val lines = text.replace("\r\n", "\n").split("\n")
        val variables = mutableMapOf<String, PipelineVariable>()
        val groups = mutableListOf<VariableGroup>()

        var inVarsBlock = false
        var baseIndent = -1
        var isList: Boolean? = null

        // List-form state
        var currentName: String? = null
        var currentNameLine = -1

        for (i in lines.indices) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()
            val stripped = trimmed.trimStart()

            if (!inVarsBlock) {
                if (VARIABLES_KEY.containsMatchIn(trimmed)) {
                    inVarsBlock = true
                }
                continue
            }

            // A non-indented non-empty line means we've left the variables block
            if (trimmed.isNotEmpty() && !trimmed.startsWith(" ") && !trimmed.startsWith("\t")) {
                break
            }

            if (stripped.isEmpty()) continue

            val lineIndent = trimmed.length - stripped.length

            // Determine form on first content line
            if (isList == null && stripped.isNotEmpty()) {
                isList = stripped.startsWith("- ") || stripped.startsWith("-\n")
                if (baseIndent == -1) baseIndent = lineIndent
            }

            if (isList == false) {
                // Map form: "  key: value"
                if (lineIndent == baseIndent) {
                    val mapMatch = MAP_ENTRY.find(trimmed)
                    if (mapMatch != null) {
                        val key = mapMatch.groupValues[2]
                        val value = mapMatch.groupValues[3].trim()
                        variables[key] = PipelineVariable(name = key, value = value, line = i)
                    }
                }
            } else {
                // List form
                if (lineIndent == baseIndent) {
                    // New list item
                    val groupMatch = GROUP_ENTRY.find(trimmed)
                    if (groupMatch != null) {
                        groups.add(VariableGroup(name = groupMatch.groupValues[1].trim(), line = i))
                        currentName = null
                        continue
                    }

                    val nameMatch = NAME_ENTRY.find(trimmed)
                    if (nameMatch != null) {
                        currentName = nameMatch.groupValues[1].trim()
                        currentNameLine = i
                        continue
                    }
                }

                // Sub-properties of a list item
                if (currentName != null && lineIndent > baseIndent) {
                    val valueMatch = VALUE_PROP.find(trimmed)
                    if (valueMatch != null) {
                        variables[currentName!!] = PipelineVariable(
                            name = currentName!!,
                            value = valueMatch.groupValues[1].trim(),
                            line = currentNameLine
                        )
                        currentName = null
                    }
                }
            }
        }

        return ParsedVariables(variables = variables, groups = groups)
    }
}

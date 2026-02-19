package com.bogdanbujor.azuretemplates.core

/**
 * Parses the parameters actually passed to a template at a specific call site.
 *
 * Port of parsePassedParameters() in the VS Code extension's hoverProvider.js (lines 382-435).
 *
 * Given a document's lines and the line number of the "- template:" line, scans the
 * "parameters:" sub-block that follows and returns a map of name to (value, line).
 */
object PassedParameterParser {

    private val PARAMETERS_KEY = Regex("^\\s+parameters\\s*:")
    private val PARAM_ENTRY = Regex("^(\\s+)([\\w-]+)\\s*:\\s*(.*)$")

    /**
     * Parses parameters passed at a template call site.
     *
     * @param lines All lines of the document
     * @param templateLine 0-based index of the "- template:" line
     * @return Map of parameter name to Pair(value, line number)
     */
    fun parse(lines: List<String>, templateLine: Int): Map<String, Pair<String, Int>> {
        val passed = mutableMapOf<String, Pair<String, Int>>()

        // Determine the indent of the template line itself
        val templateRaw = lines[templateLine]
        val templateIndent = templateRaw.length - templateRaw.trimStart().length

        var inParamsBlock = false
        var paramsIndent = -1
        // The indent of the first non-empty line directly under "parameters:" —
        // determined lazily on first encounter so we handle any indent width.
        var childIndent = -1

        for (i in (templateLine + 1) until lines.size) {
            val raw = lines[i]
            val trimmed = raw.trimEnd()
            val stripped = trimmed.trimStart()

            if (stripped.isEmpty()) continue

            val lineIndent = trimmed.length - stripped.length

            // If we've gone back to the template's indent level or shallower, we're done
            if (lineIndent <= templateIndent && stripped.isNotEmpty()) {
                break
            }

            if (!inParamsBlock) {
                // Look for "parameters:" at one indent level deeper than the template line
                if (PARAMETERS_KEY.containsMatchIn(trimmed)) {
                    inParamsBlock = true
                    paramsIndent = lineIndent
                }
                continue
            }

            // If we've gone back to or past the parameters: indent, we're done
            if (lineIndent <= paramsIndent) break

            // Determine the child indent level from the first non-empty line we see
            if (childIndent == -1) {
                childIndent = lineIndent
            }

            // Only capture direct children of the parameters block (at the first child indent level)
            if (lineIndent == childIndent) {
                val paramMatch = PARAM_ENTRY.find(trimmed)
                if (paramMatch != null) {
                    val paramName = paramMatch.groupValues[2]
                    val paramValue = paramMatch.groupValues[3].trim()
                    if (!passed.containsKey(paramName)) {
                        passed[paramName] = Pair(paramValue, i)
                    }
                }
            }
            // Lines deeper than childIndent are nested values — skip them
        }

        return passed
    }
}

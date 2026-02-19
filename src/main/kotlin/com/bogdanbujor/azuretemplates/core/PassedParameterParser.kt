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

            // Only capture direct children of the parameters block
            if (lineIndent == paramsIndent + 2 || lineIndent == paramsIndent + 4) {
                // Match "  paramName: value" -- skip nested objects/arrays for now
                val paramMatch = PARAM_ENTRY.find(trimmed)
                if (paramMatch != null && lineIndent > paramsIndent) {
                    val paramName = paramMatch.groupValues[2]
                    val paramValue = paramMatch.groupValues[3].trim()
                    // Only capture at the first level below parameters:
                    if (!passed.containsKey(paramName) || lineIndent == paramsIndent + 2) {
                        passed[paramName] = Pair(paramValue, i)
                    }
                }
            }
        }

        return passed
    }
}

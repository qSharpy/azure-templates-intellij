package com.bogdanbujor.azuretemplates.core

/**
 * Parses the parameters actually passed to a template at a specific call site.
 *
 * Port of parsePassedParameters() in the VS Code extension's hoverProvider.js (lines 382-435).
 *
 * Given a document's lines and the line number of the "- template:" line, scans the
 * "parameters:" sub-block that follows and returns a map of name to (value, line).
 *
 * ### Conditional expressions
 * Azure Pipelines allows parameters to be passed conditionally using `${{ if ... }}:`,
 * `${{ elseif ... }}:`, and `${{ else }}:` blocks.  Parameters nested inside these
 * blocks are still valid passed parameters and must be collected, otherwise the
 * validator incorrectly reports them as missing required parameters.
 *
 * ### Object-valued parameters
 * Parameters of type `object` (or `step`, `job`, etc.) are passed as multi-line YAML
 * mappings.  Their nested keys (e.g. `replicas`, `strategy`) are property values of
 * the parameter, NOT sibling parameters.  The parser tracks the indent of each
 * collected parameter entry and skips lines that are more deeply indented than it
 * (i.e. they are the object's body), resuming when the indent returns to the
 * parameter-entry level.
 *
 * ### Each pass-through
 * The idiom `${{ each parameter in parameters }}:` / `${{ parameter.key }}: ${{ parameter.value }}`
 * forwards ALL parameters from the outer pipeline into the template.  When this pattern
 * is detected inside the `parameters:` block, [hasEachPassthrough] returns `true` and
 * [CallSiteValidator] skips all missing/unknown/type-mismatch checks for that call site.
 */
object PassedParameterParser {

    private val PARAMETERS_KEY = Regex("^\\s+parameters\\s*:")
    private val PARAM_ENTRY = Regex("^(\\s+)([\\w-]+)\\s*:\\s*(.*)$")

    /**
     * Matches Azure Pipelines compile-time expression control lines:
     * `${{ if ... }}:`, `${{ elseif ... }}:`, `${{ else }}:`.
     * These are structural lines inside a parameters block — not parameter entries.
     */
    private val CONDITIONAL_LINE = Regex("""^\s*\$\{\{\s*(?:if|elseif|else)[\s\S]*\}\}\s*:""")

    /**
     * Matches the `${{ each <var> in parameters }}:` pass-through line.
     * When present inside a `parameters:` block, ALL declared parameters are
     * considered passed and no missing/unknown/type-mismatch checks should run.
     */
    private val EACH_PASSTHROUGH_LINE = Regex("""\$\{\{\s*each\s+\w+\s+in\s+parameters\s*\}\}\s*:""")

    /**
     * Parses parameters passed at a template call site.
     *
     * Parameters may appear directly under `parameters:` or nested inside
     * `${{ if }}` / `${{ elseif }}` / `${{ else }}` conditional blocks.
     * Object-valued parameters (empty value, body on subsequent indented lines)
     * are collected as a single entry; their nested property lines are skipped.
     *
     * @param lines All lines of the document
     * @param templateLine 0-based index of the "- template:" line
     * @return Map of parameter name to Pair(value, line number)
     */
    /**
     * Returns `true` if the `parameters:` block at [templateLine] contains a
     * `${{ each <var> in parameters }}:` line — the "pass-through all parameters" idiom.
     *
     * When this returns `true`, [CallSiteValidator] must skip all missing/unknown/
     * type-mismatch checks because every declared parameter is implicitly passed.
     */
    fun hasEachPassthrough(lines: List<String>, templateLine: Int): Boolean {
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

            if (lineIndent <= templateIndent) break

            if (!inParamsBlock) {
                if (PARAMETERS_KEY.containsMatchIn(trimmed)) {
                    inParamsBlock = true
                    paramsIndent = lineIndent
                }
                continue
            }

            if (lineIndent <= paramsIndent) break

            if (EACH_PASSTHROUGH_LINE.containsMatchIn(trimmed)) return true
        }

        return false
    }

    fun parse(lines: List<String>, templateLine: Int): Map<String, Pair<String, Int>> {
        val passed = mutableMapOf<String, Pair<String, Int>>()

        // Determine the indent of the template line itself
        val templateRaw = lines[templateLine]
        val templateIndent = templateRaw.length - templateRaw.trimStart().length

        var inParamsBlock = false
        var paramsIndent = -1

        // When >= 0, we are inside the body of an object-valued parameter.
        // Lines with indent > objectValueDepth are skipped until we return to
        // indent <= objectValueDepth, at which point we resume collecting params.
        var objectValueDepth = -1

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

            // If we're inside an object-valued parameter's body, check whether we've
            // returned to the parameter-entry indent level.
            if (objectValueDepth >= 0) {
                if (lineIndent <= objectValueDepth) {
                    // Exited the object body — resume collecting parameter entries
                    objectValueDepth = -1
                } else {
                    // Still inside the object body — skip this line
                    continue
                }
            }

            // Skip ${{ if }}:, ${{ elseif }}:, ${{ else }}: control lines —
            // they are structural, not parameter entries.  Parameters nested
            // inside these blocks are collected on subsequent iterations.
            if (CONDITIONAL_LINE.containsMatchIn(trimmed)) continue

            // Collect any key: value line inside the parameters block.
            // Parameters may appear directly under `parameters:` or nested one
            // level deeper inside a ${{ if }}:/${{ else }}: block — both are valid.
            val paramMatch = PARAM_ENTRY.find(trimmed)
            if (paramMatch != null) {
                val entryIndent = paramMatch.groupValues[1].length
                val paramName  = paramMatch.groupValues[2]
                val paramValue = paramMatch.groupValues[3].trim()

                if (!passed.containsKey(paramName)) {
                    passed[paramName] = Pair(paramValue, i)
                }

                // If the value is empty, the parameter's value is a multi-line YAML
                // object on the following indented lines.  Record this entry's indent
                // so we can skip those nested property lines.
                if (paramValue.isEmpty()) {
                    objectValueDepth = entryIndent
                }
            }
        }

        return passed
    }
}

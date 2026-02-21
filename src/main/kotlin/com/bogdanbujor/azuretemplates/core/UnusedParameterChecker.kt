package com.bogdanbujor.azuretemplates.core

/**
 * Detects parameters declared in a template's `parameters:` block that are never
 * referenced in the template body via `${{ parameters.name }}`.
 *
 * This is the **template-side** complement to [CallSiteValidator], which validates
 * the caller side.  Dead parameters are a common artifact of refactoring — a
 * parameter is removed from the body but its declaration is left behind.
 *
 * ### Detection strategy
 * 1. Parse all declared parameters with [ParameterParser] (reuses existing logic).
 * 2. Scan every line **after** the `parameters:` block for the pattern
 *    `${{ parameters.<name> }}` (with arbitrary surrounding whitespace).
 * 3. Any declared parameter whose name never appears in a reference is reported.
 *
 * ### Returned data
 * Each [UnusedParameterIssue] carries the 0-based [line][TemplateParameter.line]
 * of the `- name: …` entry so the inspection can highlight exactly that line.
 */
object UnusedParameterChecker {

    /**
     * Matches any reference to `parameters.<name>` anywhere in a line.
     * The capture group 1 holds the parameter name.
     *
     * This intentionally uses a simple `\bparameters\.(\w+)\b` pattern rather
     * than anchoring to `${{` so that it covers all reference forms:
     *
     * - `${{ parameters.name }}`                    — direct substitution
     * - `${{ if eq(parameters.name, true) }}`       — inside a function call
     * - `${{ if parameters.name }}:`                — bare if condition (no function)
     * - `${{ elseif parameters.name }}:`            — elseif condition
     * - `${{ replace(parameters.name, 'a', 'b') }}` — string function wrapping a param
     * - `${{ each item in parameters.list }}:`      — each loop source
     * - Nested / deeply-indented variants of all of the above
     *
     * Using `\b` word boundaries prevents `parameters.run` from matching
     * a reference to `parameters.runTests`.
     */
    private val PARAM_REF_REGEX = Regex("""\bparameters\.(\w+)\b""")

    /**
     * Matches a bare `parameters` reference inside a `${{ ... }}` expression —
     * i.e. the entire parameters object is passed to a function without selecting
     * a specific property:
     *
     * - `${{ convertToJson(parameters) }}`
     * - `${{ coalesce(parameters, '{}') }}`
     * - `${{ length(parameters) }}`
     *
     * The pattern requires `parameters` to be preceded (anywhere on the line) by
     * `${{` and to be followed by `)`, `,`, whitespace, or `}}` — NOT by `.`
     * (which would be a named property access caught by [PARAM_REF_REGEX] instead)
     * and NOT by `:` (which would be the YAML `parameters:` key).
     *
     * When this pattern is found anywhere in the file, ALL declared parameters
     * are considered "used" because the whole object is consumed at runtime.
     */
    private val BARE_PARAMS_REGEX = Regex("""\$\{\{[^}]*\bparameters\b(?!\s*[.:\w])""")

    /**
     * Analyses [text] (raw YAML file contents) and returns one [UnusedParameterIssue]
     * for every declared parameter that is never referenced in the body.
     *
     * @param text  Raw contents of the template YAML file.
     * @return      Possibly-empty list of unused-parameter issues.
     */
    fun check(text: String): List<UnusedParameterIssue> {
        val normalised = text.replace("\r\n", "\n")
        val declaredParams = ParameterParser.parse(normalised)
        if (declaredParams.isEmpty()) return emptyList()

        // Collect every parameter name that is actually referenced anywhere in the file.
        val referencedNames = mutableSetOf<String>()
        for (line in normalised.split("\n")) {
            // If the entire parameters object is referenced (e.g. convertToJson(parameters)),
            // all declared parameters are implicitly used — short-circuit immediately.
            if (BARE_PARAMS_REGEX.containsMatchIn(line)) {
                return emptyList()
            }
            PARAM_REF_REGEX.findAll(line).forEach { match ->
                referencedNames += match.groupValues[1]
            }
        }

        return declaredParams
            .filter { param -> param.name !in referencedNames }
            .map { param ->
                UnusedParameterIssue(
                    paramName = param.name,
                    declarationLine = param.line
                )
            }
    }
}

/**
 * Describes a single unused-parameter finding.
 *
 * @property paramName       The name of the declared but unreferenced parameter.
 * @property declarationLine 0-based line index of the `- name: <paramName>` entry.
 */
data class UnusedParameterIssue(
    val paramName: String,
    val declarationLine: Int
)

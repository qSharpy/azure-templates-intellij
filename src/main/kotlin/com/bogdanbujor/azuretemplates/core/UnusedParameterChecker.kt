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
     * Matches `${{ parameters.someName }}` with optional whitespace inside the
     * expression delimiters.  The capture group 1 holds the parameter name.
     *
     * Also matches the short-hand `parameters.name` used inside `if` expressions
     * such as `${{ if eq(parameters.runTests, true) }}`.
     */
    private val PARAM_REF_REGEX = Regex("""\$\{\{\s*(?:if\s+\S+\()?parameters\.(\w+)""")

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

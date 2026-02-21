package com.bogdanbujor.azuretemplates.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [UnusedParameterChecker].
 *
 * Each test exercises the pure-Kotlin checker logic without any IntelliJ
 * platform infrastructure, so they run as plain JUnit 4 tests.
 */
class UnusedParameterCheckerTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun check(yaml: String) = UnusedParameterChecker.check(yaml.trimIndent())

    // ── no issues ─────────────────────────────────────────────────────────────

    @Test
    fun `all parameters used - no issues reported`() {
        val yaml = """
            parameters:
              - name: project
                type: string
              - name: buildConfiguration
                type: string
                default: Release

            steps:
              - script: dotnet build ${'$'}{{ parameters.project }} --configuration ${'$'}{{ parameters.buildConfiguration }}
        """
        val issues = check(yaml)
        assertTrue("Expected no issues, got: $issues", issues.isEmpty())
    }

    @Test
    fun `no parameters block - no issues reported`() {
        val yaml = """
            trigger: none
            pool:
              vmImage: ubuntu-latest
            steps:
              - script: echo hello
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `empty parameters block - no issues reported`() {
        val yaml = """
            parameters:

            steps:
              - script: echo hello
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `parameter used inside if expression - not flagged`() {
        val yaml = """
            parameters:
              - name: runTests
                type: boolean
                default: true

            steps:
              - ${'$'}{{ if eq(parameters.runTests, true) }}:
                - script: dotnet test
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `parameter used with extra whitespace in expression - not flagged`() {
        val yaml = """
            parameters:
              - name: env
                type: string

            steps:
              - script: echo ${'$'}{{   parameters.env   }}
        """
        assertTrue(check(yaml).isEmpty())
    }

    // ── single unused parameter ───────────────────────────────────────────────

    @Test
    fun `single unused parameter is reported`() {
        val yaml = """
            parameters:
              - name: unusedParam
                type: string
                default: hello
              - name: usedParam
                type: string

            steps:
              - script: echo ${'$'}{{ parameters.usedParam }}
        """
        val issues = check(yaml)
        assertEquals(1, issues.size)
        assertEquals("unusedParam", issues[0].paramName)
    }

    @Test
    fun `unused parameter declaration line is correct`() {
        // "unusedParam" is the second parameter entry (0-based line 4 in the
        // trimmed YAML: line 0 = "parameters:", 1 = "  - name: usedParam",
        // 2 = "    type: string", 3 = "  - name: unusedParam", ...)
        val yaml = """
            parameters:
              - name: usedParam
                type: string
              - name: unusedParam
                type: string
                default: nope

            steps:
              - script: echo ${'$'}{{ parameters.usedParam }}
        """
        val issues = check(yaml)
        assertEquals(1, issues.size)
        assertEquals("unusedParam", issues[0].paramName)
        // The declaration line must be > 0 (it is not the first line).
        assertTrue("declarationLine should be > 0", issues[0].declarationLine > 0)
    }

    // ── multiple unused parameters ────────────────────────────────────────────

    @Test
    fun `multiple unused parameters are all reported`() {
        val yaml = """
            parameters:
              - name: alpha
                type: string
              - name: beta
                type: string
              - name: gamma
                type: string

            steps:
              - script: echo ${'$'}{{ parameters.alpha }}
        """
        val issues = check(yaml)
        assertEquals(2, issues.size)
        val names = issues.map { it.paramName }.toSet()
        assertTrue("beta" in names)
        assertTrue("gamma" in names)
    }

    @Test
    fun `all parameters unused - all reported`() {
        val yaml = """
            parameters:
              - name: foo
                type: string
              - name: bar
                type: boolean
                default: true

            steps:
              - script: echo nothing
        """
        val issues = check(yaml)
        assertEquals(2, issues.size)
        val names = issues.map { it.paramName }.toSet()
        assertTrue("foo" in names)
        assertTrue("bar" in names)
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `parameter name that is a substring of another is not falsely matched`() {
        // "run" must not be considered used just because "runTests" appears.
        val yaml = """
            parameters:
              - name: run
                type: boolean
                default: true
              - name: runTests
                type: boolean
                default: true

            steps:
              - ${'$'}{{ if eq(parameters.runTests, true) }}:
                - script: dotnet test
        """
        val issues = check(yaml)
        assertEquals(1, issues.size)
        assertEquals("run", issues[0].paramName)
    }

    @Test
    fun `CRLF line endings handled correctly`() {
        val yaml = "parameters:\r\n  - name: used\r\n    type: string\r\n  - name: unused\r\n    type: string\r\n\r\nsteps:\r\n  - script: echo \${{ parameters.used }}\r\n"
        val issues = UnusedParameterChecker.check(yaml)
        assertEquals(1, issues.size)
        assertEquals("unused", issues[0].paramName)
    }

    @Test
    fun `parameter referenced only in displayName is detected as used`() {
        val yaml = """
            parameters:
              - name: version
                type: string

            steps:
              - task: UseDotNet@2
                displayName: 'Install .NET ${'$'}{{ parameters.version }}'
                inputs:
                  version: '8.0.x'
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `notify-teams style template - includeRunLink and severity flagged`() {
        // Mirrors the modified samples/templates/notify-teams.yml
        val yaml = """
            parameters:
              - name: webhookUrl
                type: string
                default: 'https://teams.webhook.url'
              - name: message
                type: string
              - name: title
                type: string
                default: 'Pipeline Notification'
              - name: themeColor
                type: string
                default: '0076D7'
              - name: includeRunLink
                type: boolean
                default: true
              - name: severity
                type: string
                default: 'info'

            steps:
              - task: PowerShell@2
                displayName: 'Send Teams Notification'
                inputs:
                  targetType: 'inline'
                  script: |
                    ${'$'}body = @{
                      "themeColor" = "${'$'}{{ parameters.themeColor }}"
                      "summary"    = "${'$'}{{ parameters.title }}"
                      "message"    = "${'$'}{{ parameters.message }}"
                    }
                    Invoke-RestMethod -Uri "${'$'}{{ parameters.webhookUrl }}" -Method Post
        """
        val issues = check(yaml)
        val names = issues.map { it.paramName }.toSet()
        assertEquals(setOf("includeRunLink", "severity"), names)
    }

    @Test
    fun `parameter used inside object default values block is not flagged`() {
        // A parameter referenced only in another parameter's default should still
        // count as "used" — the regex scans the whole file.
        val yaml = """
            parameters:
              - name: baseUrl
                type: string
                default: 'https://example.com'
              - name: fullUrl
                type: string
                default: '${'$'}{{ parameters.baseUrl }}/api'

            steps:
              - script: echo ${'$'}{{ parameters.fullUrl }}
        """
        assertTrue(check(yaml).isEmpty())
    }

    // ── Bug 2: parameters used inside ${{ if parameters.name }}: form ─────────

    @Test
    fun `parameter used as bare if condition without function call - not flagged`() {
        // Bug 2: ${{ if parameters.isProd }}: was not matched by the old regex
        // because it required a function call wrapper like eq(...).
        val yaml = """
            parameters:
              - name: isProd
                type: boolean
                default: false

            variables:
              ${'$'}{{ if parameters.isProd }}:
                myVariable: deploy_prod
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `parameter used as bare elseif condition - not flagged`() {
        val yaml = """
            parameters:
              - name: env
                type: string
                default: dev

            variables:
              ${'$'}{{ if eq(parameters.env, 'prod') }}:
                tier: production
              ${'$'}{{ elseif parameters.env }}:
                tier: staging
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `parameter used inside nested if conditions - not flagged`() {
        // Mirrors the exact scenario from the bug report:
        // variables:
        //   ${{ if ... }}:
        //     ${{ if ... }}:
        //       ${{ if parameters.isProd }}:
        //         myVariable: deploy_prod
        val yaml = """
            parameters:
              - name: isProd
                type: boolean
                default: false

            variables:
              ${'$'}{{ if ne(variables['Build.Reason'], 'PullRequest') }}:
                ${'$'}{{ if eq(variables['System.TeamProject'], 'MyProject') }}:
                  ${'$'}{{ if parameters.isProd }}:
                    myVariable: deploy_prod
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `parameter used only as bare if condition key - not flagged`() {
        // Ensure the parameter is detected even when it appears only as a
        // mapping key in the form  ${{ if parameters.name }}:
        val yaml = """
            parameters:
              - name: deployToProduction
                type: boolean
                default: false

            stages:
              - stage: Deploy
                jobs:
                  - job: DeployJob
                    steps:
                      - ${'$'}{{ if parameters.deployToProduction }}:
                        - script: echo deploying to production
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `truly unused parameter alongside conditionally-used one - only unused flagged`() {
        val yaml = """
            parameters:
              - name: isProd
                type: boolean
                default: false
              - name: reallyUnused
                type: string
                default: nope

            variables:
              ${'$'}{{ if parameters.isProd }}:
                myVariable: deploy_prod
        """
        val issues = check(yaml)
        assertEquals(1, issues.size)
        assertEquals("reallyUnused", issues[0].paramName)
    }

    // ── Bug 3: bare parameters object reference (convertToJson, coalesce, etc.) ─

    @Test
    fun `convertToJson(parameters) - all params considered used`() {
        // Bug 3: ${{ convertToJson(parameters) }} passes the entire parameters object.
        // No individual parameters.name reference exists, but all params are implicitly used.
        val yaml = """
            parameters:
              - name: config
                type: object
              - name: environment
                type: string

            steps:
              - script: |
                  echo '${'$'}{{ convertToJson(parameters) }}'
                displayName: 'Dump all parameters as JSON'
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `coalesce with bare parameters object - all params considered used`() {
        val yaml = """
            parameters:
              - name: overrides
                type: object
                default: {}

            steps:
              - script: echo ${'$'}{{ coalesce(parameters, '{}') }}
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `length(parameters) - all params considered used`() {
        val yaml = """
            parameters:
              - name: items
                type: object

            steps:
              - script: echo ${'$'}{{ length(parameters) }}
        """
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `replace wrapping parameters dot name - only that specific param detected as used`() {
        // ${{ replace(parameters.appName, '-', '_') }} — has a dot, so PARAM_REF_REGEX
        // matches 'appName' specifically.  Only 'appName' is used; 'other' is unused.
        val yaml = """
            parameters:
              - name: appName
                type: string
              - name: other
                type: string
                default: nope

            steps:
              - script: echo ${'$'}{{ replace(parameters.appName, '-', '_') }}
        """
        val issues = check(yaml)
        assertEquals(1, issues.size)
        assertEquals("other", issues[0].paramName)
    }

    @Test
    fun `coalesce wrapping parameters dot name - only that specific param detected as used`() {
        // ${{ coalesce(parameters.slot, 'production') }} — has a dot, so only
        // 'slot' is detected as used; 'other' remains unused.
        val yaml = """
            parameters:
              - name: slot
                type: string
                default: ''
              - name: other
                type: string
                default: nope

            steps:
              - script: echo ${'$'}{{ coalesce(parameters.slot, 'production') }}
        """
        val issues = check(yaml)
        assertEquals(1, issues.size)
        assertEquals("other", issues[0].paramName)
    }

    @Test
    fun `bare parameters reference alongside named refs - all params considered used`() {
        // If convertToJson(parameters) appears, ALL params are considered used —
        // even those that would otherwise be flagged.
        val yaml = """
            parameters:
              - name: config
                type: object
              - name: neverUsedDirectly
                type: string
                default: hello

            steps:
              - script: echo ${'$'}{{ convertToJson(parameters) }}
              - script: echo ${'$'}{{ parameters.config }}
        """
        // convertToJson(parameters) makes all params "used" — nothing flagged.
        assertTrue(check(yaml).isEmpty())
    }

    @Test
    fun `word parameters in plain string or comment does not trigger bare match`() {
        // The word "parameters" appearing in a plain YAML string value or comment
        // must not suppress unused-parameter detection.
        val yaml = """
            parameters:
              - name: used
                type: string
              - name: unused
                type: string
                default: nope

            steps:
              - script: echo ${'$'}{{ parameters.used }}
                displayName: 'This step uses parameters'
                # parameters are important
        """
        // "parameters" in displayName and comment — but no bare ${{ parameters }} expression.
        // 'unused' should still be flagged.
        val issues = check(yaml)
        assertEquals(1, issues.size)
        assertEquals("unused", issues[0].paramName)
    }
}

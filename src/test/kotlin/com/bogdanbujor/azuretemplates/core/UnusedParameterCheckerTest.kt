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
}

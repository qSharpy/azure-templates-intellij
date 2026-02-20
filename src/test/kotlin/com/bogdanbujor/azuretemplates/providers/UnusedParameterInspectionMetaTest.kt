package com.bogdanbujor.azuretemplates.providers

import com.bogdanbujor.azuretemplates.core.UnusedParameterChecker
import org.junit.Assert.*
import org.junit.Test

/**
 * Lightweight tests for [UnusedParameterInspection] metadata and the
 * end-to-end path from raw YAML → [UnusedParameterChecker] → issue list.
 *
 * These tests do **not** require the IntelliJ platform to be bootstrapped;
 * they verify:
 *  1. The inspection's display name, group name, and short name are correct
 *     (these values must match the `plugin.xml` registration).
 *  2. The checker that backs the inspection produces the expected findings for
 *     representative YAML snippets — confirming the wiring is correct.
 */
class UnusedParameterInspectionMetaTest {

    private val inspection = UnusedParameterInspection()

    // ── metadata ──────────────────────────────────────────────────────────────

    @Test
    fun `display name is correct`() {
        assertEquals("Unused template parameter declaration", inspection.displayName)
    }

    @Test
    fun `group display name is correct`() {
        assertEquals("Azure Templates Navigator", inspection.groupDisplayName)
    }

    @Test
    fun `short name matches plugin xml registration`() {
        assertEquals("AzureTemplatesUnusedParameter", inspection.shortName)
    }

    // ── checker integration (no platform needed) ──────────────────────────────

    @Test
    fun `checker finds no issues for fully-used template`() {
        val yaml = """
            parameters:
              - name: environment
                type: string
              - name: appName
                type: string

            steps:
              - script: echo deploying ${'$'}{{ parameters.appName }} to ${'$'}{{ parameters.environment }}
        """.trimIndent()

        val issues = UnusedParameterChecker.check(yaml)
        assertTrue("Expected no issues for fully-used template", issues.isEmpty())
    }

    @Test
    fun `checker finds one issue for template with one unused parameter`() {
        val yaml = """
            parameters:
              - name: environment
                type: string
              - name: legacyFlag
                type: boolean
                default: false

            steps:
              - script: echo ${'$'}{{ parameters.environment }}
        """.trimIndent()

        val issues = UnusedParameterChecker.check(yaml)
        assertEquals(1, issues.size)
        assertEquals("legacyFlag", issues[0].paramName)
    }

    @Test
    fun `checker finds two issues for notify-teams style template`() {
        // Mirrors samples/templates/notify-teams.yml which has includeRunLink
        // and severity declared but never referenced in the body.
        val yaml = """
            parameters:
              - name: webhookUrl
                type: string
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
                inputs:
                  script: |
                    Invoke-RestMethod -Uri "${'$'}{{ parameters.webhookUrl }}" -Body "${'$'}{{ parameters.message }}" `
                      -Headers @{ color = "${'$'}{{ parameters.themeColor }}"; title = "${'$'}{{ parameters.title }}" }
        """.trimIndent()

        val issues = UnusedParameterChecker.check(yaml)
        val names = issues.map { it.paramName }.toSet()
        assertEquals(
            "Expected exactly includeRunLink and severity to be flagged",
            setOf("includeRunLink", "severity"),
            names
        )
    }

    @Test
    fun `checker handles template with no parameters block`() {
        val yaml = """
            trigger: none
            steps:
              - script: echo hello
        """.trimIndent()

        assertTrue(UnusedParameterChecker.check(yaml).isEmpty())
    }
}

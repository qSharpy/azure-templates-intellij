package com.bogdanbujor.azuretemplates.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [PassedParameterParser].
 *
 * Covers both the straightforward case (flat parameters block) and the
 * conditional-expression cases where parameters are nested inside
 * `${{ if }}` / `${{ elseif }}` / `${{ else }}` blocks.
 */
class PassedParameterParserTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Wraps [yaml] in a minimal pipeline document with a `- template:` line
     * at indent 2 (as it would appear inside a `steps:` or `jobs:` block),
     * then delegates to [PassedParameterParser.parse].
     *
     * The template line is always line 0 of the supplied [yaml] after trimming.
     */
    private fun parse(yaml: String): Map<String, Pair<String, Int>> {
        val lines = yaml.trimIndent().lines()
        // Find the "- template:" line index
        val templateLine = lines.indexOfFirst { it.trimStart().startsWith("- template:") }
        require(templateLine >= 0) { "No '- template:' line found in test YAML" }
        return PassedParameterParser.parse(lines, templateLine)
    }

    // ── flat parameters block (no conditionals) ───────────────────────────────

    @Test
    fun `flat parameters block - all entries collected`() {
        val yaml = """
            - template: /templates/build.yml
              parameters:
                project: MyApp
                configuration: Release
                runTests: true
        """
        val result = parse(yaml)
        assertEquals(3, result.size)
        assertEquals("MyApp", result["project"]?.first)
        assertEquals("Release", result["configuration"]?.first)
        assertEquals("true", result["runTests"]?.first)
    }

    @Test
    fun `empty parameters block - returns empty map`() {
        val yaml = """
            - template: /templates/build.yml
              parameters:
        """
        val result = parse(yaml)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `no parameters block - returns empty map`() {
        val yaml = """
            - template: /templates/build.yml
        """
        val result = parse(yaml)
        assertTrue(result.isEmpty())
    }

    // ── parameters inside ${{ if }} blocks ────────────────────────────────────

    @Test
    fun `parameter inside single if block is collected`() {
        // Bug 1: missingParam was previously not collected because it sits
        // deeper than childIndent (which was set to the ${{ if }}:  line).
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                ${'$'}{{ if ne(variables['Build.Reason'], 'PullRequest') }}:
                  environment: production
        """
        val result = parse(yaml)
        assertEquals(1, result.size)
        assertEquals("production", result["environment"]?.first)
    }

    @Test
    fun `parameters in if and else blocks are both collected`() {
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                ${'$'}{{ if eq(variables['Build.Reason'], 'PullRequest') }}:
                  environment: staging
                ${'$'}{{ else }}:
                  environment: production
        """
        // Both branches declare 'environment'; only the first occurrence is kept.
        val result = parse(yaml)
        assertEquals(1, result.size)
        assertEquals("staging", result["environment"]?.first)
    }

    @Test
    fun `parameters in if and elseif blocks are both collected`() {
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                ${'$'}{{ if eq(variables['env'], 'prod') }}:
                  region: eastus
                ${'$'}{{ elseif eq(variables['env'], 'staging') }}:
                  region: westus
        """
        // Both branches declare 'region'; only the first occurrence is kept.
        val result = parse(yaml)
        assertEquals(1, result.size)
        assertEquals("eastus", result["region"]?.first)
    }

    @Test
    fun `mix of conditional and unconditional parameters are all collected`() {
        val yaml = """
            - template: /templates/build.yml
              parameters:
                project: MyApp
                ${'$'}{{ if eq(variables['Build.Reason'], 'PullRequest') }}:
                  runTests: true
                configuration: Release
        """
        val result = parse(yaml)
        assertEquals(3, result.size)
        assertEquals("MyApp", result["project"]?.first)
        assertEquals("true", result["runTests"]?.first)
        assertEquals("Release", result["configuration"]?.first)
    }

    @Test
    fun `multiple parameters inside one if block are all collected`() {
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                ${'$'}{{ if ne(variables['Build.Reason'], 'PullRequest') }}:
                  environment: production
                  region: eastus
                  replicas: 3
        """
        val result = parse(yaml)
        assertEquals(3, result.size)
        assertEquals("production", result["environment"]?.first)
        assertEquals("eastus", result["region"]?.first)
        assertEquals("3", result["replicas"]?.first)
    }

    @Test
    fun `stops at next sibling template line`() {
        val yaml = """
            - template: /templates/build.yml
              parameters:
                project: MyApp
            - template: /templates/test.yml
              parameters:
                suite: unit
        """
        val result = parse(yaml)
        // Only the first template's parameters should be collected
        assertEquals(1, result.size)
        assertEquals("MyApp", result["project"]?.first)
        assertNull(result["suite"])
    }

    @Test
    fun `parameter line number is recorded correctly for flat block`() {
        val yaml = """
            - template: /templates/build.yml
              parameters:
                project: MyApp
        """
        val lines = yaml.trimIndent().lines()
        val templateLine = lines.indexOfFirst { it.trimStart().startsWith("- template:") }
        val result = PassedParameterParser.parse(lines, templateLine)
        // "project: MyApp" is at line index 2 (0-based)
        assertEquals(2, result["project"]?.second)
    }

    @Test
    fun `parameter line number is recorded correctly inside if block`() {
        val yaml = """
            - template: /templates/build.yml
              parameters:
                ${'$'}{{ if eq(variables['env'], 'prod') }}:
                  environment: production
        """
        val lines = yaml.trimIndent().lines()
        val templateLine = lines.indexOfFirst { it.trimStart().startsWith("- template:") }
        val result = PassedParameterParser.parse(lines, templateLine)
        // "  environment: production" is at line index 3 (0-based)
        assertEquals(3, result["environment"]?.second)
    }

    // ── object-valued parameters (Bug 4) ─────────────────────────────────────

    @Test
    fun `object-valued parameter is collected but its nested keys are not`() {
        // Bug 4: replicas, strategy, healthCheckPath are properties of deployConfig,
        // not top-level parameters.  They must NOT appear in the result.
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                appName: myapp
                deployConfig:
                  replicas: 3
                  strategy: RollingUpdate
                  healthCheckPath: /health
                environment: production
        """
        val result = parse(yaml)
        assertEquals(3, result.size)
        assertEquals("myapp", result["appName"]?.first)
        assertEquals("", result["deployConfig"]?.first)
        assertEquals("production", result["environment"]?.first)
        assertNull(result["replicas"])
        assertNull(result["strategy"])
        assertNull(result["healthCheckPath"])
    }

    @Test
    fun `two consecutive object-valued parameters - nested keys of both are skipped`() {
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                deployConfig:
                  replicas: 3
                  strategy: RollingUpdate
                tags:
                  team: platform
                  env: production
        """
        val result = parse(yaml)
        assertEquals(2, result.size)
        assertEquals("", result["deployConfig"]?.first)
        assertEquals("", result["tags"]?.first)
        assertNull(result["replicas"])
        assertNull(result["strategy"])
        assertNull(result["team"])
        assertNull(result["env"])
    }

    @Test
    fun `object-valued parameter inside if block - nested keys are skipped`() {
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                appName: myapp
                ${'$'}{{ if eq(variables['env'], 'prod') }}:
                  deployConfig:
                    replicas: 3
                    strategy: RollingUpdate
                  region: eastus
        """
        val result = parse(yaml)
        assertEquals(3, result.size)
        assertEquals("myapp", result["appName"]?.first)
        assertEquals("", result["deployConfig"]?.first)
        assertEquals("eastus", result["region"]?.first)
        assertNull(result["replicas"])
        assertNull(result["strategy"])
    }

    @Test
    fun `mix of scalar and object params with conditional blocks`() {
        // Comprehensive scenario: unconditional scalar, conditional object, unconditional scalar
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                appName: myapp
                ${'$'}{{ if eq(variables['env'], 'prod') }}:
                  environment: production
                ${'$'}{{ else }}:
                  environment: staging
                deployConfig:
                  replicas: 3
                  strategy: RollingUpdate
                imageTag: latest
        """
        val result = parse(yaml)
        assertEquals(4, result.size)
        assertEquals("myapp", result["appName"]?.first)
        assertEquals("production", result["environment"]?.first) // first occurrence wins
        assertEquals("", result["deployConfig"]?.first)
        assertEquals("latest", result["imageTag"]?.first)
        assertNull(result["replicas"])
        assertNull(result["strategy"])
    }

    @Test
    fun `deeply nested object value - all nested lines are skipped`() {
        val yaml = """
            - template: /templates/deploy.yml
              parameters:
                config:
                  level1:
                    level2:
                      level3: deep
                appName: myapp
        """
        val result = parse(yaml)
        assertEquals(2, result.size)
        assertEquals("", result["config"]?.first)
        assertEquals("myapp", result["appName"]?.first)
        assertNull(result["level1"])
        assertNull(result["level2"])
        assertNull(result["level3"])
    }
}

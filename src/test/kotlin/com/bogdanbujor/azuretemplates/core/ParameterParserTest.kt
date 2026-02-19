package com.bogdanbujor.azuretemplates.core

import org.junit.Assert.*
import org.junit.Test

class ParameterParserTest {

    @Test
    fun `parses parameters with types and defaults`() {
        val yaml = """
            parameters:
              - name: environment
                type: string
              - name: vmImage
                type: string
                default: 'ubuntu-latest'
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(2, params.size)
        assertEquals("environment", params[0].name)
        assertEquals("string", params[0].type)
        assertTrue(params[0].required)
        assertNull(params[0].default)

        assertEquals("vmImage", params[1].name)
        assertEquals("string", params[1].type)
        assertFalse(params[1].required)
        assertEquals("'ubuntu-latest'", params[1].default)
    }

    @Test
    fun `parses parameters with no type specified`() {
        val yaml = """
            parameters:
              - name: myParam
                default: 'hello'
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(1, params.size)
        assertEquals("myParam", params[0].name)
        assertEquals("string", params[0].type)
        assertFalse(params[0].required)
    }

    @Test
    fun `handles empty parameters block`() {
        val yaml = """
            parameters:
            
            trigger: none
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(0, params.size)
    }

    @Test
    fun `handles file with no parameters`() {
        val yaml = """
            trigger: none
            pool:
              vmImage: 'ubuntu-latest'
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(0, params.size)
    }

    @Test
    fun `handles comments inside parameters block`() {
        val yaml = """
            parameters:
            # This is a comment
              - name: env
                type: string
              # Another comment
              - name: region
                type: string
                default: 'eastus'
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(2, params.size)
        assertEquals("env", params[0].name)
        assertTrue(params[0].required)
        assertEquals("region", params[1].name)
        assertFalse(params[1].required)
    }

    @Test
    fun `strips inline comments from values`() {
        val yaml = """
            parameters:
              - name: buildConfig
                type: string
                default: Release # This is the default
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(1, params.size)
        assertEquals("Release", params[0].default)
    }

    @Test
    fun `handles boolean type parameters`() {
        val yaml = """
            parameters:
              - name: runTests
                type: boolean
                default: true
              - name: deploy
                type: boolean
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(2, params.size)
        assertEquals("boolean", params[0].type)
        assertFalse(params[0].required)
        assertEquals("true", params[0].default)
        assertTrue(params[1].required)
    }

    @Test
    fun `handles CRLF line endings`() {
        val yaml = "parameters:\r\n  - name: env\r\n    type: string\r\n  - name: region\r\n    type: string\r\n    default: 'eastus'\r\n"

        val params = ParameterParser.parse(yaml)
        assertEquals(2, params.size)
        assertEquals("env", params[0].name)
        assertTrue(params[0].required)
        assertEquals("region", params[1].name)
        assertFalse(params[1].required)
    }

    @Test
    fun `handles parameters at column 0`() {
        val yaml = """
            parameters:
            - name: env
              type: string
            - name: region
              type: string
              default: 'eastus'
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(2, params.size)
    }

    @Test
    fun `stops at next top-level key`() {
        val yaml = """
            parameters:
              - name: env
                type: string
            stages:
              - stage: Build
        """.trimIndent()

        val params = ParameterParser.parse(yaml)
        assertEquals(1, params.size)
        assertEquals("env", params[0].name)
    }
}

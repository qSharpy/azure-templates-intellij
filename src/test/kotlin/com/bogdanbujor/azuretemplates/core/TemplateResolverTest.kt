package com.bogdanbujor.azuretemplates.core

import org.junit.Assert.*
import org.junit.Test

class TemplateResolverTest {

    @Test
    fun `extracts template ref from simple line`() {
        val ref = TemplateResolver.extractTemplateRef("  - template: templates/build.yml")
        assertEquals("templates/build.yml", ref)
    }

    @Test
    fun `extracts template ref with alias`() {
        val ref = TemplateResolver.extractTemplateRef("  - template: templates/build.yml@templates")
        assertEquals("templates/build.yml@templates", ref)
    }

    @Test
    fun `returns null for non-template line`() {
        val ref = TemplateResolver.extractTemplateRef("  - stage: Build")
        assertNull(ref)
    }

    @Test
    fun `returns null for comment line`() {
        val ref = TemplateResolver.extractTemplateRef("  # template: templates/build.yml")
        assertNull(ref)
    }

    @Test
    fun `resolves relative path`() {
        val resolved = TemplateResolver.resolve(
            "templates/build.yml",
            "/repo/pipelines/main.yml",
            emptyMap()
        )
        assertNotNull(resolved)
        assertEquals("/repo/pipelines/templates/build.yml", resolved!!.filePath)
        assertNull(resolved.repoName)
        assertFalse(resolved.unknownAlias)
    }

    @Test
    fun `resolves self alias as local`() {
        val resolved = TemplateResolver.resolve(
            "templates/build.yml@self",
            "/repo/pipelines/main.yml",
            emptyMap()
        )
        assertNotNull(resolved)
        assertNull(resolved!!.repoName)
        assertFalse(resolved.unknownAlias)
    }

    @Test
    fun `returns unknown alias for unresolved alias`() {
        val resolved = TemplateResolver.resolve(
            "templates/build.yml@unknown",
            "/repo/pipelines/main.yml",
            emptyMap()
        )
        assertNotNull(resolved)
        assertTrue(resolved!!.unknownAlias)
        assertEquals("unknown", resolved.alias)
    }

    @Test
    fun `returns null for empty ref`() {
        val resolved = TemplateResolver.resolve("", "/repo/main.yml", emptyMap())
        assertNull(resolved)
    }

    @Test
    fun `findOwningTemplateLine finds template line`() {
        val lines = listOf(
            "stages:",
            "  - stage: Build",
            "    jobs:",
            "      - template: templates/build.yml",
            "        parameters:",
            "          project: 'src/MyApp.csproj'",
            "          config: Release"
        )

        val result = TemplateResolver.findOwningTemplateLine(lines, 5)
        assertEquals(3, result)
    }

    @Test
    fun `findOwningTemplateLine returns -1 when not in template block`() {
        val lines = listOf(
            "stages:",
            "  - stage: Build",
            "    pool:",
            "      vmImage: 'ubuntu-latest'"
        )

        val result = TemplateResolver.findOwningTemplateLine(lines, 3)
        assertEquals(-1, result)
    }
}

package com.bogdanbujor.azuretemplates.core

import org.junit.Assert.*
import org.junit.Test

class GraphBuilderTest {

    @Test
    fun `isPipelineRoot detects trigger keyword`() {
        assertTrue(GraphBuilder.isPipelineRoot("trigger: none\npool:\n  vmImage: 'ubuntu-latest'"))
    }

    @Test
    fun `isPipelineRoot detects pr keyword`() {
        assertTrue(GraphBuilder.isPipelineRoot("pr:\n  branches:\n    include:\n      - main"))
    }

    @Test
    fun `isPipelineRoot detects stages keyword`() {
        assertTrue(GraphBuilder.isPipelineRoot("stages:\n  - stage: Build"))
    }

    @Test
    fun `isPipelineRoot returns false for template file`() {
        assertFalse(GraphBuilder.isPipelineRoot("parameters:\n  - name: env\n    type: string"))
    }

    @Test
    fun `extractTemplateRefs finds template references`() {
        val tempFile = java.io.File.createTempFile("test", ".yml")
        tempFile.deleteOnExit()
        tempFile.writeText("""
            stages:
              - stage: Build
                jobs:
                  - template: templates/build.yml
                    parameters:
                      project: 'src/app.csproj'
              - stage: Deploy
                jobs:
                  - template: templates/deploy.yml@templates
        """.trimIndent())

        val refs = GraphBuilder.extractTemplateRefs(tempFile.absolutePath)
        assertEquals(2, refs.size)
        assertEquals("templates/build.yml", refs[0].templateRef)
        assertEquals("templates/deploy.yml@templates", refs[1].templateRef)
    }

    @Test
    fun `extractTemplateRefs skips comment lines`() {
        val tempFile = java.io.File.createTempFile("test", ".yml")
        tempFile.deleteOnExit()
        tempFile.writeText("""
            # template: this/is/a/comment.yml
            stages:
              - stage: Build
                jobs:
                  - template: templates/build.yml
        """.trimIndent())

        val refs = GraphBuilder.extractTemplateRefs(tempFile.absolutePath)
        assertEquals(1, refs.size)
        assertEquals("templates/build.yml", refs[0].templateRef)
    }
}

package com.bogdanbujor.azuretemplates.core

import org.junit.Assert.*
import org.junit.Test

class CallSiteValidatorTest {

    @Test
    fun `inferValueType detects boolean`() {
        assertEquals("boolean", CallSiteValidator.inferValueType("true"))
        assertEquals("boolean", CallSiteValidator.inferValueType("false"))
        assertEquals("boolean", CallSiteValidator.inferValueType("yes"))
        assertEquals("boolean", CallSiteValidator.inferValueType("no"))
        assertEquals("boolean", CallSiteValidator.inferValueType("on"))
        assertEquals("boolean", CallSiteValidator.inferValueType("off"))
        assertEquals("boolean", CallSiteValidator.inferValueType("TRUE"))
        assertEquals("boolean", CallSiteValidator.inferValueType("False"))
    }

    @Test
    fun `inferValueType detects number`() {
        assertEquals("number", CallSiteValidator.inferValueType("42"))
        assertEquals("number", CallSiteValidator.inferValueType("-1"))
        assertEquals("number", CallSiteValidator.inferValueType("3.14"))
        assertEquals("number", CallSiteValidator.inferValueType("-0.5"))
    }

    @Test
    fun `inferValueType detects object`() {
        assertEquals("object", CallSiteValidator.inferValueType("[1, 2, 3]"))
        assertEquals("object", CallSiteValidator.inferValueType("{key: value}"))
    }

    @Test
    fun `inferValueType detects string`() {
        assertEquals("string", CallSiteValidator.inferValueType("hello"))
        assertEquals("string", CallSiteValidator.inferValueType("'quoted'"))
        assertEquals("string", CallSiteValidator.inferValueType("\"double-quoted\""))
        assertEquals("string", CallSiteValidator.inferValueType(""))
    }

    @Test
    fun `inferValueType treats expressions as string`() {
        assertEquals("string", CallSiteValidator.inferValueType("\$(Build.BuildId)"))
        assertEquals("string", CallSiteValidator.inferValueType("\${{ variables.foo }}"))
    }
}

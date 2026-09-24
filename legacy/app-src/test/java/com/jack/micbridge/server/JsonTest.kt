package com.jack.micbridge.server

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test
    fun `escapes untrusted strings`() {
        assertEquals(
            "{\"message\":\"quote\\\" slash\\\\ line\\n\"}",
            Json.objectOf("message" to "quote\" slash\\ line\n"),
        )
    }
}


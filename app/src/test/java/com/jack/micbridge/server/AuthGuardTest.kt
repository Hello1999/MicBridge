package com.jack.micbridge.server

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGuardTest {
    @Test
    fun `token contains 256 random bits in base64url form`() {
        val first = AuthGuard.generateToken()
        val second = AuthGuard.generateToken()

        assertEquals(AuthGuard.TOKEN_BYTES, Base64.getUrlDecoder().decode(first).size)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first))
        assertNotEquals(first, second)
    }

    @Test
    fun `authentication rejects missing and wrong token`() {
        assertTrue(AuthGuard.matches("expected", "expected"))
        assertFalse(AuthGuard.matches("expected", "wrong"))
        assertFalse(AuthGuard.matches("expected", null))
    }

    @Test
    fun `newly rotated token rejects old credential`() {
        val oldToken = AuthGuard.generateToken()
        val currentToken = AuthGuard.generateToken()

        assertFalse(AuthGuard.matches(currentToken, oldToken))
        assertTrue(AuthGuard.matches(currentToken, currentToken))
    }
}

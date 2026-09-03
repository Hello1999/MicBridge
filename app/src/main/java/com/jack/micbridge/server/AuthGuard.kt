package com.jack.micbridge.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object AuthGuard {
    fun generateToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun matches(expected: String, candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )
    }

    const val TOKEN_BYTES = 32
}


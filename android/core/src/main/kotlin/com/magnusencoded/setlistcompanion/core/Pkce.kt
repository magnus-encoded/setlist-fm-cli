package com.magnusencoded.setlistcompanion.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) helpers for the Spotify Authorization Code flow. A mobile
 * app cannot keep a client secret, so authorization is proven with a
 * per-request code verifier/challenge pair instead.
 */
object Pkce {
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generateCodeVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return urlEncoder.encodeToString(digest)
    }

    fun generateState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }
}

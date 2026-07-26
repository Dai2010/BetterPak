package com.dai2010.betterpak.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class CloudOAuthConfig(
    val provider: CloudProviderId,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    val scopes: List<String>,
) {
    init {
        require(clientId.isNotBlank()) { "OAuth client ID 未配置" }
        require(authorizationEndpoint.startsWith("https://")) { "OAuth 授权地址必须使用 HTTPS" }
        require(tokenEndpoint.startsWith("https://")) { "OAuth Token 地址必须使用 HTTPS" }
        require(redirectUri.isNotBlank()) { "OAuth 回调地址未配置" }
        require(scopes.isNotEmpty()) { "OAuth 权限范围不能为空" }
    }
}

class CloudOAuthSession internal constructor(
    val state: String,
    val codeVerifier: String,
    val codeChallenge: String,
) {
    override fun toString(): String = "CloudOAuthSession(redacted)"
}

object CloudOAuth {
    fun createSession(random: SecureRandom = SecureRandom()): CloudOAuthSession {
        val verifier = randomToken(random)
        return CloudOAuthSession(
            state = randomToken(random),
            codeVerifier = verifier,
            codeChallenge = sha256Base64Url(verifier),
        )
    }

    fun validateCallback(session: CloudOAuthSession, returnedState: String?, code: String): Boolean =
        returnedState != null &&
            constantTimeEquals(session.state, returnedState) &&
            code.isNotBlank()

    private fun randomToken(random: SecureRandom): String = ByteArray(32)
        .also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun sha256Base64Url(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.US_ASCII))
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun constantTimeEquals(first: String, second: String): Boolean {
        if (first.length != second.length) return false
        var difference = 0
        first.indices.forEach { index ->
            difference = difference or (first[index].code xor second[index].code)
        }
        return difference == 0
    }
}

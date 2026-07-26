package com.dai2010.betterpak.data

import android.net.Uri
import com.dai2010.betterpak.domain.CloudAccessToken
import com.dai2010.betterpak.domain.CloudErrorClassifier
import com.dai2010.betterpak.domain.CloudErrorCode
import com.dai2010.betterpak.domain.CloudOAuthConfig
import com.dai2010.betterpak.domain.CloudOAuth
import com.dai2010.betterpak.domain.CloudOAuthSession
import com.dai2010.betterpak.domain.CloudOperationException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CloudOAuthClient {
    fun authorizationUri(config: CloudOAuthConfig, session: CloudOAuthSession): Uri =
        Uri.parse(config.authorizationEndpoint).buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("scope", config.scopes.joinToString(" "))
            .appendQueryParameter("state", session.state)
            .appendQueryParameter("code_challenge", session.codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

    suspend fun exchangeCode(
        config: CloudOAuthConfig,
        session: CloudOAuthSession,
        returnedState: String?,
        code: String,
    ): Result<CloudAccessToken> {
        if (!CloudOAuth.validateCallback(session, returnedState, code)) {
            return Result.failure(
                CloudOperationException(CloudErrorCode.AUTH_REQUIRED, "OAuth 回调校验失败"),
            )
        }
        return requestToken(
            config = config,
            parameters = mapOf(
                "grant_type" to "authorization_code",
                "client_id" to config.clientId,
                "code" to code,
                "redirect_uri" to config.redirectUri,
                "code_verifier" to session.codeVerifier,
            ),
        )
    }

    suspend fun refresh(
        config: CloudOAuthConfig,
        refreshToken: String,
    ): Result<CloudAccessToken> = requestToken(
        config = config,
        parameters = mapOf(
            "grant_type" to "refresh_token",
            "client_id" to config.clientId,
            "refresh_token" to refreshToken,
        ),
        previousRefreshToken = refreshToken,
    )

    private suspend fun requestToken(
        config: CloudOAuthConfig,
        parameters: Map<String, String>,
        previousRefreshToken: String? = null,
    ): Result<CloudAccessToken> = try {
        Result.success(withContext(Dispatchers.IO) {
            val body = parameters.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            val connection = URL(config.tokenEndpoint).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TOKEN_CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = TOKEN_READ_TIMEOUT_MILLIS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    throw CloudOperationException(
                        code = when (statusCode) {
                            400, 401 -> CloudErrorCode.AUTH_REQUIRED
                            else -> CloudErrorClassifier.fromHttpStatus(statusCode)
                        },
                        message = "OAuth Token 请求失败",
                    )
                }
                val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                val accessToken = json.optString("access_token", "")
                require(accessToken.isNotBlank()) { "OAuth 响应缺少访问令牌" }
                val expiresIn = json.optLong("expires_in", DEFAULT_TOKEN_LIFETIME_SECONDS)
                val tokenLifetimeMillis = (
                    expiresIn.coerceAtLeast(MIN_TOKEN_LIFETIME_SECONDS) * 1000L - TOKEN_REFRESH_SKEW_MILLIS
                    ).coerceAtLeast(MIN_REFRESHABLE_LIFETIME_MILLIS)
                CloudAccessToken(
                    value = accessToken,
                    expiresAtEpochMillis = System.currentTimeMillis() + tokenLifetimeMillis,
                    refreshToken = json.optString("refresh_token", null) ?: previousRefreshToken,
                )
            } finally {
                connection.disconnect()
            }
        })
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(CloudErrorClassifier.wrap(error))
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val TOKEN_CONNECT_TIMEOUT_MILLIS = 15_000
        const val TOKEN_READ_TIMEOUT_MILLIS = 30_000
        const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
        const val MIN_TOKEN_LIFETIME_SECONDS = 60L
        const val TOKEN_REFRESH_SKEW_MILLIS = 60_000L
        const val MIN_REFRESHABLE_LIFETIME_MILLIS = 60_000L
    }
}

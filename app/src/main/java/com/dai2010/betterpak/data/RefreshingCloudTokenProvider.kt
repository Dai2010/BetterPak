package com.dai2010.betterpak.data

import com.dai2010.betterpak.domain.CloudAccessToken
import com.dai2010.betterpak.domain.CloudErrorClassifier
import com.dai2010.betterpak.domain.CloudErrorCode
import com.dai2010.betterpak.domain.CloudOAuthConfig
import com.dai2010.betterpak.domain.CloudOperationException
import com.dai2010.betterpak.domain.CloudProviderId
import com.dai2010.betterpak.domain.CloudTokenProvider
import kotlinx.coroutines.CancellationException

class RefreshingCloudTokenProvider(
    private val tokenStore: CloudTokenStore,
    private val oauthClient: CloudOAuthClient,
    private val configs: Map<CloudProviderId, CloudOAuthConfig>,
) : CloudTokenProvider {
    override suspend fun accessToken(accountId: String): Result<CloudAccessToken> = try {
        val token = tokenStore.storedToken(accountId)
            ?: throw CloudOperationException(CloudErrorCode.AUTH_REQUIRED, "云端账号未授权")
        if (!token.isExpired()) {
            Result.success(token)
        } else {
            val refreshToken = token.refreshToken
                ?: throw CloudOperationException(CloudErrorCode.AUTH_EXPIRED, "云端授权已过期")
            val provider = tokenStore.providerId(accountId)
                ?: throw CloudOperationException(CloudErrorCode.AUTH_EXPIRED, "云端账号信息已失效")
            val config = configs[provider]
                ?: throw CloudOperationException(CloudErrorCode.AUTH_REQUIRED, "云端 OAuth 未配置")
            val refreshed = oauthClient.refresh(config, refreshToken).getOrThrow()
            val stored = refreshed.copyWithRefreshToken(refreshToken)
            tokenStore.save(accountId, stored)
            Result.success(stored)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(CloudErrorClassifier.wrap(error))
    }

    private fun CloudAccessToken.copyWithRefreshToken(fallback: String): CloudAccessToken =
        if (refreshToken == null) {
            CloudAccessToken(value, expiresAtEpochMillis, fallback)
        } else {
            this
        }
}

package com.dai2010.betterpak.data

import android.content.Context
import com.dai2010.betterpak.domain.CloudOAuthConfig
import com.dai2010.betterpak.domain.CloudProviderId

class CloudConfiguration(context: Context) {
    private val metadata = context.applicationContext.packageManager
        .getApplicationInfo(
            context.applicationContext.packageName,
            android.content.pm.PackageManager.GET_META_DATA,
        )
        .metaData

    fun config(provider: CloudProviderId): CloudOAuthConfig? {
        val clientId = metadata?.getString(clientIdKey(provider)).orEmpty().trim()
        if (clientId.isBlank()) return null
        return runCatching {
            CloudOAuthConfig(
                provider = provider,
                clientId = clientId,
                authorizationEndpoint = authorizationEndpoint(provider),
                tokenEndpoint = tokenEndpoint(provider),
                redirectUri = redirectUri(provider),
                scopes = configuredScopes(provider),
            )
        }.getOrNull()
    }

    fun configs(): Map<CloudProviderId, CloudOAuthConfig> =
        CloudProviderId.entries.mapNotNull { provider ->
            config(provider)?.let { provider to it }
        }.toMap()

    private fun clientIdKey(provider: CloudProviderId): String = when (provider) {
        CloudProviderId.ONEDRIVE -> "com.dai2010.betterpak.cloud.onedrive.client_id"
        CloudProviderId.GOOGLE_DRIVE -> "com.dai2010.betterpak.cloud.google_drive.client_id"
    }

    private fun authorizationEndpoint(provider: CloudProviderId): String = when (provider) {
        CloudProviderId.ONEDRIVE -> "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        CloudProviderId.GOOGLE_DRIVE -> "https://accounts.google.com/o/oauth2/v2/auth"
    }

    private fun tokenEndpoint(provider: CloudProviderId): String = when (provider) {
        CloudProviderId.ONEDRIVE -> "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        CloudProviderId.GOOGLE_DRIVE -> "https://oauth2.googleapis.com/token"
    }

    private fun redirectUri(provider: CloudProviderId): String = when (provider) {
        CloudProviderId.ONEDRIVE -> "com.dai2010.betterpak://oauth/onedrive"
        CloudProviderId.GOOGLE_DRIVE -> "com.dai2010.betterpak://oauth/google-drive"
    }

    private fun configuredScopes(provider: CloudProviderId): List<String> {
        val configured = metadata?.getString(scopeKey(provider)).orEmpty()
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        return configured.ifEmpty { defaultScopes(provider) }
    }

    private fun scopeKey(provider: CloudProviderId): String = when (provider) {
        CloudProviderId.ONEDRIVE -> "com.dai2010.betterpak.cloud.onedrive.scopes"
        CloudProviderId.GOOGLE_DRIVE -> "com.dai2010.betterpak.cloud.google_drive.scopes"
    }

    private fun defaultScopes(provider: CloudProviderId): List<String> = when (provider) {
        CloudProviderId.ONEDRIVE -> listOf("offline_access", "Files.ReadWrite")
        CloudProviderId.GOOGLE_DRIVE -> listOf(
            "openid",
            "email",
            "profile",
            "https://www.googleapis.com/auth/drive.file",
        )
    }
}

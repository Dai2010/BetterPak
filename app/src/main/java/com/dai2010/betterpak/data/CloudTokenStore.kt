package com.dai2010.betterpak.data

import android.content.Context
import android.util.Base64
import com.dai2010.betterpak.domain.CloudAccessToken
import com.dai2010.betterpak.domain.CloudErrorCode
import com.dai2010.betterpak.domain.CloudOperationException
import com.dai2010.betterpak.domain.CloudAccount
import com.dai2010.betterpak.domain.CloudProviderId
import com.dai2010.betterpak.domain.CloudTokenProvider
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class CloudTokenStore(context: Context) : CloudTokenProvider {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        ensureKey()
    }

    fun save(accountId: String, token: CloudAccessToken) {
        require(accountId.isNotBlank()) { "云账号标识不能为空" }
        require(token.value.isNotBlank()) { "访问令牌不能为空" }
        preferences.edit()
            .putString(tokenKey(accountId), encrypt(token))
            .apply()
    }

    fun save(account: CloudAccount, token: CloudAccessToken) {
        save(account.id, token)
        preferences.edit().putString(providerKey(account.id), account.provider.name).apply()
    }

    override suspend fun accessToken(accountId: String): Result<CloudAccessToken> = runCatching {
        val token = storedToken(accountId)
            ?: throw CloudOperationException(CloudErrorCode.AUTH_REQUIRED, "云端账号未授权")
        if (token.isExpired()) {
            throw CloudOperationException(CloudErrorCode.AUTH_EXPIRED, "云端授权已过期")
        }
        token
    }

    fun storedToken(accountId: String): CloudAccessToken? =
        preferences.getString(tokenKey(accountId), null)?.let { encoded ->
            runCatching { decrypt(encoded) }.getOrNull()
        }

    fun providerId(accountId: String): CloudProviderId? =
        preferences.getString(providerKey(accountId), null)?.let {
            runCatching { CloudProviderId.valueOf(it) }.getOrNull()
        }

    fun clear(accountId: String) {
        preferences.edit()
            .remove(tokenKey(accountId))
            .remove(providerKey(accountId))
            .apply()
    }

    fun clearAll() {
        preferences.edit()
            .apply {
                preferences.all.keys
                    .filter { it.startsWith(TOKEN_KEY_PREFIX) }
                    .forEach { key -> remove(key) }
                preferences.all.keys
                    .filter { it.startsWith(PROVIDER_KEY_PREFIX) }
                    .forEach { key -> remove(key) }
            }
            .apply()
    }

    private fun encrypt(token: CloudAccessToken): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = listOf(
            token.value,
            token.expiresAtEpochMillis.toString(),
            token.refreshToken.orEmpty(),
        ).joinToString(FIELD_SEPARATOR) { value ->
            Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }.toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(payload)
        return Base64.encodeToString(
            ByteBuffer.allocate(cipher.iv.size + encrypted.size)
                .put(cipher.iv)
                .put(encrypted)
                .array(),
            Base64.NO_WRAP,
        )
    }

    private fun decrypt(encoded: String): CloudAccessToken {
        val payload = Base64.decode(encoded, Base64.DEFAULT)
        require(payload.size > GCM_IV_LENGTH) { "云端凭据已损坏" }
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val fields = String(cipher.doFinal(encrypted), Charsets.UTF_8)
            .split(FIELD_SEPARATOR)
            .map { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }
        require(fields.size == TOKEN_FIELD_COUNT)
        return CloudAccessToken(
            value = fields[0],
            expiresAtEpochMillis = fields[1].toLong(),
            refreshToken = fields[2].takeIf(String::isNotEmpty),
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: error("无法读取云端凭据加密密钥")
    }

    private fun ensureKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private fun tokenKey(accountId: String): String =
        TOKEN_KEY_PREFIX + Base64.encodeToString(accountId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun providerKey(accountId: String): String =
        PROVIDER_KEY_PREFIX + Base64.encodeToString(accountId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "betterpak-cloud-token-key"
        const val PREFERENCES_NAME = "betterpak_cloud_credentials"
        const val TOKEN_KEY_PREFIX = "token_"
        const val PROVIDER_KEY_PREFIX = "provider_"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val FIELD_SEPARATOR = ":"
        const val TOKEN_FIELD_COUNT = 3
    }
}

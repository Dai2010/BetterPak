package com.dai2010.betterpak.data

import android.content.Context
import android.util.Base64
import com.dai2010.betterpak.domain.CloudAccount
import com.dai2010.betterpak.domain.CloudOAuthSession
import com.dai2010.betterpak.domain.CloudProviderId

data class PendingCloudAuthorization(
    val provider: CloudProviderId,
    val accountKey: String,
    val session: CloudOAuthSession,
)

class CloudAccountStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(account: CloudAccount) {
        val value = listOf(
            account.id,
            account.provider.name,
            account.displayName,
            account.email.orEmpty(),
        ).encodeFields()
        preferences.edit().putString(accountKey(account.id, account.provider), value).apply()
    }

    fun list(): List<CloudAccount> = preferences.all
        .filterKeys { it.startsWith(ACCOUNT_KEY_PREFIX) }
        .values
        .mapNotNull { (it as? String)?.let(::decodeAccount) }
        .sortedWith(compareBy({ it.provider.name }, { it.displayName }))

    fun remove(account: CloudAccount) {
        preferences.edit().remove(accountKey(account.id, account.provider)).apply()
    }

    fun savePending(provider: CloudProviderId, session: CloudOAuthSession): String {
        val accountKey = pendingAccountKey(provider)
        val value = listOf(session.state, session.codeVerifier, session.codeChallenge).encodeFields()
        preferences.edit()
            .putString(pendingKey(provider), value)
            .apply()
        return accountKey
    }

    fun pending(provider: CloudProviderId): PendingCloudAuthorization? =
        preferences.getString(pendingKey(provider), null)?.let { value ->
            runCatching {
                val fields = value.decodeFields()
                require(fields.size == PENDING_FIELD_COUNT)
                PendingCloudAuthorization(
                    provider = provider,
                    accountKey = pendingAccountKey(provider),
                    session = CloudOAuthSession(fields[0], fields[1], fields[2]),
                )
            }.getOrNull()
        }

    fun clearPending(provider: CloudProviderId) {
        preferences.edit().remove(pendingKey(provider)).apply()
    }

    private fun decodeAccount(value: String): CloudAccount? = runCatching {
        val fields = value.decodeFields()
        require(fields.size == ACCOUNT_FIELD_COUNT)
        CloudAccount(
            id = fields[0],
            provider = CloudProviderId.valueOf(fields[1]),
            displayName = fields[2],
            email = fields[3].takeIf(String::isNotBlank),
        )
    }.getOrNull()

    private fun accountKey(id: String, provider: CloudProviderId): String =
        ACCOUNT_KEY_PREFIX + encode("${provider.name}:$id")

    private fun pendingKey(provider: CloudProviderId): String = "$PENDING_KEY_PREFIX${provider.name}"

    private fun pendingAccountKey(provider: CloudProviderId): String =
        "$PENDING_ACCOUNT_KEY_PREFIX${provider.name}"

    private fun List<String>.encodeFields(): String = joinToString(FIELD_SEPARATOR) { encode(it) }

    private fun String.decodeFields(): List<String> = split(FIELD_SEPARATOR).map(::decode)

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)

    private companion object {
        const val PREFERENCES_NAME = "betterpak_cloud_accounts"
        const val ACCOUNT_KEY_PREFIX = "account_"
        const val PENDING_KEY_PREFIX = "pending_"
        const val PENDING_ACCOUNT_KEY_PREFIX = "pending-account-"
        const val FIELD_SEPARATOR = ":"
        const val ACCOUNT_FIELD_COUNT = 4
        const val PENDING_FIELD_COUNT = 3
    }
}

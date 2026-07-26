package com.dai2010.betterpak.domain

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class CloudProviderId {
    ONEDRIVE,
    GOOGLE_DRIVE,
}

data class CloudAccount(
    val id: String,
    val provider: CloudProviderId,
    val displayName: String,
    val email: String? = null,
)

data class CloudItem(
    val id: String,
    val name: String,
    val parentId: String?,
    val isDirectory: Boolean,
    val size: Long? = null,
    val modifiedAtEpochMillis: Long? = null,
    val mimeType: String? = null,
)

data class CloudPage(
    val items: List<CloudItem>,
    val nextPageToken: String? = null,
)

data class CloudTransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

object CloudTransferPolicy {
    const val MAX_LOCAL_DOWNLOAD_BYTES = 50L * 1024L * 1024L * 1024L
}

enum class CloudErrorCode(val retryable: Boolean) {
    AUTH_REQUIRED(false),
    AUTH_EXPIRED(true),
    ACCESS_DENIED(false),
    NOT_FOUND(false),
    NETWORK_UNAVAILABLE(true),
    TIMEOUT(true),
    QUOTA_EXCEEDED(true),
    LIMIT_EXCEEDED(false),
    INVALID_REQUEST(false),
    CONFLICT(true),
    CANCELLED(true),
    UNKNOWN(true),
}

class CloudOperationException(
    val code: CloudErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    override fun toString(): String = "CloudOperationException(code=$code)"
}

class CloudAccessToken internal constructor(
    val value: String,
    val expiresAtEpochMillis: Long,
    val refreshToken: String? = null,
) {
    fun isExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMillis <= nowEpochMillis

    override fun toString(): String = "CloudAccessToken(redacted)"
}

interface CloudTokenProvider {
    suspend fun accessToken(accountId: String): Result<CloudAccessToken>
}

interface CloudProvider {
    val providerId: CloudProviderId

    suspend fun currentAccount(accountKey: String): Result<CloudAccount>

    suspend fun listChildren(
        account: CloudAccount,
        parentId: String? = null,
        pageToken: String? = null,
    ): Result<CloudPage>

    suspend fun download(
        account: CloudAccount,
        item: CloudItem,
        destination: File,
        onProgress: suspend (CloudTransferProgress) -> Unit = {},
    ): Result<File>

    suspend fun upload(
        account: CloudAccount,
        parentId: String?,
        name: String,
        source: File,
        mimeType: String? = null,
        onProgress: suspend (CloudTransferProgress) -> Unit = {},
    ): Result<CloudItem>
}

object CloudErrorClassifier {
    fun fromHttpStatus(statusCode: Int): CloudErrorCode = when (statusCode) {
        401 -> CloudErrorCode.AUTH_EXPIRED
        403 -> CloudErrorCode.ACCESS_DENIED
        404 -> CloudErrorCode.NOT_FOUND
        408 -> CloudErrorCode.TIMEOUT
        409 -> CloudErrorCode.CONFLICT
        429 -> CloudErrorCode.QUOTA_EXCEEDED
        in 500..599 -> CloudErrorCode.NETWORK_UNAVAILABLE
        in 400..499 -> CloudErrorCode.INVALID_REQUEST
        else -> CloudErrorCode.UNKNOWN
    }

    fun classify(error: Throwable): CloudErrorCode {
        if (error is CloudOperationException) return error.code
        return when (error) {
            is SocketTimeoutException -> CloudErrorCode.TIMEOUT
            is ConnectException,
            is UnknownHostException,
            is IOException,
            -> CloudErrorCode.NETWORK_UNAVAILABLE
            else -> CloudErrorCode.UNKNOWN
        }
    }

    fun wrap(error: Throwable): CloudOperationException = when (error) {
        is CloudOperationException -> error
        else -> CloudOperationException(
            code = classify(error),
            message = "云端操作失败",
            cause = error,
        )
    }
}

package com.dai2010.betterpak.domain

import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

enum class ArchiveErrorCode(val retryable: Boolean) {
    INVALID_PATH(false),
    LIMIT_EXCEEDED(false),
    PERMISSION_REVOKED(true),
    OUTPUT_CONFLICT(true),
    INSUFFICIENT_STORAGE(true),
    WRONG_PASSWORD(false),
    CORRUPT_ARCHIVE(false),
    UNSUPPORTED_FORMAT(false),
    NO_EXTERNAL_HANDLER(false),
    CANCELLED(true),
    UNKNOWN(true),
}

class ArchiveOperationException(
    val code: ArchiveErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

object ArchiveErrorClassifier {
    fun wrap(error: Throwable): Throwable {
        if (error is ArchiveOperationException) return error
        return ArchiveOperationException(classify(error), error.message ?: "归档操作失败", error)
    }

    fun classify(error: Throwable): ArchiveErrorCode {
        when (error) {
            is ArchiveOperationException -> return error.code
            is SecurityException,
            is FileNotFoundException,
            -> return ArchiveErrorCode.PERMISSION_REVOKED
            is ZipException -> return ArchiveErrorCode.CORRUPT_ARCHIVE
        }

        error.cause?.let { cause ->
            classifyCause(cause)?.let { return it }
        }

        val message = error.message.orEmpty().lowercase()
        return when {
            "路径" in message || "path" in message -> ArchiveErrorCode.INVALID_PATH
            "超过限制" in message || "limit" in message -> ArchiveErrorCode.LIMIT_EXCEEDED
            "权限" in message || "permission" in message || "无法访问" in message -> ArchiveErrorCode.PERMISSION_REVOKED
            "空间" in message || "storage" in message || "no space" in message -> ArchiveErrorCode.INSUFFICIENT_STORAGE
            "密码" in message || "password" in message || "encrypted" in message -> ArchiveErrorCode.WRONG_PASSWORD
            "输出" in message || "目标" in message -> ArchiveErrorCode.OUTPUT_CONFLICT
            "不支持" in message || "unsupported" in message -> ArchiveErrorCode.UNSUPPORTED_FORMAT
            "损坏" in message || "corrupt" in message || "checksum" in message -> ArchiveErrorCode.CORRUPT_ARCHIVE
            else -> ArchiveErrorCode.UNKNOWN
        }
    }

    private fun classifyCause(error: Throwable): ArchiveErrorCode? = when (error) {
        is ArchiveOperationException -> error.code
        is SecurityException,
        is FileNotFoundException,
        -> ArchiveErrorCode.PERMISSION_REVOKED
        is ZipException -> ArchiveErrorCode.CORRUPT_ARCHIVE
        is IOException -> error.message?.lowercase()?.let { message ->
            when {
                "no space" in message || "空间" in message -> ArchiveErrorCode.INSUFFICIENT_STORAGE
                "permission" in message || "权限" in message -> ArchiveErrorCode.PERMISSION_REVOKED
                else -> null
            }
        }
        else -> null
    }
}

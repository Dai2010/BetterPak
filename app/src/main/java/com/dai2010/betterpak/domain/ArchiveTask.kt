package com.dai2010.betterpak.domain

import java.net.URI
import java.util.Base64

enum class ArchiveTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    RETRYABLE,
    RECOVERING,
}

enum class ArchiveTaskKind {
    CREATE,
    EXTRACT,
}

data class ArchiveTask(
    val id: String,
    val kind: ArchiveTaskKind,
    val sourceUri: String,
    val targetUri: String,
    val format: ArchiveFormat,
    val status: ArchiveTaskStatus = ArchiveTaskStatus.QUEUED,
    val errorCode: ArchiveErrorCode? = null,
    val progressSummary: String = "",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

object ArchiveTaskValidation {
    fun canExecute(task: ArchiveTask): Boolean =
        task.sourceUri.split(',').all(::isUsableUri) && isUsableUri(task.targetUri)

    private fun isUsableUri(value: String): Boolean = runCatching {
        URI(value.trim()).scheme.orEmpty().isNotBlank()
    }.getOrDefault(false)
}

object ArchiveTaskCodec {
    private const val FIELD_SEPARATOR = ":"
    private const val FIELD_COUNT = 10

    fun encode(task: ArchiveTask): String = listOf(
        task.id,
        task.kind.name,
        task.sourceUri,
        task.targetUri,
        task.format.name,
        task.status.name,
        task.errorCode?.name.orEmpty(),
        task.progressSummary,
        task.createdAt.toString(),
        task.updatedAt.toString(),
    ).joinToString(FIELD_SEPARATOR) { value ->
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    fun decode(value: String): ArchiveTask? = runCatching {
        val fields = value.split(FIELD_SEPARATOR).map { encoded ->
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }
        require(fields.size == FIELD_COUNT)
        ArchiveTask(
            id = fields[0],
            kind = ArchiveTaskKind.valueOf(fields[1]),
            sourceUri = fields[2],
            targetUri = fields[3],
            format = ArchiveFormat.valueOf(fields[4]),
            status = ArchiveTaskStatus.valueOf(fields[5]),
            errorCode = fields[6].takeIf(String::isNotEmpty)?.let(ArchiveErrorCode::valueOf),
            progressSummary = fields[7],
            createdAt = fields[8].toLong(),
            updatedAt = fields[9].toLong(),
        )
    }.getOrNull()
}

sealed interface ArchiveTaskEvent {
    data object Start : ArchiveTaskEvent
    data object Recover : ArchiveTaskEvent
    data object Complete : ArchiveTaskEvent
    data object Cancel : ArchiveTaskEvent
    data class Fail(val retryable: Boolean) : ArchiveTaskEvent
    data object Retry : ArchiveTaskEvent
}

object ArchiveTaskStateMachine {
    fun apply(status: ArchiveTaskStatus, event: ArchiveTaskEvent): ArchiveTaskStatus = when (event) {
        ArchiveTaskEvent.Start -> when (status) {
            ArchiveTaskStatus.QUEUED,
            ArchiveTaskStatus.RETRYABLE,
            -> ArchiveTaskStatus.RUNNING
            else -> status
        }
        ArchiveTaskEvent.Recover -> when (status) {
            ArchiveTaskStatus.RUNNING -> ArchiveTaskStatus.RECOVERING
            else -> status
        }
        ArchiveTaskEvent.Complete -> when (status) {
            ArchiveTaskStatus.RUNNING,
            ArchiveTaskStatus.RECOVERING,
            -> ArchiveTaskStatus.SUCCEEDED
            else -> status
        }
        ArchiveTaskEvent.Cancel -> when (status) {
            ArchiveTaskStatus.QUEUED,
            ArchiveTaskStatus.RUNNING,
            ArchiveTaskStatus.RECOVERING,
            ArchiveTaskStatus.RETRYABLE,
            -> ArchiveTaskStatus.CANCELLED
            else -> status
        }
        is ArchiveTaskEvent.Fail -> when (status) {
            ArchiveTaskStatus.RUNNING,
            ArchiveTaskStatus.RECOVERING,
            -> if (event.retryable) ArchiveTaskStatus.RETRYABLE else ArchiveTaskStatus.FAILED
            else -> status
        }
        ArchiveTaskEvent.Retry -> when (status) {
            ArchiveTaskStatus.FAILED,
            ArchiveTaskStatus.RETRYABLE,
            ArchiveTaskStatus.CANCELLED,
            -> ArchiveTaskStatus.QUEUED
            else -> status
        }
    }
}

package com.dai2010.betterpak.domain

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

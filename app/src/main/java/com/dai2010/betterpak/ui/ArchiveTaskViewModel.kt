package com.dai2010.betterpak.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dai2010.betterpak.data.ArchiveTaskStore
import com.dai2010.betterpak.domain.ArchiveErrorCode
import com.dai2010.betterpak.domain.ArchiveFormat
import com.dai2010.betterpak.domain.ArchiveTask
import com.dai2010.betterpak.domain.ArchiveTaskEvent
import com.dai2010.betterpak.domain.ArchiveTaskKind
import com.dai2010.betterpak.domain.ArchiveTaskStateMachine
import com.dai2010.betterpak.domain.ArchiveTaskStatus
import com.dai2010.betterpak.domain.ArchiveTaskValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ArchiveTaskViewModel(private val store: ArchiveTaskStore) : ViewModel() {
    private val _tasks = MutableStateFlow<List<ArchiveTask>>(emptyList())
    val tasks: StateFlow<List<ArchiveTask>> = _tasks.asStateFlow()

    init {
        store.list().forEach { task ->
            if (task.status == ArchiveTaskStatus.RUNNING) {
                store.save(
                    task.copy(
                        status = ArchiveTaskStateMachine.apply(task.status, ArchiveTaskEvent.Recover),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        _tasks.value = store.list()
    }

    fun enqueue(
        kind: ArchiveTaskKind,
        sourceUri: String,
        targetUri: String,
        format: ArchiveFormat,
    ): ArchiveTask {
        require(
            ArchiveTaskValidation.canExecute(
                ArchiveTask(
                    id = "validation",
                    kind = kind,
                    sourceUri = sourceUri,
                    targetUri = targetUri,
                    format = format,
                    createdAt = 0L,
                ),
            ),
        ) { "任务来源或目标 URI 无效" }
        val now = System.currentTimeMillis()
        return ArchiveTask(
            id = UUID.randomUUID().toString(),
            kind = kind,
            sourceUri = sourceUri,
            targetUri = targetUri,
            format = format,
            createdAt = now,
            updatedAt = now,
        ).also(::save)
    }

    fun start(id: String) = transition(id, ArchiveTaskEvent.Start)

    fun recover(id: String) = transition(id, ArchiveTaskEvent.Recover)

    fun complete(id: String) = transition(id, ArchiveTaskEvent.Complete)

    fun cancel(id: String) = transition(id, ArchiveTaskEvent.Cancel)

    fun retry(id: String) = transition(id, ArchiveTaskEvent.Retry)

    fun fail(id: String, errorCode: ArchiveErrorCode) {
        val task = store.find(id) ?: return
        val status = ArchiveTaskStateMachine.apply(
            task.status,
            ArchiveTaskEvent.Fail(errorCode.retryable),
        )
        save(task.copy(status = status, errorCode = errorCode))
    }

    fun updateProgress(id: String, summary: String) {
        val task = store.find(id) ?: return
        save(task.copy(progressSummary = summary, updatedAt = System.currentTimeMillis()))
    }

    private fun transition(id: String, event: ArchiveTaskEvent) {
        val task = store.find(id) ?: return
        val status = ArchiveTaskStateMachine.apply(task.status, event)
        save(task.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    private fun save(task: ArchiveTask): ArchiveTask {
        store.save(task)
        _tasks.value = store.list()
        return task
    }
}

class ArchiveTaskViewModelFactory(
    private val store: ArchiveTaskStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ArchiveTaskViewModel::class.java))
        return ArchiveTaskViewModel(store) as T
    }
}

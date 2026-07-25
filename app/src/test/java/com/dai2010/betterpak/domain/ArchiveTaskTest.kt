package com.dai2010.betterpak.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveTaskTest {
    @Test
    fun supportsRecoveryAndRetryWithoutPersistingSecrets() {
        assertEquals(
            ArchiveTaskStatus.RECOVERING,
            ArchiveTaskStateMachine.apply(ArchiveTaskStatus.RUNNING, ArchiveTaskEvent.Recover),
        )
        assertEquals(
            ArchiveTaskStatus.RETRYABLE,
            ArchiveTaskStateMachine.apply(ArchiveTaskStatus.RUNNING, ArchiveTaskEvent.Fail(retryable = true)),
        )
        assertEquals(
            ArchiveTaskStatus.QUEUED,
            ArchiveTaskStateMachine.apply(ArchiveTaskStatus.RETRYABLE, ArchiveTaskEvent.Retry),
        )
    }

    @Test
    fun doesNotMoveCompletedTasksBackToRunning() {
        assertEquals(
            ArchiveTaskStatus.SUCCEEDED,
            ArchiveTaskStateMachine.apply(ArchiveTaskStatus.SUCCEEDED, ArchiveTaskEvent.Start),
        )
        assertEquals(
            ArchiveTaskStatus.CANCELLED,
            ArchiveTaskStateMachine.apply(ArchiveTaskStatus.RUNNING, ArchiveTaskEvent.Cancel),
        )
    }
}

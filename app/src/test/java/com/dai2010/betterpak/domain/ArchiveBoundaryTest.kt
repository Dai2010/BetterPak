package com.dai2010.betterpak.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveBoundaryTest {
    @Test
    fun resolvesFormatFromCaseInsensitiveNames() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormatResolver.fromFileName("backup/ARCHIVE.ZIP"))
        assertEquals(ArchiveFormat.TAR_ZSTANDARD, ArchiveFormatResolver.fromFileName("logs.tzst"))
        assertEquals(ArchiveFormat.ZSTANDARD, ArchiveFormatResolver.fromFileName("payload.ZSTD"))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormatResolver.fromFileName("payload.bin"))
    }

    @Test
    fun selectionIncludesDescendantsButNotSiblingPaths() {
        assertTrue(ArchiveSelection.includes("docs/readme.md", setOf("docs")))
        assertTrue(ArchiveSelection.includes("docs", setOf("docs")))
        assertFalse(ArchiveSelection.includes("docs-old/readme.md", setOf("docs")))
        assertTrue(ArchiveSelection.includes("anything", null))
    }

    @Test
    fun taskCodecRoundTripsAndDoesNotStorePlaintextSecrets() {
        val task = ArchiveTask(
            id = "task-1",
            kind = ArchiveTaskKind.EXTRACT,
            sourceUri = "content://archives/one",
            targetUri = "content://output/tree",
            format = ArchiveFormat.ZIP,
            status = ArchiveTaskStatus.RETRYABLE,
            errorCode = ArchiveErrorCode.WRONG_PASSWORD,
            progressSummary = "4/10",
            createdAt = 100L,
            updatedAt = 200L,
        )
        val encoded = ArchiveTaskCodec.encode(task)
        assertFalse(encoded.contains("password"))
        assertEquals(task, ArchiveTaskCodec.decode(encoded))
        assertTrue(ArchiveTaskValidation.canExecute(task))
        assertFalse(
            ArchiveTaskValidation.canExecute(task.copy(sourceUri = "not-a-uri")),
        )
    }
}

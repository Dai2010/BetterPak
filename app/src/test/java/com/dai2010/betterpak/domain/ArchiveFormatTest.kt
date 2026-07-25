package com.dai2010.betterpak.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveFormatTest {
    @Test
    fun passwordCapabilityMatchesArchiveSupport() {
        assertFalse(ArchiveFormat.ZIP.supportsPassword)
        assertTrue(ArchiveFormat.RAR.supportsPassword)
        assertTrue(ArchiveFormat.SEVEN_Z.supportsPassword)
        assertFalse(ArchiveFormat.ZSTANDARD.supportsPassword)
        assertFalse(ArchiveFormat.TAR_ZSTANDARD.supportsPassword)
        assertFalse(ArchiveFormat.UNKNOWN.supportsPassword)
    }

    @Test
    fun formatCapabilitiesMatchV005Scope() {
        assertTrue(ArchiveFormat.TAR.supportsCreate)
        assertTrue(ArchiveFormat.TAR.supportsDirectoryListing)
        assertTrue(ArchiveFormat.TAR_ZSTANDARD.supportsCreate)
        assertTrue(ArchiveFormat.TAR_ZSTANDARD.supportsDirectoryListing)
        assertTrue(ArchiveFormat.ZSTANDARD.supportsStreamPreview)
        assertTrue(ArchiveFormat.ZSTANDARD.supportsList)
        assertTrue(ArchiveFormat.ZSTANDARD.supportsExtract)
        assertTrue(ArchiveFormat.ZSTANDARD.supportsPreview)
        assertTrue(ArchiveFormat.TAR_ZSTANDARD.supportsStream)
        assertFalse(ArchiveFormat.ZSTANDARD.supportsDirectoryListing)
        assertFalse(ArchiveFormat.RAR.supportsCreate)
        assertFalse(ArchiveFormat.UNKNOWN.supportsList)
    }
}

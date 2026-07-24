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
        assertFalse(ArchiveFormat.UNKNOWN.supportsPassword)
    }
}

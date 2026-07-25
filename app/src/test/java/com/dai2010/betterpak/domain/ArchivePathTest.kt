package com.dai2010.betterpak.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchivePathTest {
    @Test
    fun normalizesSafeRelativePaths() {
        assertEquals("文档/readme.txt", ArchivePath.normalize("文档\\./readme.txt"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        assertNull(ArchivePath.normalize("../outside.txt"))
        assertNull(ArchivePath.normalize("folder/../../outside.txt"))
        assertNull(ArchivePath.normalize("/absolute/file.txt"))
        assertNull(ArchivePath.normalize("C:/absolute/file.txt"))
    }

    @Test
    fun rejectsEmptyAndNulPaths() {
        assertNull(ArchivePath.normalize(""))
        assertNull(ArchivePath.normalize("safe\u0000name.txt"))
    }

    @Test
    fun keepsArchiveDirectoriesRelative() {
        assertEquals("folder", ArchivePath.normalize("folder/"))
        assertEquals("folder/file.txt", ArchivePath.normalize("./folder//file.txt"))
    }

    @Test
    fun rejectsOtherAbsolutePathForms() {
        assertNull(ArchivePath.normalize("\\server\\share\\file.txt"))
        assertNull(ArchivePath.normalize("//server/share/file.txt"))
    }
}

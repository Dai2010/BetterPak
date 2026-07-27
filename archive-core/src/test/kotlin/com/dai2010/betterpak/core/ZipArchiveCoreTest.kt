package com.dai2010.betterpak.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipArchiveCoreTest {
    @Test
    fun createsListsExtractsAndReadsUnicodeZipEntries() = withTemporaryDirectory { root ->
        val inputDirectory = Files.createDirectories(root.resolve("输入资料"))
        Files.writeString(inputDirectory.resolve("说明.txt"), "BetterPak\n归档核心")
        Files.createDirectories(inputDirectory.resolve("空目录"))
        Files.write(inputDirectory.resolve("空文件.bin"), byteArrayOf())
        val archive = root.resolve("输出.zip")
        val destination = Files.createDirectories(root.resolve("解压结果"))
        val engine = ZipArchiveCore()

        val createdFiles = engine.create(listOf(inputDirectory), archive)
        val entries = engine.list(archive)
        val extractedFiles = engine.extract(archive, destination)

        assertEquals(2, createdFiles)
        assertTrue(entries.any { it.path == "输入资料/说明.txt" && !it.isDirectory })
        assertTrue(entries.any { it.path == "输入资料/空目录" && it.isDirectory })
        assertEquals(2, extractedFiles)
        assertEquals(
            "BetterPak\n归档核心",
            Files.readString(destination.resolve("输入资料/说明.txt")),
        )
        assertArrayEquals(
            byteArrayOf(),
            Files.readAllBytes(destination.resolve("输入资料/空文件.bin")),
        )
    }

    @Test
    fun rejectsUnsafeEntriesBeforeWritingDestination() = withTemporaryDirectory { root ->
        val archive = root.resolve("unsafe.zip")
        writeZip(archive, "../outside.txt" to "blocked")
        val destination = Files.createDirectories(root.resolve("destination"))
        val engine = ZipArchiveCore()

        val error = runCatching { engine.extract(archive, destination) }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.INVALID_PATH, (error as ArchiveCoreException).code)
        assertFalse(Files.exists(root.resolve("outside.txt")))
        assertEquals(emptyList<Path>(), Files.list(destination).use { it.toList() })
    }

    @Test
    fun enforcesExpandedByteLimitAndCleansStagingFiles() = withTemporaryDirectory { root ->
        val archive = root.resolve("large.zip")
        writeZip(archive, "large.txt" to "123456789")
        val destination = Files.createDirectories(root.resolve("destination"))
        val engine = ZipArchiveCore(ArchiveLimits(maxExpandedBytes = 4))

        val error = runCatching { engine.extract(archive, destination) }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.LIMIT_EXCEEDED, (error as ArchiveCoreException).code)
        assertEquals(emptyList<Path>(), Files.list(destination).use { it.toList() })
    }

    @Test
    fun cancellationDoesNotLeavePartialArchive() = withTemporaryDirectory { root ->
        val input = root.resolve("input.txt")
        Files.writeString(input, "cancel me")
        val output = root.resolve("output.zip")
        val engine = ZipArchiveCore()

        val error = runCatching {
            engine.create(listOf(input), output, isCancelled = { true })
        }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.CANCELLED, (error as ArchiveCoreException).code)
        assertFalse(Files.exists(output))
        assertEquals(
            emptyList<Path>(),
            Files.list(root).use { stream -> stream.filter { it.fileName.toString().contains(".part") }.toList() },
        )
    }

    @Test
    fun readsSelectedEntryWithoutLoadingOtherEntries() = withTemporaryDirectory { root ->
        val archive = root.resolve("selection.zip")
        writeZip(archive, "docs/readme.txt" to "read me", "media.bin" to "not selected")
        val destination = Files.createDirectories(root.resolve("destination"))
        val engine = ZipArchiveCore()

        val bytes = engine.readEntry(archive, "docs/readme.txt", 64)
        val extracted = engine.extract(archive, destination, selectedPaths = setOf("docs"))

        assertArrayEquals("read me".toByteArray(), bytes)
        assertEquals(1, extracted)
        assertTrue(Files.exists(destination.resolve("docs/readme.txt")))
        assertFalse(Files.exists(destination.resolve("media.bin")))
    }

    private fun writeZip(archive: Path, vararg entries: Pair<String, String>) {
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun <T> withTemporaryDirectory(block: (Path) -> T): T {
        val directory = Files.createTempDirectory("betterpak-core-test-")
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun Path.deleteRecursively() {
        if (!Files.exists(this)) return
        Files.walk(this).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

package com.dai2010.betterpak.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TarArchiveCoreTest {
    @Test
    fun createsListsExtractsAndReadsUnicodeTarEntries() = withTemporaryDirectory { root ->
        assertArchiveRoundTrip(root, TarCompression.NONE, "输出.tar")
    }

    @Test
    fun createsListsExtractsAndReadsUnicodeTarZstandardEntries() = withTemporaryDirectory { root ->
        try {
            assertArchiveRoundTrip(root, TarCompression.ZSTANDARD, "输出.tar.zst")
        } catch (error: UnsatisfiedLinkError) {
            assumeNoException(error)
        }
    }

    @Test
    fun rejectsUnsafeTarEntriesBeforeWritingDestination() = withTemporaryDirectory { root ->
        val archive = root.resolve("unsafe.tar")
        writeTar(archive, "../outside.txt" to "blocked")
        val destination = Files.createDirectories(root.resolve("destination"))

        val error = runCatching { TarArchiveCore().extract(archive, destination) }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.INVALID_PATH, (error as ArchiveCoreException).code)
        assertFalse(Files.exists(root.resolve("outside.txt")))
        assertEquals(emptyList<Path>(), Files.list(destination).use { it.toList() })
    }

    @Test
    fun enforcesExpandedByteLimitAndCleansStagingFiles() = withTemporaryDirectory { root ->
        val archive = root.resolve("large.tar")
        writeTar(archive, "large.txt" to "123456789")
        val destination = Files.createDirectories(root.resolve("destination"))

        val error = runCatching {
            TarArchiveCore(ArchiveLimits(maxExpandedBytes = 4)).extract(archive, destination)
        }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.LIMIT_EXCEEDED, (error as ArchiveCoreException).code)
        assertEquals(emptyList<Path>(), Files.list(destination).use { it.toList() })
    }

    @Test
    fun cancellationDoesNotLeavePartialTarArchive() = withTemporaryDirectory { root ->
        val input = root.resolve("input.txt")
        Files.writeString(input, "cancel me")
        val output = root.resolve("output.tar")

        val error = runCatching {
            TarArchiveCore().create(listOf(input), output, isCancelled = { true })
        }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.CANCELLED, (error as ArchiveCoreException).code)
        assertFalse(Files.exists(output))
        assertEquals(emptyList<Path>(), Files.list(root).use { stream ->
            stream.filter { it.fileName.toString().contains("betterpak-") }.toList()
        })
    }

    @Test
    fun readEntryHonorsReadLimit() = withTemporaryDirectory { root ->
        val archive = root.resolve("read.tar")
        writeTar(archive, "notes.txt" to "read me")

        val error = runCatching {
            TarArchiveCore().readEntry(archive, "notes.txt", maxBytes = 2)
        }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.LIMIT_EXCEEDED, (error as ArchiveCoreException).code)
    }

    private fun assertArchiveRoundTrip(root: Path, compression: TarCompression, name: String) {
        val inputDirectory = Files.createDirectories(root.resolve("输入资料"))
        Files.writeString(inputDirectory.resolve("说明.txt"), "BetterPak\n归档核心")
        Files.createDirectories(inputDirectory.resolve("空目录"))
        Files.write(inputDirectory.resolve("空文件.bin"), byteArrayOf())
        val archive = root.resolve(name)
        val destination = Files.createDirectories(root.resolve("解压结果"))
        val engine = TarArchiveCore()

        val createdFiles = engine.create(listOf(inputDirectory), archive, compression = compression)
        val entries = engine.list(archive, compression)
        val extractedFiles = engine.extract(archive, destination, compression)
        val payload = engine.readEntry(archive, "输入资料/说明.txt", 64, compression)

        assertEquals(2, createdFiles)
        assertTrue(entries.any { it.path == "输入资料/说明.txt" && !it.isDirectory })
        assertTrue(entries.any { it.path == "输入资料/空目录" && it.isDirectory })
        assertEquals(2, extractedFiles)
        assertEquals("BetterPak\n归档核心", Files.readString(destination.resolve("输入资料/说明.txt")))
        assertArrayEquals(byteArrayOf(), Files.readAllBytes(destination.resolve("输入资料/空文件.bin")))
        assertArrayEquals("BetterPak\n归档核心".toByteArray(), payload)
    }

    private fun writeTar(archive: Path, vararg entries: Pair<String, String>) {
        TarArchiveOutputStream(Files.newOutputStream(archive)).use { output ->
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            output.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            entries.forEach { (name, content) ->
                val entry = TarArchiveEntry(name).apply { size = content.toByteArray().size.toLong() }
                output.putArchiveEntry(entry)
                output.write(content.toByteArray())
                output.closeArchiveEntry()
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

package com.dai2010.betterpak.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
    fun createsStoredZipEntriesWithoutDeflateData() = withTemporaryDirectory { root ->
        val input = root.resolve("payload.txt")
        Files.writeString(input, "stored content")
        val archive = root.resolve("stored.zip")

        ZipArchiveCore().create(
            inputs = listOf(input),
            output = archive,
            compression = ZipCompression.STORE,
        )

        ZipFile(archive.toFile()).use { zipFile ->
            val entry = zipFile.getEntry("payload.txt")
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals(entry.size, entry.compressedSize)
        }
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

    @Test
    fun readEntryHonorsLimitCancellationAndProgress() = withTemporaryDirectory { root ->
        val archive = root.resolve("read.zip")
        writeZip(archive, "notes.txt" to "read me")
        val progress = mutableListOf<ArchiveProgress>()
        val engine = ZipArchiveCore()

        val bytes = engine.readEntry(
            archive = archive,
            path = "notes.txt",
            maxBytes = 64,
            onProgress = progress::add,
        )

        assertArrayEquals("read me".toByteArray(), bytes)
        assertTrue(progress.isNotEmpty())
        assertEquals(1, progress.last().processedEntries)
        assertEquals(bytes.size.toLong(), progress.last().processedBytes)

        val cancelled = runCatching {
            engine.readEntry(archive, "notes.txt", 64, isCancelled = { true })
        }.exceptionOrNull()
        assertTrue(cancelled is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.CANCELLED, (cancelled as ArchiveCoreException).code)

        val limited = runCatching { engine.readEntry(archive, "notes.txt", 2) }.exceptionOrNull()
        assertTrue(limited is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.LIMIT_EXCEEDED, (limited as ArchiveCoreException).code)
    }

    @Test
    fun readEntryHonorsCoreExpandedLimitEvenWhenReadLimitIsLarger() = withTemporaryDirectory { root ->
        val archive = root.resolve("bounded-read.zip")
        writeZip(archive, "notes.txt" to "read me")

        val error = runCatching {
            ZipArchiveCore(ArchiveLimits(maxExpandedBytes = 2)).readEntry(
                archive = archive,
                path = "notes.txt",
                maxBytes = 64,
            )
        }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.LIMIT_EXCEEDED, (error as ArchiveCoreException).code)
    }

    @Test
    fun classifiesTruncatedZipAsCorruptArchive() = withTemporaryDirectory { root ->
        val archive = root.resolve("truncated.zip")
        writeZip(archive, "notes.txt" to "content")
        val bytes = Files.readAllBytes(archive)
        Files.write(archive, bytes.copyOf(bytes.size - 4))

        val error = runCatching { ZipArchiveCore().list(archive) }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.CORRUPT_ARCHIVE, (error as ArchiveCoreException).code)
    }

    @Test
    fun rejectsEntryCountDuplicatesAndUnsafeOutputParents() = withTemporaryDirectory { root ->
        val tooManyEntries = root.resolve("too-many.zip")
        writeZip(tooManyEntries, "nested/file.txt" to "1", "two.txt" to "2")
        val tooMany = runCatching {
            ZipArchiveCore(ArchiveLimits(maxEntries = 1)).list(tooManyEntries)
        }.exceptionOrNull()
        assertTrue(tooMany is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.LIMIT_EXCEEDED, (tooMany as ArchiveCoreException).code)

        val duplicate = root.resolve("duplicate.zip")
        writeZip(duplicate, "same.txt" to "first", "./same.txt" to "second")
        val duplicateError = runCatching { ZipArchiveCore().list(duplicate) }.exceptionOrNull()
        assertTrue(duplicateError is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.DUPLICATE_ENTRY, (duplicateError as ArchiveCoreException).code)

        val destination = Files.createDirectories(root.resolve("destination"))
        Files.writeString(destination.resolve("nested"), "blocking file")
        val parentConflict = runCatching { ZipArchiveCore().extract(tooManyEntries, destination) }.exceptionOrNull()
        assertTrue(parentConflict is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.OUTPUT_CONFLICT, (parentConflict as ArchiveCoreException).code)
        assertEquals("blocking file", Files.readString(destination.resolve("nested")))
    }

    @Test
    fun rejectsSymbolicLinksInExtractionDestination() = withTemporaryDirectory { root ->
        val archive = root.resolve("link.zip")
        writeZip(archive, "redirect/file.txt" to "blocked")
        val destination = Files.createDirectories(root.resolve("destination"))
        val outside = Files.createDirectories(root.resolve("outside"))
        Files.createSymbolicLink(destination.resolve("redirect"), outside)

        val error = runCatching { ZipArchiveCore().extract(archive, destination) }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.INVALID_PATH, (error as ArchiveCoreException).code)
        assertFalse(Files.exists(outside.resolve("file.txt")))
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

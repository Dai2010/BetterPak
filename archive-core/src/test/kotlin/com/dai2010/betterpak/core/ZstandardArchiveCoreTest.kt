package com.dai2010.betterpak.core

import com.github.luben.zstd.ZstdOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class ZstandardArchiveCoreTest {
    @Test
    fun measuresReadsAndCopiesSingleStream() = withTemporaryDirectory { root ->
        withZstandardSupport {
            val archive = root.resolve("内容.txt.zst")
            val payload = "BetterPak Zstandard 核心".toByteArray()
            writeZstandard(archive, payload)
            val progress = mutableListOf<ArchiveProgress>()
            val output = ByteArrayOutputStream()
            val engine = ZstandardArchiveCore()

            assertEquals(payload.size.toLong(), engine.expandedSize(archive))
            assertArrayEquals(payload, engine.read(archive, payload.size.toLong()))
            assertEquals(
                payload.size.toLong(),
                engine.copyTo(archive, output, payload.size.toLong(), onProgress = progress::add),
            )
            assertArrayEquals(payload, output.toByteArray())
            assertTrue(progress.isNotEmpty())
            assertEquals(1, progress.last().processedEntries)
            assertEquals(payload.size.toLong(), progress.last().processedBytes)
            val extracted = engine.extract(archive, root.resolve("解压结果.txt"))
            assertArrayEquals(payload, Files.readAllBytes(extracted))
        }
    }

    @Test
    fun enforcesReadAndCoreExpandedLimits() = withTemporaryDirectory { root ->
        withZstandardSupport {
            val archive = root.resolve("limited.zst")
            writeZstandard(archive, "123456789".toByteArray())

            val readError = runCatching {
                ZstandardArchiveCore().read(archive, maxBytes = 4)
            }.exceptionOrNull()
            assertTrue(readError is ArchiveCoreException)
            assertEquals(ArchiveErrorCode.LIMIT_EXCEEDED, (readError as ArchiveCoreException).code)

            val coreError = runCatching {
                ZstandardArchiveCore(ArchiveLimits(maxExpandedBytes = 4)).expandedSize(archive)
            }.exceptionOrNull()
            assertTrue(coreError is ArchiveCoreException)
            assertEquals(ArchiveErrorCode.LIMIT_EXCEEDED, (coreError as ArchiveCoreException).code)
        }
    }

    @Test
    fun classifiesInvalidZstandardStreamAsCorrupt() = withTemporaryDirectory { root ->
        withZstandardSupport {
            val archive = root.resolve("invalid.zst")
            Files.writeString(archive, "not a zstandard frame")

            val error = runCatching { ZstandardArchiveCore().expandedSize(archive) }.exceptionOrNull()

            assertTrue(error is ArchiveCoreException)
            assertEquals(ArchiveErrorCode.CORRUPT_ARCHIVE, (error as ArchiveCoreException).code)
        }
    }

    @Test
    fun cancellationCleansTransactionalOutput() = withTemporaryDirectory { root ->
        val archive = root.resolve("cancelled.zst")
        val output = root.resolve("output.txt")
        Files.write(
            archive,
            byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte()),
        )

        val error = runCatching {
            ZstandardArchiveCore().extract(archive, output, isCancelled = { true })
        }.exceptionOrNull()

        assertTrue(error is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.CANCELLED, (error as ArchiveCoreException).code)
        assertTrue(Files.notExists(output))
        assertTrue(Files.list(root).use { stream ->
            stream.noneMatch { it.fileName.toString().contains("betterpak-") }
        })
    }

    @Test
    fun rejectsSourceOverwriteAndSymbolicLinkParents() = withTemporaryDirectory { root ->
        val archive = root.resolve("source.zst")
        Files.write(
            archive,
            byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte()),
        )

        val sourceConflict = runCatching {
            ZstandardArchiveCore().extract(archive, archive, isCancelled = { true })
        }.exceptionOrNull()
        assertTrue(sourceConflict is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.OUTPUT_CONFLICT, (sourceConflict as ArchiveCoreException).code)

        val outside = Files.createDirectories(root.resolve("outside"))
        val linkedParent = root.resolve("linked")
        Files.createSymbolicLink(linkedParent, outside)
        val linkError = runCatching {
            ZstandardArchiveCore().extract(
                archive,
                linkedParent.resolve("nested/output.txt"),
                isCancelled = { true },
            )
        }.exceptionOrNull()
        assertTrue(linkError is ArchiveCoreException)
        assertEquals(ArchiveErrorCode.INVALID_PATH, (linkError as ArchiveCoreException).code)
        assertTrue(Files.notExists(outside.resolve("nested")))
    }

    private fun writeZstandard(archive: Path, payload: ByteArray) {
        ZstdOutputStream(Files.newOutputStream(archive)).use { output -> output.write(payload) }
    }

    private fun withZstandardSupport(block: () -> Unit) {
        try {
            ZstdOutputStream(ByteArrayOutputStream()).close()
            block()
        } catch (error: LinkageError) {
            assumeNoException(error)
        }
    }

    private fun <T> withTemporaryDirectory(block: (Path) -> T): T {
        val directory = Files.createTempDirectory("betterpak-zstandard-core-test-")
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

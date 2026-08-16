package com.dai2010.betterpak.core

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

class ZstandardArchiveCore(
    private val defaultLimits: ArchiveLimits = ArchiveLimits(),
) {
    fun expandedSize(
        archive: Path,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Long = decompress(
        archive = archive,
        output = null,
        maxBytes = limits.maxExpandedBytes,
        limits = limits,
        isCancelled = isCancelled,
        onProgress = onProgress,
    )

    fun read(
        archive: Path,
        maxBytes: Long,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): ByteArray = withReadErrors {
        require(maxBytes > 0L) { "读取上限必须大于 0" }
        require(maxBytes <= Int.MAX_VALUE) { "读取上限超过 JVM 数组限制" }
        val output = ByteArrayOutputStream()
        copyTo(
            archive = archive,
            output = output,
            maxBytes = maxBytes,
            limits = limits,
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
        output.toByteArray()
    }

    fun copyTo(
        archive: Path,
        output: OutputStream,
        maxBytes: Long,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Long = decompress(
        archive = archive,
        output = output,
        maxBytes = maxBytes,
        limits = limits,
        isCancelled = isCancelled,
        onProgress = onProgress,
    )

    fun extract(
        archive: Path,
        output: Path,
        overwritePolicy: ArchiveOverwritePolicy = ArchiveOverwritePolicy.REPLACE,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Path = withWriteErrors {
        val normalizedArchive = archive.toAbsolutePath().normalize()
        val normalizedOutput = output.toAbsolutePath().normalize()
        if (normalizedArchive == normalizedOutput) {
            throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "解压输出不能覆盖源归档")
        }
        normalizedOutput.parent?.let(::createSafeDirectories)
        if (Files.isDirectory(normalizedOutput, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalizedOutput)) {
            throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "输出路径不是普通文件：$normalizedOutput")
        }
        val target = when (overwritePolicy) {
            ArchiveOverwritePolicy.REPLACE -> normalizedOutput
            ArchiveOverwritePolicy.SKIP -> {
                if (Files.exists(normalizedOutput, LinkOption.NOFOLLOW_LINKS)) return@withWriteErrors normalizedOutput
                normalizedOutput
            }
            ArchiveOverwritePolicy.RENAME -> uniqueOutputPath(normalizedOutput)
        }
        val temporaryOutput = temporarySibling(target)
        try {
            Files.newOutputStream(
                temporaryOutput,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { outputStream ->
                copyTo(
                    archive = archive,
                    output = outputStream,
                    maxBytes = limits.maxExpandedBytes,
                    limits = limits,
                    isCancelled = isCancelled,
                    onProgress = onProgress,
                )
            }
            moveIntoPlace(temporaryOutput, target)
            target
        } finally {
            Files.deleteIfExists(temporaryOutput)
        }
    }

    private fun decompress(
        archive: Path,
        output: OutputStream?,
        maxBytes: Long,
        limits: ArchiveLimits,
        isCancelled: () -> Boolean,
        onProgress: (ArchiveProgress) -> Unit,
    ): Long = withReadErrors {
        require(maxBytes > 0L) { "读取上限必须大于 0" }
        requireRegularFile(archive)
        checkCancelled(isCancelled)
        val effectiveMaxBytes = minOf(maxBytes, limits.maxExpandedBytes)
        var expandedBytes = 0L
        ZstdInputStream(BufferedInputStream(Files.newInputStream(archive))).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancelled(isCancelled)
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                expandedBytes = checkedAdd(expandedBytes, read.toLong(), effectiveMaxBytes)
                if (output != null) {
                    try {
                        output.write(buffer, 0, read)
                    } catch (error: IOException) {
                        throw ArchiveCoreException(
                            ArchiveErrorCode.PERMISSION_DENIED,
                            "无法写入解压输出",
                            error,
                        )
                    }
                }
                onProgress(ArchiveProgress(0, 1, expandedBytes, -1L))
            }
        }
        onProgress(ArchiveProgress(1, 1, expandedBytes, expandedBytes))
        expandedBytes
    }

    private fun requireRegularFile(path: Path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "无法访问归档文件：$path")
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            throw ArchiveCoreException(ArchiveErrorCode.CANCELLED, "归档操作已取消")
        }
    }

    private fun checkedAdd(current: Long, increment: Long, limit: Long): Long {
        if (increment < 0L || current > limit - increment) {
            throw ArchiveCoreException(ArchiveErrorCode.LIMIT_EXCEEDED, "归档展开大小超过限制")
        }
        return current + increment
    }

    private fun createSafeDirectories(directory: Path) {
        val normalizedDirectory = directory.toAbsolutePath().normalize()
        var current = normalizedDirectory.root
            ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输出目录必须是绝对路径")
        normalizedDirectory.forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输出目录不能包含符号链接：$current")
                }
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "输出父路径不是目录：$current")
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }

    private fun uniqueOutputPath(output: Path): Path {
        val fileName = output.fileName.toString()
        val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        val stem = if (extension == null) fileName else fileName.removeSuffix(".$extension")
        var index = 1
        var candidate = output
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            val candidateName = if (extension == null) "$stem ($index)" else "$stem ($index).$extension"
            candidate = output.resolveSibling(candidateName)
            index++
        }
        return candidate
    }

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun temporarySibling(target: Path): Path =
        target.resolveSibling(".${target.fileName}.betterpak-${UUID.randomUUID()}.part")

    private fun <T> withReadErrors(block: () -> T): T {
        try {
            return block()
        } catch (error: ArchiveCoreException) {
            throw error
        } catch (error: SecurityException) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "没有访问归档路径的权限", error)
        } catch (error: ZstdException) {
            throw ArchiveCoreException(ArchiveErrorCode.CORRUPT_ARCHIVE, "Zstandard 数据损坏或无法读取", error)
        } catch (error: IOException) {
            throw ArchiveCoreException(ArchiveErrorCode.CORRUPT_ARCHIVE, "Zstandard 数据损坏或无法读取", error)
        }
    }

    private fun <T> withWriteErrors(block: () -> T): T {
        try {
            return block()
        } catch (error: ArchiveCoreException) {
            throw error
        } catch (error: SecurityException) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "没有写入输出路径的权限", error)
        } catch (error: IOException) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "Zstandard 文件操作失败", error)
        }
    }

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
    }
}

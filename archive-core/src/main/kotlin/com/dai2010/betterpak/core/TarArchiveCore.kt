package com.dai2010.betterpak.core

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

enum class TarCompression {
    NONE,
    ZSTANDARD,
}

class TarArchiveCore(
    private val defaultLimits: ArchiveLimits = ArchiveLimits(),
) {
    fun list(
        archive: Path,
        compression: TarCompression = TarCompression.NONE,
        limits: ArchiveLimits = defaultLimits,
    ): List<ArchiveEntry> = withReadErrors {
        openInput(archive, compression).use { input ->
            val entries = mutableListOf<ArchiveEntry>()
            val paths = mutableSetOf<String>()
            var expandedBytes = 0L
            while (true) {
                val entry = input.getNextEntry() ?: break
                checkEntryCount(entries.size + 1, limits)
                val path = safeEntryPath(entry.name)
                validateRegularEntry(entry)
                if (!paths.add(path)) {
                    throw ArchiveCoreException(
                        ArchiveErrorCode.DUPLICATE_ENTRY,
                        "归档包含重复条目：$path",
                    )
                }
                if (entry.size < 0L) {
                    throw ArchiveCoreException(
                        ArchiveErrorCode.CORRUPT_ARCHIVE,
                        "归档条目包含无效大小：$path",
                    )
                }
                if (!entry.isDirectory) {
                    expandedBytes = checkedAdd(expandedBytes, entry.size, limits.maxExpandedBytes)
                }
                entries += ArchiveEntry(
                    path = path,
                    size = entry.size,
                    isDirectory = entry.isDirectory,
                    modifiedTimeMillis = entry.modTime?.time,
                )
            }
            validatePathRelationships(entries)
            entries
        }
    }

    fun create(
        inputs: List<Path>,
        output: Path,
        compression: TarCompression = TarCompression.NONE,
        compressionLevel: Int = 3,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Int = withWriteErrors {
        require(inputs.isNotEmpty()) { "至少需要一个输入路径" }
        if (compression == TarCompression.ZSTANDARD) {
            require(compressionLevel in 1..22) { "Zstandard 压缩级别必须在 1 到 22 之间" }
        }
        val normalizedInputs = inputs.map { it.toAbsolutePath().normalize() }
        val normalizedOutput = output.toAbsolutePath().normalize()
        ensureOutputDoesNotOverlapInputs(normalizedInputs, normalizedOutput)
        val entries = collectInputEntries(normalizedInputs, limits)
        require(entries.isNotEmpty()) { "输入路径没有可归档内容" }
        val totalBytes = entries.sumOf { entry ->
            if (entry.isDirectory) 0L else Files.size(entry.pathOnDisk)
        }
        val temporaryOutput = temporarySibling(normalizedOutput)
        try {
            normalizedOutput.parent?.let { Files.createDirectories(it) }
            openOutput(temporaryOutput, compression, compressionLevel).use { outputStream ->
                var processedEntries = 0
                var processedBytes = 0L
                entries.forEach { inputEntry ->
                    checkCancelled(isCancelled)
                    val entry = outputStream.createArchiveEntry(
                        inputEntry.pathOnDisk.toFile(),
                        inputEntry.archivePath,
                    )
                    outputStream.putArchiveEntry(entry)
                    if (!inputEntry.isDirectory) {
                        Files.newInputStream(inputEntry.pathOnDisk).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                checkCancelled(isCancelled)
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                outputStream.write(buffer, 0, read)
                                processedBytes = checkedAdd(processedBytes, read.toLong(), Long.MAX_VALUE)
                                onProgress(
                                    ArchiveProgress(
                                        processedEntries,
                                        entries.size,
                                        processedBytes,
                                        totalBytes,
                                    ),
                                )
                            }
                        }
                    }
                    outputStream.closeArchiveEntry()
                    processedEntries++
                    onProgress(
                        ArchiveProgress(
                            processedEntries,
                            entries.size,
                            processedBytes,
                            totalBytes,
                        ),
                    )
                }
            }
            moveIntoPlace(temporaryOutput, normalizedOutput)
            entries.count { !it.isDirectory }
        } finally {
            Files.deleteIfExists(temporaryOutput)
        }
    }

    fun extract(
        archive: Path,
        destination: Path,
        compression: TarCompression = TarCompression.NONE,
        selectedPaths: Set<String>? = null,
        overwritePolicy: ArchiveOverwritePolicy = ArchiveOverwritePolicy.REPLACE,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Int = withReadErrors {
        requireDirectory(destination)
        val normalizedSelection = selectedPaths?.map {
            ArchivePath.normalize(it)
                ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "选择路径不安全")
        }?.toSet()
        val listedEntries = list(archive, compression, limits)
        val selectedEntries = listedEntries.filter { entry ->
            ArchiveSelection.includes(entry.path, normalizedSelection)
        }
        val totalBytes = selectedEntries.fold(0L) { current, entry ->
            if (entry.isDirectory) current else checkedAdd(current, entry.size, limits.maxExpandedBytes)
        }
        val normalizedDestination = destination.toAbsolutePath().normalize()
        val stagingDirectory = normalizedDestination.resolveSibling(
            ".${normalizedDestination.fileName}.betterpak-${UUID.randomUUID()}",
        )
        try {
            Files.createDirectory(stagingDirectory)
            openInput(archive, compression).use { input ->
                val selectedByPath = selectedEntries.associateBy { it.path }
                var processedEntries = 0
                var processedBytes = 0L
                while (true) {
                    checkCancelled(isCancelled)
                    val entry = input.getNextEntry() ?: break
                    val path = safeEntryPath(entry.name)
                    val listedEntry = selectedByPath[path]
                    if (listedEntry == null) continue
                    val target = stagedTarget(
                        normalizedDestination,
                        stagingDirectory,
                        path,
                        entry.isDirectory,
                        overwritePolicy,
                    )
                    if (target != null) {
                        if (entry.isDirectory) {
                            Files.createDirectories(target.stagedPath)
                        } else {
                            Files.createDirectories(target.stagedPath.parent)
                            try {
                                Files.newOutputStream(
                                    target.stagedPath,
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE,
                                ).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    while (true) {
                                        checkCancelled(isCancelled)
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        if (read == 0) continue
                                        processedBytes = checkedAdd(
                                            processedBytes,
                                            read.toLong(),
                                            limits.maxExpandedBytes,
                                        )
                                        output.write(buffer, 0, read)
                                        onProgress(
                                            ArchiveProgress(
                                                processedEntries,
                                                selectedEntries.size,
                                                processedBytes,
                                                totalBytes,
                                            ),
                                        )
                                    }
                                }
                            } catch (error: Throwable) {
                                Files.deleteIfExists(target.stagedPath)
                                throw error
                            }
                        }
                    }
                    processedEntries++
                    onProgress(
                        ArchiveProgress(
                            processedEntries,
                            selectedEntries.size,
                            processedBytes,
                            totalBytes,
                        ),
                    )
                    if (listedEntry.isDirectory) continue
                }
            }
            commitStagedFiles(stagingDirectory, normalizedDestination, overwritePolicy)
            selectedEntries.count { !it.isDirectory }
        } finally {
            stagingDirectory.deleteRecursivelyIfExists()
        }
    }

    fun readEntry(
        archive: Path,
        path: String,
        maxBytes: Long,
        compression: TarCompression = TarCompression.NONE,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): ByteArray = withReadErrors {
        require(maxBytes > 0L) { "读取上限必须大于 0" }
        require(maxBytes <= Int.MAX_VALUE) { "读取上限超过 JVM 数组限制" }
        val output = ByteArrayOutputStream()
        copyEntry(
            archive = archive,
            path = path,
            output = output,
            maxBytes = maxBytes,
            compression = compression,
            limits = limits,
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
        output.toByteArray()
    }

    fun copyEntry(
        archive: Path,
        path: String,
        output: OutputStream,
        maxBytes: Long,
        compression: TarCompression = TarCompression.NONE,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Long = withReadErrors {
        require(maxBytes > 0L) { "读取上限必须大于 0" }
        val normalizedPath = safeEntryPath(path)
        val listedEntry = list(archive, compression, limits).firstOrNull { it.path == normalizedPath }
            ?: throw ArchiveCoreException(
                ArchiveErrorCode.INVALID_PATH,
                "找不到归档条目：$normalizedPath",
            )
        if (listedEntry.isDirectory) {
            throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目录不能作为文件读取")
        }
        val effectiveMaxBytes = minOf(maxBytes, limits.maxExpandedBytes)
        if (listedEntry.size > effectiveMaxBytes) {
            throw ArchiveCoreException(ArchiveErrorCode.LIMIT_EXCEEDED, "条目读取大小超过限制")
        }
        openInput(archive, compression).use { input ->
            while (true) {
                checkCancelled(isCancelled)
                val entry = input.getNextEntry() ?: break
                if (safeEntryPath(entry.name) != normalizedPath) continue
                val buffer = ByteArray(BUFFER_SIZE)
                var readBytes = 0L
                while (true) {
                    checkCancelled(isCancelled)
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    readBytes = checkedAdd(readBytes, read.toLong(), effectiveMaxBytes)
                    try {
                        output.write(buffer, 0, read)
                    } catch (error: IOException) {
                        throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "无法写入输出文件", error)
                    }
                    onProgress(ArchiveProgress(0, 1, readBytes, listedEntry.size))
                }
                onProgress(ArchiveProgress(1, 1, readBytes, listedEntry.size))
                return@withReadErrors readBytes
            }
        }
        throw ArchiveCoreException(ArchiveErrorCode.CORRUPT_ARCHIVE, "找不到归档条目：$normalizedPath")
    }

    private fun collectInputEntries(inputs: List<Path>, limits: ArchiveLimits): List<InputEntry> {
        val entries = mutableListOf<InputEntry>()
        val archivePaths = mutableSetOf<String>()
        inputs.forEach { input ->
            requireExists(input)
            if (Files.isSymbolicLink(input)) {
                throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "不支持符号链接输入：$input")
            }
            val rootName = ArchivePath.normalize(input.fileName.toString())
                ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输入文件名不安全：$input")
            if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(
                    input,
                    emptySet<FileVisitOption>(),
                    Int.MAX_VALUE,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            addInputEntry(
                                entries,
                                archivePaths,
                                InputEntry(directory, rootName + relativeSuffix(input, directory), true),
                                limits,
                            )
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                                throw ArchiveCoreException(
                                    ArchiveErrorCode.INVALID_PATH,
                                    "输入目录包含不支持的文件类型：$file",
                                )
                            }
                            addInputEntry(
                                entries,
                                archivePaths,
                                InputEntry(file, rootName + relativeSuffix(input, file), false),
                                limits,
                            )
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            } else {
                if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
                    throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输入路径不是普通文件：$input")
                }
                addInputEntry(entries, archivePaths, InputEntry(input, rootName, false), limits)
            }
        }
        validatePathRelationships(entries.map { ArchiveEntry(it.archivePath, 0L, it.isDirectory) })
        return entries
    }

    private fun addInputEntry(
        entries: MutableList<InputEntry>,
        archivePaths: MutableSet<String>,
        inputEntry: InputEntry,
        limits: ArchiveLimits,
    ) {
        checkEntryCount(entries.size + 1, limits)
        val normalizedPath = safeEntryPath(inputEntry.archivePath)
        if (!archivePaths.add(normalizedPath)) {
            throw ArchiveCoreException(ArchiveErrorCode.DUPLICATE_ENTRY, "输入包含重复条目：$normalizedPath")
        }
        entries += inputEntry.copy(archivePath = normalizedPath)
    }

    private fun stagedTarget(
        destination: Path,
        stagingDirectory: Path,
        path: String,
        isDirectory: Boolean,
        overwritePolicy: ArchiveOverwritePolicy,
    ): StagedTarget? {
        val output = resolveSafe(destination, path)
        val staged = resolveSafe(stagingDirectory, path)
        ensureNoSymbolicLinks(destination, path)
        ensureOutputParentsAreDirectories(destination, path)
        if (isDirectory) {
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "文件阻挡目录输出：$path")
            }
            return StagedTarget(staged)
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "目录阻挡文件输出：$path")
            }
            when (overwritePolicy) {
                ArchiveOverwritePolicy.SKIP -> return null
                ArchiveOverwritePolicy.RENAME -> {
                    val renamed = uniqueOutputPath(output)
                    val renamedPath = destination.relativize(renamed).toString()
                    ensureNoSymbolicLinks(destination, renamedPath)
                    ensureOutputParentsAreDirectories(destination, renamedPath)
                    return StagedTarget(resolveSafe(stagingDirectory, renamedPath))
                }
                ArchiveOverwritePolicy.REPLACE -> Unit
            }
        }
        return StagedTarget(staged)
    }

    private fun commitStagedFiles(
        stagingDirectory: Path,
        destination: Path,
        overwritePolicy: ArchiveOverwritePolicy,
    ) {
        Files.walkFileTree(
            stagingDirectory,
            emptySet<FileVisitOption>(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (directory != stagingDirectory) {
                        val target = resolveSafe(destination, stagingDirectory.relativize(directory).toString())
                        Files.createDirectories(target)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    val target = resolveSafe(destination, stagingDirectory.relativize(file).toString())
                    Files.createDirectories(target.parent)
                    val moveOptions = if (overwritePolicy == ArchiveOverwritePolicy.REPLACE) {
                        arrayOf(StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        emptyArray()
                    }
                    Files.move(file, target, *moveOptions)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun validatePathRelationships(entries: List<ArchiveEntry>) {
        val entryMap = entries.associateBy { it.path }
        entries.filterNot { it.isDirectory }.forEach { file ->
            var parent = file.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                if (entryMap[parent]?.isDirectory == false) {
                    throw ArchiveCoreException(
                        ArchiveErrorCode.OUTPUT_CONFLICT,
                        "文件与目录路径冲突：${file.path}",
                    )
                }
                parent = parent.substringBeforeLast('/', "")
            }
        }
    }

    private fun validateRegularEntry(entry: TarArchiveEntry) {
        if (!entry.isDirectory && !entry.isFile) {
            throw ArchiveCoreException(
                ArchiveErrorCode.INVALID_PATH,
                "TAR 包含不支持的特殊条目：${entry.name}",
            )
        }
    }

    private fun safeEntryPath(rawPath: String): String =
        ArchivePath.normalize(rawPath)
            ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "归档包含不安全路径：$rawPath")

    private fun openInput(archive: Path, compression: TarCompression): TarArchiveInputStream {
        requireRegularFile(archive)
        val input = BufferedInputStream(Files.newInputStream(archive))
        val decoded = if (compression == TarCompression.ZSTANDARD) ZstdInputStream(input) else input
        return TarArchiveInputStream(decoded)
    }

    private fun openOutput(
        archive: Path,
        compression: TarCompression,
        compressionLevel: Int,
    ): TarArchiveOutputStream {
        val output = BufferedOutputStream(
            Files.newOutputStream(
                archive,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ),
        )
        val encoded = if (compression == TarCompression.ZSTANDARD) {
            ZstdOutputStream(output).apply { setLevel(compressionLevel) }
        } else {
            output
        }
        return TarArchiveOutputStream(encoded).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        }
    }

    private fun ensureOutputDoesNotOverlapInputs(inputs: List<Path>, output: Path) {
        inputs.forEach { input ->
            if (output == input || output.startsWith(input)) {
                throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "输出路径不能位于输入路径中")
            }
            if (Files.exists(output) && Files.exists(input) && Files.isSameFile(output, input)) {
                throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "输出路径不能覆盖输入文件")
            }
        }
    }

    private fun resolveSafe(root: Path, relativePath: String): Path {
        val normalizedPath = safeEntryPath(relativePath)
        val resolved = root.resolve(normalizedPath).normalize()
        if (!resolved.startsWith(root)) {
            throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输出路径越界：$relativePath")
        }
        return resolved
    }

    private fun ensureNoSymbolicLinks(root: Path, relativePath: String) {
        if (Files.isSymbolicLink(root)) {
            throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目标路径包含符号链接：$root")
        }
        var current = root
        safeEntryPath(relativePath).split('/').forEach { part ->
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) {
                throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目标路径包含符号链接：$relativePath")
            }
        }
    }

    private fun ensureOutputParentsAreDirectories(root: Path, relativePath: String) {
        var current = root
        val parts = safeEntryPath(relativePath).split('/')
        parts.dropLast(1).forEach { part ->
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "文件阻挡目录输出：$relativePath")
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

    private fun requireRegularFile(path: Path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "无法访问归档文件：$path")
        }
    }

    private fun requireDirectory(path: Path) {
        if (Files.isSymbolicLink(path)) {
            throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目标目录不能是符号链接：$path")
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "目标路径不是目录：$path")
        }
    }

    private fun requireExists(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "输入路径不存在：$path")
        }
    }

    private fun checkEntryCount(count: Int, limits: ArchiveLimits) {
        if (count > limits.maxEntries) {
            throw ArchiveCoreException(ArchiveErrorCode.LIMIT_EXCEEDED, "归档条目数量超过限制")
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

    private fun relativeSuffix(root: Path, child: Path): String {
        val relative = root.relativize(child).toString()
        return if (relative.isEmpty()) "" else "/$relative"
    }

    private fun Path.deleteRecursivelyIfExists() {
        if (!Files.exists(this, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            this,
            emptySet<FileVisitOption>(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult {
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun <T> withReadErrors(block: () -> T): T {
        try {
            return block()
        } catch (error: ArchiveCoreException) {
            throw error
        } catch (error: SecurityException) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "没有访问归档路径的权限", error)
        } catch (error: IOException) {
            throw ArchiveCoreException(ArchiveErrorCode.CORRUPT_ARCHIVE, "归档损坏或无法读取", error)
        }
    }

    private fun <T> withWriteErrors(block: () -> T): T {
        try {
            return block()
        } catch (error: ArchiveCoreException) {
            throw error
        } catch (error: SecurityException) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "没有访问归档路径的权限", error)
        } catch (error: IOException) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "归档文件操作失败", error)
        }
    }

    private data class InputEntry(
        val pathOnDisk: Path,
        val archivePath: String,
        val isDirectory: Boolean,
    )

    private data class StagedTarget(val stagedPath: Path)

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
    }
}

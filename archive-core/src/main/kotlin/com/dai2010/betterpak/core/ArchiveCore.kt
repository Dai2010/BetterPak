package com.dai2010.betterpak.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ArchiveLimits(
    val maxEntries: Int = 100_000,
    val maxExpandedBytes: Long = 50L * 1024L * 1024L * 1024L,
) {
    init {
        require(maxEntries > 0) { "最大条目数必须大于 0" }
        require(maxExpandedBytes > 0L) { "最大展开体积必须大于 0" }
    }
}

data class ArchiveEntry(
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val compressedSize: Long = -1L,
    val modifiedTimeMillis: Long? = null,
)

data class ArchiveProgress(
    val processedEntries: Int,
    val totalEntries: Int,
    val processedBytes: Long,
    val totalBytes: Long,
)

enum class ArchiveOverwritePolicy {
    REPLACE,
    SKIP,
    RENAME,
}

enum class ZipCompression {
    DEFLATE,
    STORE,
}

enum class ArchiveErrorCode {
    INVALID_PATH,
    LIMIT_EXCEEDED,
    DUPLICATE_ENTRY,
    OUTPUT_CONFLICT,
    PERMISSION_DENIED,
    CORRUPT_ARCHIVE,
    CANCELLED,
}

class ArchiveCoreException(
    val code: ArchiveErrorCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

object ArchivePath {
    fun normalize(rawPath: String?): String? {
        val raw = rawPath?.replace('\\', '/') ?: return null
        if (
            raw.isBlank() ||
            raw.contains('\u0000') ||
            raw.startsWith('/') ||
            raw.startsWith("//") ||
            raw.matches(Regex("^[A-Za-z]:.*$"))
        ) {
            return null
        }

        val parts = raw.split('/')
        if (parts.any { it == ".." }) return null
        val normalized = parts.filter { it.isNotEmpty() && it != "." }
        if (normalized.isEmpty() || normalized.any { it == ".." || it.contains('\u0000') }) return null
        return normalized.joinToString("/")
    }
}

object ArchiveSelection {
    fun includes(path: String, selectedPaths: Set<String>?): Boolean {
        if (selectedPaths == null) return true
        return selectedPaths.any { selected ->
            path == selected || path.startsWith("$selected/")
        }
    }
}

class ZipArchiveCore(
    private val defaultLimits: ArchiveLimits = ArchiveLimits(),
) {
    fun list(
        archive: Path,
        limits: ArchiveLimits = defaultLimits,
    ): List<ArchiveEntry> = withArchiveErrors {
        requireRegularFile(archive)
        ZipFile(archive.toFile()).use { zipFile ->
            val entries = mutableListOf<ArchiveEntry>()
            val paths = mutableSetOf<String>()
            val enumeration = zipFile.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                checkEntryCount(entries.size + 1, limits)
                if (entry.size < -1L || entry.compressedSize < -1L) {
                    throw ArchiveCoreException(
                        ArchiveErrorCode.CORRUPT_ARCHIVE,
                        "归档条目包含无效大小：${entry.name}",
                    )
                }
                val path = safeEntryPath(entry.name)
                if (!paths.add(path)) {
                    throw ArchiveCoreException(
                        ArchiveErrorCode.DUPLICATE_ENTRY,
                        "归档包含重复条目：$path",
                    )
                }
                val size = entry.size
                entries += ArchiveEntry(
                    path = path,
                    size = size,
                    isDirectory = entry.isDirectory,
                    compressedSize = entry.compressedSize,
                    modifiedTimeMillis = entry.time.takeIf { it >= 0L },
                )
            }
            validatePathRelationships(entries)
            entries
        }
    }

    fun create(
        inputs: List<Path>,
        output: Path,
        compressionLevel: Int = 6,
        compression: ZipCompression = ZipCompression.DEFLATE,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Int = withArchiveErrors {
        require(inputs.isNotEmpty()) { "至少需要一个输入路径" }
        require(compressionLevel in 0..9) { "ZIP 压缩级别必须在 0 到 9 之间" }
        val normalizedInputs = inputs.map { it.toAbsolutePath().normalize() }
        val normalizedOutput = output.toAbsolutePath().normalize()
        ensureOutputDoesNotOverlapInputs(normalizedInputs, normalizedOutput)
        val entries = collectInputEntries(normalizedInputs, limits)
        require(entries.isNotEmpty()) { "输入路径没有可归档内容" }
        val totalBytes = entries.sumOf { inputEntry ->
            if (inputEntry.pathOnDisk.isRegularFileWithoutFollowingLinks()) inputEntry.pathOnDisk.fileSize() else 0L
        }
        val temporaryOutput = temporarySibling(normalizedOutput)
        try {
            normalizedOutput.parent?.let { Files.createDirectories(it) }
            ZipOutputStream(
                BufferedOutputStream(
                    Files.newOutputStream(
                        temporaryOutput,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    ),
                ),
            ).use { zipOutput ->
                zipOutput.setLevel(compressionLevel)
                var processedEntries = 0
                var processedBytes = 0L
                entries.forEach { inputEntry ->
                    checkCancelled(isCancelled)
                    val entryName = if (inputEntry.isDirectory) {
                        "${inputEntry.archivePath}/"
                    } else {
                        inputEntry.archivePath
                    }
                    val zipEntry = ZipEntry(entryName).apply {
                        inputEntry.pathOnDisk.lastModifiedTimeMillis()?.let { time = it }
                        if (compression == ZipCompression.STORE) {
                            method = ZipEntry.STORED
                            size = if (inputEntry.isDirectory) 0L else inputEntry.pathOnDisk.fileSize()
                            compressedSize = size
                            crc = if (inputEntry.isDirectory) 0L else crc32(inputEntry.pathOnDisk)
                        }
                    }
                    zipOutput.putNextEntry(zipEntry)
                    if (!inputEntry.isDirectory) {
                        BufferedInputStream(Files.newInputStream(inputEntry.pathOnDisk)).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                checkCancelled(isCancelled)
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                zipOutput.write(buffer, 0, read)
                                processedBytes = checkedAdd(processedBytes, read.toLong(), Long.MAX_VALUE)
                                onProgress(
                                    ArchiveProgress(
                                        processedEntries = processedEntries,
                                        totalEntries = entries.size,
                                        processedBytes = processedBytes,
                                        totalBytes = totalBytes,
                                    ),
                                )
                            }
                        }
                    }
                    zipOutput.closeEntry()
                    processedEntries++
                    onProgress(
                        ArchiveProgress(
                            processedEntries = processedEntries,
                            totalEntries = entries.size,
                            processedBytes = processedBytes,
                            totalBytes = totalBytes,
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
        selectedPaths: Set<String>? = null,
        overwritePolicy: ArchiveOverwritePolicy = ArchiveOverwritePolicy.REPLACE,
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): Int = withArchiveErrors {
        requireRegularFile(archive)
        val normalizedDestination = destination.toAbsolutePath().normalize()
        requireDirectory(normalizedDestination)
        val normalizedSelection = selectedPaths?.map {
            ArchivePath.normalize(it)
                ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "选择路径不安全")
        }?.toSet()
        val listedEntries = list(archive, limits)
        val selectedEntries = listedEntries.filter { entry ->
            ArchiveSelection.includes(entry.path, normalizedSelection)
        }
        var totalBytes = 0L
        selectedEntries.forEach { entry ->
            if (entry.size > 0L) {
                totalBytes = checkedAdd(totalBytes, entry.size, limits.maxExpandedBytes)
            }
        }
        val stagingDirectory = normalizedDestination.resolveSibling(
            ".${normalizedDestination.fileName}.betterpak-${UUID.randomUUID()}",
        )
        try {
            Files.createDirectory(stagingDirectory)
            ZipFile(archive.toFile()).use { zipFile ->
                var processedEntries = 0
                var processedBytes = 0L
                selectedEntries.forEach { listedEntry ->
                    checkCancelled(isCancelled)
                    val target = stagedTarget(
                        normalizedDestination,
                        stagingDirectory,
                        listedEntry.path,
                        listedEntry.isDirectory,
                        overwritePolicy,
                    )
                    if (target == null) {
                        processedEntries++
                        onProgress(
                            ArchiveProgress(
                                processedEntries,
                                selectedEntries.size,
                                processedBytes,
                                totalBytes,
                            ),
                        )
                        return@forEach
                    }
                    if (listedEntry.isDirectory) {
                        Files.createDirectories(target.stagedPath)
                    } else {
                        val zipEntry = zipFile.getEntry(listedEntry.path)
                            ?: throw ArchiveCoreException(
                                ArchiveErrorCode.CORRUPT_ARCHIVE,
                                "找不到归档条目：${listedEntry.path}",
                            )
                        Files.createDirectories(target.stagedPath.parent)
                        BufferedInputStream(zipFile.getInputStream(zipEntry)).use { input ->
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
        limits: ArchiveLimits = defaultLimits,
        isCancelled: () -> Boolean = { false },
        onProgress: (ArchiveProgress) -> Unit = {},
    ): ByteArray = withArchiveErrors {
        require(maxBytes > 0L) { "读取上限必须大于 0" }
        require(maxBytes <= Int.MAX_VALUE) { "读取上限超过 JVM 数组限制" }
        requireRegularFile(archive)
        val normalizedPath = safeEntryPath(path)
        val effectiveMaxBytes = minOf(maxBytes, limits.maxExpandedBytes)
        val listedEntry = list(archive, limits).firstOrNull { it.path == normalizedPath }
            ?: throw ArchiveCoreException(
                ArchiveErrorCode.INVALID_PATH,
                "找不到归档条目：$normalizedPath",
            )
        if (listedEntry.isDirectory) {
            throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目录不能作为文件读取")
        }
        if (listedEntry.size > effectiveMaxBytes) {
            throw ArchiveCoreException(ArchiveErrorCode.LIMIT_EXCEEDED, "条目读取大小超过限制")
        }
        ZipFile(archive.toFile()).use { zipFile ->
            val zipEntry = zipFile.getEntry(normalizedPath)
                ?: throw ArchiveCoreException(ArchiveErrorCode.CORRUPT_ARCHIVE, "找不到归档条目：$normalizedPath")
            BufferedInputStream(zipFile.getInputStream(zipEntry)).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var readBytes = 0L
                while (true) {
                    checkCancelled(isCancelled)
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    readBytes = checkedAdd(readBytes, read.toLong(), effectiveMaxBytes)
                    output.write(buffer, 0, read)
                    onProgress(
                        ArchiveProgress(
                            processedEntries = 0,
                            totalEntries = 1,
                            processedBytes = readBytes,
                            totalBytes = listedEntry.size.takeIf { it >= 0L } ?: 0L,
                        ),
                    )
                }
                onProgress(
                    ArchiveProgress(
                        processedEntries = 1,
                        totalEntries = 1,
                        processedBytes = readBytes,
                        totalBytes = listedEntry.size.takeIf { it >= 0L } ?: readBytes,
                    ),
                )
                output.toByteArray()
            }
        }
    }

    private fun collectInputEntries(inputs: List<Path>, limits: ArchiveLimits): List<InputEntry> {
        val entries = mutableListOf<InputEntry>()
        val archivePaths = mutableSetOf<String>()
        inputs.forEach { input ->
            requireExists(input)
            if (input.isSymbolicLinkWithoutFollowingLinks()) {
                throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "不支持符号链接输入：$input")
            }
            val rootName = ArchivePath.normalize(input.fileName.toString())
                ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输入文件名不安全：$input")
            if (input.isDirectoryWithoutFollowingLinks()) {
                Files.walkFileTree(
                    input,
                    emptySet<FileVisitOption>(),
                    Int.MAX_VALUE,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                            addInputEntry(
                                entries,
                                archivePaths,
                                InputEntry(directory, rootName + relativeSuffix(input, directory), true),
                                limits,
                            )
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                            if (file.isSymbolicLinkWithoutFollowingLinks() || !file.isRegularFileWithoutFollowingLinks()) {
                                throw ArchiveCoreException(
                                    ArchiveErrorCode.INVALID_PATH,
                                    "不支持非普通文件输入：$file",
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
                if (!input.isRegularFileWithoutFollowingLinks()) {
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
        val normalizedPath = ArchivePath.normalize(inputEntry.archivePath)
            ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输入路径不安全：${inputEntry.archivePath}")
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
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
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
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (directory != stagingDirectory) {
                        val target = resolveSafe(destination, stagingDirectory.relativize(directory).toString())
                        Files.createDirectories(target)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
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
        val directories = entries.filter { it.isDirectory }.map { it.path }.toSet()
        entries.filterNot { it.isDirectory }.forEach { file ->
            var parent = file.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                if (entries.any { it.path == parent && !it.isDirectory }) {
                    throw ArchiveCoreException(
                        ArchiveErrorCode.OUTPUT_CONFLICT,
                        "文件与目录路径冲突：${file.path}",
                    )
                }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        directories.forEach { directory ->
            if (directory.isEmpty()) {
                throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目录路径为空")
            }
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

    private fun resolveSafe(root: Path, relativePath: String): Path {
        val normalizedPath = ArchivePath.normalize(relativePath)
            ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输出路径不安全：$relativePath")
        val resolved = root.resolve(normalizedPath).normalize()
        if (!resolved.startsWith(root)) {
            throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "输出路径越界：$relativePath")
        }
        return resolved
    }

    private fun safeEntryPath(rawPath: String): String =
        ArchivePath.normalize(rawPath)
            ?: throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "归档包含不安全路径：$rawPath")

    private fun requireRegularFile(path: Path) {
        if (!path.isRegularFileWithoutFollowingLinks()) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "无法访问归档文件：$path")
        }
    }

    private fun requireDirectory(path: Path) {
        if (Files.isSymbolicLink(path)) {
            throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目标目录不能是符号链接：$path")
        }
        if (!path.isDirectoryWithoutFollowingLinks()) {
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

    private fun Path.isRegularFileWithoutFollowingLinks(): Boolean =
        Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS)

    private fun Path.isDirectoryWithoutFollowingLinks(): Boolean =
        Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS)

    private fun Path.isSymbolicLinkWithoutFollowingLinks(): Boolean = Files.isSymbolicLink(this)

    private fun Path.fileSize(): Long = Files.size(this)

    private fun Path.lastModifiedTimeMillis(): Long? =
        runCatching { Files.getLastModifiedTime(this, LinkOption.NOFOLLOW_LINKS).toMillis() }.getOrNull()

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

    private fun ensureNoSymbolicLinks(root: Path, relativePath: String) {
        var current = root
        if (Files.isSymbolicLink(current)) {
            throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目标路径包含符号链接：$root")
        }
        ArchivePath.normalize(relativePath).orEmpty().split('/').forEach { part ->
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) {
                throw ArchiveCoreException(ArchiveErrorCode.INVALID_PATH, "目标路径包含符号链接：$relativePath")
            }
        }
    }

    private fun ensureOutputParentsAreDirectories(root: Path, relativePath: String) {
        var current = root
        val parts = ArchivePath.normalize(relativePath).orEmpty().split('/')
        parts.dropLast(1).forEach { part ->
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw ArchiveCoreException(ArchiveErrorCode.OUTPUT_CONFLICT, "文件阻挡目录输出：$relativePath")
            }
        }
    }

    private fun <T> withArchiveErrors(block: () -> T): T {
        try {
            return block()
        } catch (error: ArchiveCoreException) {
            throw error
        } catch (error: ZipException) {
            throw ArchiveCoreException(ArchiveErrorCode.CORRUPT_ARCHIVE, "归档损坏或无法读取", error)
        } catch (error: SecurityException) {
            throw ArchiveCoreException(ArchiveErrorCode.PERMISSION_DENIED, "没有访问归档路径的权限", error)
        } catch (error: IOException) {
            val code = if (isCorruptArchiveError(error)) {
                ArchiveErrorCode.CORRUPT_ARCHIVE
            } else {
                ArchiveErrorCode.PERMISSION_DENIED
            }
            throw ArchiveCoreException(code, error.message ?: "归档文件操作失败", error)
        }
    }

    private fun isCorruptArchiveError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (
                "crc" in message ||
                "central directory" in message ||
                "end of central" in message ||
                "zip file" in message ||
                "unexpected end" in message
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun crc32(file: Path): Long {
        val checksum = CRC32()
        BufferedInputStream(Files.newInputStream(file)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                checksum.update(buffer, 0, read)
            }
        }
        return checksum.value
    }

    private data class InputEntry(
        val pathOnDisk: Path,
        val archivePath: String,
        val isDirectory: Boolean,
    )

    private data class StagedTarget(
        val stagedPath: Path,
    )

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
    }
}

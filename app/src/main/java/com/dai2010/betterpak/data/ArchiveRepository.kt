package com.dai2010.betterpak.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.dai2010.betterpak.domain.ArchiveCreateOptions
import com.dai2010.betterpak.domain.ArchiveFormat
import com.dai2010.betterpak.domain.ArchiveItem
import com.dai2010.betterpak.domain.ArchivePreview
import com.dai2010.betterpak.domain.ArchiveProgress
import com.dai2010.betterpak.domain.ArchiveExtractOptions
import com.dai2010.betterpak.domain.ArchivePath
import com.dai2010.betterpak.domain.CompressionAlgorithm
import com.dai2010.betterpak.domain.OverwritePolicy
import com.github.junrar.Archive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.tukaani.xz.LZMA2Options
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

object ArchiveRepository {
    private const val MAX_LIST_ENTRIES = 100_000
    private const val MAX_PREVIEW_BYTES = 8L * 1024L * 1024L
    private const val BUFFER_SIZE = 32 * 1024

    fun supportedArchiveMimeTypes(): Array<String> = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/vnd.rar",
        "application/x-rar-compressed",
        "application/x-rar",
        "application/x-7z-compressed",
        "application/7z",
    )

    suspend fun list(
        context: Context,
        uri: Uri,
        password: String = "",
    ): Result<List<ArchiveItem>> = operation {
        withContext(Dispatchers.IO) {
            val format = detectFormat(context, uri)
            val source = copyToCache(context, uri, format)
            try {
                when (format) {
                    ArchiveFormat.ZIP -> {
                        require(password.isBlank()) { "ZIP 读取暂不支持密码加密" }
                        listZip(source)
                    }
                    ArchiveFormat.RAR -> listRar(source, password)
                    ArchiveFormat.SEVEN_Z -> listSevenZ(source, password)
                    ArchiveFormat.UNKNOWN -> error("暂不支持该压缩包格式")
                }
            } finally {
                source.delete()
            }
        }
    }

    suspend fun extract(
        context: Context,
        archiveUri: Uri,
        destinationUri: Uri,
        selectedPaths: Set<String>?,
        options: ArchiveExtractOptions = ArchiveExtractOptions(),
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<Int> = operation {
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, destinationUri)
                ?: error("无法访问目标目录")
            require(root.isDirectory) { "目标 URI 不是目录" }
            val format = detectFormat(context, archiveUri)
            val source = copyToCache(context, archiveUri, format)
            try {
                when (format) {
                    ArchiveFormat.ZIP -> {
                        require(options.password.isBlank()) { "ZIP 解压暂不支持密码加密" }
                        extractZip(
                            context,
                            source,
                            root,
                            selectedPaths,
                            options,
                            onProgress,
                        )
                    }
                    ArchiveFormat.RAR -> extractRar(
                        context,
                        source,
                        root,
                        selectedPaths,
                        options,
                        onProgress,
                    )
                    ArchiveFormat.SEVEN_Z -> extractSevenZ(
                        context,
                        source,
                        root,
                        selectedPaths,
                        options,
                        onProgress,
                    )
                    ArchiveFormat.UNKNOWN -> error("暂不支持该压缩包格式")
                }
            } finally {
                source.delete()
            }
        }
    }

    suspend fun createZip(
        context: Context,
        inputUris: List<Uri>,
        outputUri: Uri,
        options: ArchiveCreateOptions = ArchiveCreateOptions(algorithm = CompressionAlgorithm.DEFLATE),
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<Int> = operation {
        withContext(Dispatchers.IO) {
            require(inputUris.isNotEmpty()) { "请先选择要打包的文件或目录" }
            require(options.password.isBlank()) { "ZIP 创建暂不支持密码加密" }
            val tempDirectory = File(context.cacheDir, "betterpak-create-${System.nanoTime()}")
                .apply { mkdirs() }
            val outputFile = File(tempDirectory, "archive.zip")
            try {
                val stagedInputs = stageInputs(context, inputUris, tempDirectory, options.threads)
                val totalBytes = stagedInputs.sumOf { if (it.file.isFile) it.file.length() else 0L }
                var processedEntries = 0
                var processedBytes = 0L
                ZipOutputStream(outputFile.outputStream().buffered()).use { archive ->
                    archive.setLevel(options.compressionLevel.coerceIn(0, 9))
                    stagedInputs.forEach { staged ->
                        coroutineContext.ensureActive()
                        val entry = ZipEntry(staged.entryName)
                        if (staged.file.isDirectory) {
                            entry.method = ZipEntry.STORED
                            entry.size = 0L
                            entry.compressedSize = 0L
                            entry.crc = 0L
                        } else if (options.algorithm == CompressionAlgorithm.COPY) {
                            val checksum = crc32(staged.file)
                            entry.method = ZipEntry.STORED
                            entry.size = staged.file.length()
                            entry.compressedSize = staged.file.length()
                            entry.crc = checksum.value
                        }
                        archive.putNextEntry(entry)
                        if (staged.file.isFile) {
                            staged.file.inputStream().use { input ->
                                processedBytes += copyWithProgress(
                                    input,
                                    archive,
                                    totalBytes,
                                    processedBytes,
                                    onProgress,
                                )
                            }
                        }
                        archive.closeEntry()
                        processedEntries++
                        onProgress(
                            ArchiveProgress(
                                processedEntries = processedEntries,
                                totalEntries = stagedInputs.size,
                                processedBytes = processedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                }
                context.contentResolver.openOutputStream(outputUri)?.use { output ->
                    outputFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("无法写入目标文件")
                stagedInputs.count { it.file.isFile }
            } finally {
                tempDirectory.deleteRecursively()
            }
        }
    }

    suspend fun createSevenZ(
        context: Context,
        inputUris: List<Uri>,
        outputUri: Uri,
        options: ArchiveCreateOptions = ArchiveCreateOptions(),
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<Int> = operation {
        withContext(Dispatchers.IO) {
            require(inputUris.isNotEmpty()) { "请先选择要打包的文件或目录" }
            val tempDirectory = File(context.cacheDir, "betterpak-create-${System.nanoTime()}")
                .apply { mkdirs() }
            val outputFile = File(tempDirectory, "archive.7z")
            val password = options.password.takeIf(String::isNotEmpty)?.toCharArray()
            try {
                val stagedInputs = stageInputs(context, inputUris, tempDirectory, options.threads)
                val totalBytes = stagedInputs.sumOf { if (it.file.isFile) it.file.length() else 0L }
                var processedEntries = 0
                var processedBytes = 0L
                val archive = if (password == null) {
                    SevenZOutputFile(outputFile)
                } else {
                    SevenZOutputFile(outputFile, password)
                }
                archive.use { output ->
                    output.setContentMethods(compressionMethods(options))
                    stagedInputs.forEach { staged ->
                        coroutineContext.ensureActive()
                        val entry = output.createArchiveEntry(staged.file, staged.entryName)
                        output.putArchiveEntry(entry)
                        if (staged.file.isFile) {
                            staged.file.inputStream().use { input ->
                                processedBytes += copyWithProgressToSevenZ(
                                    input,
                                    output,
                                    totalBytes,
                                    processedBytes,
                                    onProgress,
                                )
                            }
                        }
                        output.closeArchiveEntry()
                        processedEntries++
                        onProgress(
                            ArchiveProgress(
                                processedEntries = processedEntries,
                                totalEntries = stagedInputs.size,
                                processedBytes = processedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                }
                context.contentResolver.openOutputStream(outputUri)?.use { output ->
                    outputFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("无法写入目标文件")
                stagedInputs.count { it.file.isFile }
            } finally {
                password?.fill('\u0000')
                tempDirectory.deleteRecursively()
            }
        }
    }

    suspend fun preview(
        context: Context,
        uri: Uri,
        path: String,
        password: String = "",
        maxBytes: Long = MAX_PREVIEW_BYTES,
    ): Result<ArchivePreview> = operation {
        withContext(Dispatchers.IO) {
            val normalizedPath = ArchivePath.normalize(path) ?: error("条目路径不安全")
            val format = detectFormat(context, uri)
            val source = copyToCache(context, uri, format)
            try {
                val name = normalizedPath.substringAfterLast('/')
                val mimeType = mimeTypeForName(name)
                val payload = when (format) {
                    ArchiveFormat.ZIP -> {
                        require(password.isBlank()) { "ZIP 预览暂不支持密码加密" }
                        readZipEntry(source, normalizedPath, maxBytes)
                    }
                    ArchiveFormat.RAR -> readRarEntry(source, normalizedPath, password, maxBytes)
                    ArchiveFormat.SEVEN_Z -> readSevenZEntry(source, normalizedPath, password, maxBytes)
                    ArchiveFormat.UNKNOWN -> error("暂不支持该压缩包格式")
                } ?: error("找不到压缩包条目")
                require(payload.size <= maxBytes) { "该文件超过预览大小限制" }
                if (isTextPreview(name, mimeType)) {
                    ArchivePreview(
                        path = normalizedPath,
                        mimeType = "text/plain",
                        text = payload.toString(Charsets.UTF_8).removePrefix("\uFEFF"),
                    )
                } else if (mimeType.startsWith("image/")) {
                    ArchivePreview(path = normalizedPath, mimeType = mimeType, bytes = payload)
                } else if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
                    ArchivePreview(path = normalizedPath, mimeType = mimeType, bytes = payload)
                } else {
                    error("暂不支持预览此类型，请选择解压")
                }
            } finally {
                source.delete()
            }
        }
    }

    fun detectFormat(context: Context, uri: Uri): ArchiveFormat {
        val header = ByteArray(8)
        val count = context.contentResolver.openInputStream(uri)?.use { input -> readAtMost(input, header) } ?: 0
        if (count >= RAR_SIGNATURE.size && header.copyOf(RAR_SIGNATURE.size).contentEquals(RAR_SIGNATURE)) {
            return ArchiveFormat.RAR
        }
        if (count >= SEVEN_Z_SIGNATURE.size && header.copyOf(SEVEN_Z_SIGNATURE.size).contentEquals(SEVEN_Z_SIGNATURE)) {
            return ArchiveFormat.SEVEN_Z
        }
        if (isZipSignature(header, count)) {
            return ArchiveFormat.ZIP
        }
        return when {
            displayName(context, uri).lowercase().endsWith(".zip") -> ArchiveFormat.ZIP
            displayName(context, uri).lowercase().endsWith(".rar") -> ArchiveFormat.RAR
            displayName(context, uri).lowercase().endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            else -> ArchiveFormat.UNKNOWN
        }
    }

    fun persistUriPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private suspend fun listRar(source: File, password: String): List<ArchiveItem> {
        openRar(source, password).use { archive ->
            require(archive.fileHeaders.size <= MAX_LIST_ENTRIES) { "压缩包文件数量超过限制" }
            return archive.fileHeaders.mapNotNull { header ->
                val path = ArchivePath.normalize(header.fileNameString) ?: return@mapNotNull null
                ArchiveItem(
                    path = path,
                    size = header.fullUnpackSize,
                    isDirectory = header.isDirectory,
                    isEncrypted = header.isEncrypted,
                )
            }
        }
    }

    private suspend fun listZip(source: File): List<ArchiveItem> {
        ZipFile(source).use { archive ->
            val items = mutableListOf<ArchiveItem>()
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                coroutineContext.ensureActive()
                if (items.size >= MAX_LIST_ENTRIES) error("压缩包文件数量超过限制")
                val entry = entries.nextElement()
                val path = ArchivePath.normalize(entry.name)
                if (path != null) {
                    items += ArchiveItem(
                        path = path,
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                        modifiedTime = entry.time.takeIf { it >= 0L },
                        isDirectory = entry.isDirectory,
                    )
                }
            }
            return items
        }
    }

    private suspend fun listSevenZ(source: File, password: String): List<ArchiveItem> {
        openSevenZ(source, password).use { archive ->
            val items = mutableListOf<ArchiveItem>()
            var entry = archive.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                if (items.size >= MAX_LIST_ENTRIES) error("压缩包文件数量超过限制")
                val path = ArchivePath.normalize(entry.name)
                if (path != null) {
                    items += ArchiveItem(
                        path = path,
                        size = entry.size,
                        isDirectory = entry.isDirectory,
                    )
                }
                entry = archive.nextEntry
            }
            return items
        }
    }

    private suspend fun extractRar(
        context: Context,
        source: File,
        root: DocumentFile,
        selectedPaths: Set<String>?,
        options: ArchiveExtractOptions,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): Int {
        var extracted = 0
        var expandedBytes = 0L
        openRar(source, options.password).use { archive ->
            val headers = archive.fileHeaders.toList()
            require(headers.size <= options.maxEntries) { "压缩包文件数量超过限制" }
            headers.forEachIndexed { index, header ->
                coroutineContext.ensureActive()
                val path = ArchivePath.normalize(header.fileNameString)
                if (path == null || !isSelected(path, selectedPaths)) return@forEachIndexed
                if (header.isDirectory) {
                    ensureDirectory(root, path)
                    onProgress(ArchiveProgress(index + 1, headers.size, expandedBytes, -1L))
                    return@forEachIndexed
                }
                val output = createOutputFile(root, path, options.overwritePolicy)
                if (output == null) {
                    onProgress(ArchiveProgress(index + 1, headers.size, expandedBytes, -1L))
                    return@forEachIndexed
                }
                val copied = writeEntry(context, output) { stream ->
                    archive.getInputStream(header).use { input ->
                        copyWithLimit(input, stream, expandedBytes, options.maxExpandedBytes)
                    }
                }
                expandedBytes = checkedExpandedBytes(expandedBytes, copied, options)
                extracted++
                onProgress(ArchiveProgress(index + 1, headers.size, expandedBytes, -1L))
            }
        }
        return extracted
    }

    private suspend fun extractZip(
        context: Context,
        source: File,
        root: DocumentFile,
        selectedPaths: Set<String>?,
        options: ArchiveExtractOptions,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): Int {
        var extracted = 0
        var seenEntries = 0
        var expandedBytes = 0L
        ZipFile(source).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                coroutineContext.ensureActive()
                if (++seenEntries > options.maxEntries) error("压缩包文件数量超过限制")
                val entry = entries.nextElement()
                val path = ArchivePath.normalize(entry.name)
                if (path != null && isSelected(path, selectedPaths)) {
                    if (entry.isDirectory) {
                        ensureDirectory(root, path)
                    } else {
                        val output = createOutputFile(root, path, options.overwritePolicy)
                        if (output != null) {
                            val copied = writeEntry(context, output) { stream ->
                                archive.getInputStream(entry).use { input ->
                                    copyWithLimit(input, stream, expandedBytes, options.maxExpandedBytes)
                                }
                            }
                            expandedBytes = checkedExpandedBytes(expandedBytes, copied, options)
                            extracted++
                        }
                    }
                }
                onProgress(ArchiveProgress(seenEntries, 0, expandedBytes, -1L))
            }
        }
        return extracted
    }

    private suspend fun extractSevenZ(
        context: Context,
        source: File,
        root: DocumentFile,
        selectedPaths: Set<String>?,
        options: ArchiveExtractOptions,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): Int {
        var extracted = 0
        var seenEntries = 0
        var expandedBytes = 0L
        openSevenZ(source, options.password).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                if (++seenEntries > options.maxEntries) error("压缩包文件数量超过限制")
                val path = ArchivePath.normalize(entry.name)
                if (path != null && isSelected(path, selectedPaths)) {
                    if (entry.isDirectory) {
                        ensureDirectory(root, path)
                    } else {
                        val output = createOutputFile(root, path, options.overwritePolicy)
                        if (output != null) {
                            val copied = writeEntry(context, output) { stream ->
                                copyWithLimitFromSevenZ(archive, stream, expandedBytes, options.maxExpandedBytes)
                            }
                            expandedBytes = checkedExpandedBytes(expandedBytes, copied, options)
                            extracted++
                        }
                    }
                }
                onProgress(ArchiveProgress(seenEntries, 0, expandedBytes, -1L))
                entry = archive.nextEntry
            }
        }
        return extracted
    }

    private suspend fun writeEntry(
        context: Context,
        target: TargetFile,
        writer: suspend (OutputStream) -> Long,
    ): Long {
        val temporary = target.parent.createFile(
            "application/octet-stream",
            ".betterpak-${UUID.randomUUID()}.part",
        ) ?: error("无法创建临时输出文件：${target.name}")
        return try {
            val copied = context.contentResolver.openOutputStream(temporary.uri)?.use { output ->
                writer(output)
            } ?: error("无法写入临时输出文件：${target.name}")
            target.existing?.let { existing ->
                if (!existing.delete()) error("无法替换已有文件：${target.name}")
            }
            if (!temporary.renameTo(target.name)) error("无法完成输出文件替换：${target.name}")
            copied
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun createOutputFile(
        root: DocumentFile,
        path: String,
        overwritePolicy: OverwritePolicy,
    ): TargetFile? {
        val parts = path.split('/').filter(String::isNotEmpty)
        if (parts.isEmpty()) return null
        val parent = ensureDirectory(root, parts.dropLast(1).joinToString("/"))
        val name = parts.last()
        val existing = parent.findFile(name)
        return when (overwritePolicy) {
            OverwritePolicy.REPLACE -> TargetFile(parent, name, existing)
            OverwritePolicy.SKIP -> if (existing == null) TargetFile(parent, name, null) else null
            OverwritePolicy.RENAME -> {
                val extension = name.substringAfterLast('.', "")
                val stem = if (extension.isBlank()) name else name.removeSuffix(".$extension")
                var index = 1
                var candidate = name
                while (parent.findFile(candidate) != null) {
                    candidate = if (extension.isBlank()) {
                        "$stem ($index)"
                    } else {
                        "$stem ($index).$extension"
                    }
                    index++
                }
                TargetFile(parent, candidate, null)
            }
        }
    }

    private fun compressionMethods(options: ArchiveCreateOptions): List<SevenZMethodConfiguration> {
        val level = options.compressionLevel.coerceIn(0, 9)
        return when (options.algorithm) {
            CompressionAlgorithm.COPY -> listOf(SevenZMethodConfiguration(SevenZMethod.COPY))
            CompressionAlgorithm.DEFLATE -> listOf(
                SevenZMethodConfiguration(SevenZMethod.DEFLATE, level.coerceAtLeast(1)),
            )
            CompressionAlgorithm.BZIP2 -> listOf(
                SevenZMethodConfiguration(SevenZMethod.BZIP2, level.coerceAtLeast(1)),
            )
            CompressionAlgorithm.LZMA2 -> {
                val lzmaOptions = LZMA2Options().apply { setPreset(level) }
                listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, lzmaOptions))
            }
        }
    }

    private suspend fun stageInputs(
        context: Context,
        inputUris: List<Uri>,
        tempDirectory: File,
        threads: Int,
    ): List<StagedInput> = coroutineScope {
        val inputDispatcher = Dispatchers.IO.limitedParallelism(threads.coerceIn(1, 8))
        inputUris.mapIndexed { index, uri ->
            async(inputDispatcher) {
                val root = File(tempDirectory, "input-$index").apply { mkdirs() }
                val tree = DocumentFile.fromTreeUri(context, uri)
                if (tree?.isDirectory == true) {
                    val rootName = safeSegment(tree.name, "folder-$index")
                    val destination = File(root, rootName).apply { mkdirs() }
                    copyDocumentTree(context, tree, destination)
                    collectStagedFiles(destination, rootName)
                } else {
                    val source = DocumentFile.fromSingleUri(context, uri)
                    val name = safeSegment(source?.name ?: displayName(context, uri), "file-$index")
                    val destination = File(root, name)
                    copyUriToFile(context, uri, destination)
                    listOf(StagedInput(destination, name))
                }
            }
        }.awaitAll().flatten()
    }

    private fun copyDocumentTree(context: Context, source: DocumentFile, destination: File) {
        source.listFiles().forEachIndexed { index, child ->
            val name = safeSegment(child.name, "item-$index")
            val target = File(destination, name)
            if (child.isDirectory) {
                target.mkdirs()
                copyDocumentTree(context, child, target)
            } else if (child.isFile) {
                copyUriToFile(context, child.uri, target)
            }
        }
    }

    private fun collectStagedFiles(root: File, entryRoot: String): List<StagedInput> {
        val result = mutableListOf<StagedInput>()
        fun visit(file: File, relativePath: String) {
            result += StagedInput(file, relativePath)
            if (file.isDirectory) {
                file.listFiles().orEmpty().forEach { child ->
                    visit(child, "$relativePath/${child.name}")
                }
            }
        }
        visit(root, entryRoot)
        return result
    }

    private fun readRarEntry(source: File, path: String, password: String, maxBytes: Long): ByteArray? {
        openRar(source, password).use { archive ->
            val header = archive.fileHeaders.firstOrNull { ArchivePath.normalize(it.fileNameString) == path } ?: return null
            return archive.getInputStream(header).use { readPreviewBytes(it, maxBytes) }
        }
    }

    private fun readZipEntry(source: File, path: String, maxBytes: Long): ByteArray? {
        ZipFile(source).use { archive ->
            val entry = archive.entries().asSequence().firstOrNull {
                ArchivePath.normalize(it.name) == path
            } ?: return null
            return archive.getInputStream(entry).use { readPreviewBytes(it, maxBytes) }
        }
    }

    private fun crc32(file: File): CRC32 {
        val checksum = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                checksum.update(buffer, 0, read)
            }
        }
        return checksum
    }

    private fun readSevenZEntry(source: File, path: String, password: String, maxBytes: Long): ByteArray? {
        openSevenZ(source, password).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (ArchivePath.normalize(entry.name) == path) {
                    return readPreviewBytesFromSevenZ(archive, maxBytes)
                }
                entry = archive.nextEntry
            }
        }
        return null
    }

    private fun readPreviewBytes(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) error("该文件超过预览大小限制")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun isTextPreview(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("text/")) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in setOf("json", "xml", "yaml", "yml", "toml", "ini", "csv", "md", "kt", "java", "py", "js", "css", "html", "log")) {
            return true
        }
        return false
    }

    private fun mimeTypeForName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "aac" -> "audio/aac"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "mka" -> "audio/x-matroska"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "oga", "ogg" -> "audio/ogg"
                "opus" -> "audio/opus"
                "wav" -> "audio/wav"
                "webm" -> "video/webm"
                else -> "application/octet-stream"
            }
    }

    private fun checkedExpandedBytes(current: Long, next: Long, options: ArchiveExtractOptions): Long {
        require(next >= 0L && current <= options.maxExpandedBytes && next <= options.maxExpandedBytes - current) {
            "压缩包展开大小超过限制"
        }
        return current + next
    }

    private fun copyWithLimit(
        input: InputStream,
        output: OutputStream,
        currentBytes: Long,
        maxBytes: Long,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            copied += read
            require(currentBytes <= maxBytes && copied <= maxBytes - currentBytes) {
                "压缩包展开大小超过限制"
            }
            output.write(buffer, 0, read)
        }
        return copied
    }

    private suspend fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        initialBytes: Long,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            copied += read
            onProgress(ArchiveProgress(0, 0, initialBytes + copied, totalBytes))
        }
        return copied
    }

    private suspend fun copyWithProgressToSevenZ(
        input: InputStream,
        output: SevenZOutputFile,
        totalBytes: Long,
        initialBytes: Long,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            copied += read
            onProgress(ArchiveProgress(0, 0, initialBytes + copied, totalBytes))
        }
        return copied
    }

    private suspend fun copyWithLimitFromSevenZ(
        input: SevenZFile,
        output: OutputStream,
        currentBytes: Long,
        maxBytes: Long,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            copied += read
            require(currentBytes <= maxBytes && copied <= maxBytes - currentBytes) {
                "压缩包展开大小超过限制"
            }
            output.write(buffer, 0, read)
        }
        return copied
    }

    private fun readPreviewBytesFromSevenZ(input: SevenZFile, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) error("该文件超过预览大小限制")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun openRar(source: File, password: String): Archive {
        return if (password.isBlank()) Archive(source) else Archive(source, password)
    }

    private fun openSevenZ(source: File, password: String): SevenZFile {
        return if (password.isBlank()) SevenZFile(source) else SevenZFile(source, password.toCharArray())
    }

    private fun ensureDirectory(root: DocumentFile, path: String): DocumentFile {
        var current = root
        path.split('/').filter(String::isNotEmpty).forEach { segment ->
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment)
                existing.isDirectory -> existing
                else -> error("输出路径中的目录名已被文件占用：$segment")
            } ?: error("无法创建目录：$segment")
        }
        return current
    }

    private fun copyToCache(context: Context, uri: Uri, format: ArchiveFormat): File {
        val suffix = when (format) {
            ArchiveFormat.ZIP -> ".zip"
            ArchiveFormat.RAR -> ".rar"
            ArchiveFormat.SEVEN_Z -> ".7z"
            ArchiveFormat.UNKNOWN -> ".archive"
        }
        return File.createTempFile("betterpak-source-", suffix, context.cacheDir).also {
            copyUriToFile(context, uri, it)
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取文件")
        input.use { source ->
            destination.outputStream().use { target -> source.copyTo(target) }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文件"
    }

    private fun safeSegment(name: String?, fallback: String): String {
        val value = name.orEmpty().replace('\\', '_').replace('/', '_').trim()
        return value.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: fallback
    }

    private fun isSelected(path: String, selectedPaths: Set<String>?): Boolean {
        if (selectedPaths == null) return true
        return selectedPaths.any { selected -> path == selected || path.startsWith("$selected/") }
    }

    private fun readAtMost(input: InputStream, buffer: ByteArray): Int {
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read <= 0) break
            count += read
        }
        return count
    }

    private fun isZipSignature(header: ByteArray, count: Int): Boolean {
        if (count < 4 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) return false
        return header[2] == 0x03.toByte() && header[3] == 0x04.toByte() ||
            header[2] == 0x05.toByte() && header[3] == 0x06.toByte() ||
            header[2] == 0x07.toByte() && header[3] == 0x08.toByte()
    }

    private suspend fun <T> operation(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private data class TargetFile(
        val parent: DocumentFile,
        val name: String,
        val existing: DocumentFile?,
    )

    private data class StagedInput(
        val file: File,
        val entryName: String,
    )

    private val RAR_SIGNATURE = byteArrayOf(
        0x52.toByte(), 0x61.toByte(), 0x72.toByte(), 0x21.toByte(), 0x1A.toByte(), 0x07.toByte(),
    )

    private val SEVEN_Z_SIGNATURE = byteArrayOf(
        0x37.toByte(), 0x7A.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27.toByte(), 0x1C.toByte(),
    )

}

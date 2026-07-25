package com.dai2010.betterpak.domain

import android.content.Context
import android.net.Uri
import java.io.File

interface ArchiveEngine {
    fun supportedArchiveMimeTypes(): Array<String>

    fun initializeAppStorage(context: Context)

    fun cleanupTemporaryFiles(context: Context)

    suspend fun list(
        context: Context,
        uri: Uri,
        password: String = "",
    ): Result<List<ArchiveItem>>

    suspend fun extract(
        context: Context,
        archiveUri: Uri,
        destinationUri: Uri,
        selectedPaths: Set<String>?,
        options: ArchiveExtractOptions = ArchiveExtractOptions(),
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<Int>

    suspend fun createZip(
        context: Context,
        inputUris: List<Uri>,
        outputUri: Uri,
        options: ArchiveCreateOptions = ArchiveCreateOptions(algorithm = CompressionAlgorithm.DEFLATE),
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<Int>

    suspend fun createSevenZ(
        context: Context,
        inputUris: List<Uri>,
        outputUri: Uri,
        options: ArchiveCreateOptions = ArchiveCreateOptions(),
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<Int>

    suspend fun createTar(
        context: Context,
        inputUris: List<Uri>,
        outputUri: Uri,
        options: ArchiveCreateOptions = ArchiveCreateOptions(algorithm = CompressionAlgorithm.COPY),
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<Int>

    suspend fun createTarZstandard(
        context: Context,
        inputUris: List<Uri>,
        outputUri: Uri,
        options: ArchiveCreateOptions = ArchiveCreateOptions(algorithm = CompressionAlgorithm.COPY),
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<Int>

    suspend fun preview(
        context: Context,
        uri: Uri,
        path: String,
        password: String = "",
        maxBytes: Long = 8L * 1024L * 1024L,
    ): Result<ArchivePreview>

    suspend fun extractEntryToInternalStorage(
        context: Context,
        archiveUri: Uri,
        path: String,
        password: String = "",
        maxBytes: Long = 50L * 1024L * 1024L * 1024L,
    ): Result<File>

    suspend fun extractEntryToCache(
        context: Context,
        archiveUri: Uri,
        path: String,
        password: String = "",
        maxBytes: Long = 50L * 1024L * 1024L * 1024L,
    ): Result<File>

    fun mimeTypeForPath(path: String): String

    fun detectFormat(context: Context, uri: Uri): ArchiveFormat

    fun persistUriPermission(context: Context, uri: Uri)
}

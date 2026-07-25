package com.dai2010.betterpak.domain

enum class ArchiveFormat(
    val label: String,
    val supportsList: Boolean,
    val supportsCreate: Boolean,
    val supportsExtract: Boolean,
    val supportsPreview: Boolean,
    val supportsPassword: Boolean,
    val supportsDirectoryListing: Boolean,
    val supportsStream: Boolean,
    val supportsStreamPreview: Boolean = supportsStream,
) {
    ZIP(
        label = "ZIP",
        supportsList = true,
        supportsCreate = true,
        supportsExtract = true,
        supportsPreview = true,
        supportsPassword = false,
        supportsDirectoryListing = true,
        supportsStream = false,
    ),
    RAR(
        label = "RAR / RAR5",
        supportsList = true,
        supportsCreate = false,
        supportsExtract = true,
        supportsPreview = true,
        supportsPassword = true,
        supportsDirectoryListing = true,
        supportsStream = false,
    ),
    SEVEN_Z(
        label = "7z",
        supportsList = true,
        supportsCreate = true,
        supportsExtract = true,
        supportsPreview = true,
        supportsPassword = true,
        supportsDirectoryListing = true,
        supportsStream = false,
    ),
    TAR(
        label = "TAR",
        supportsList = true,
        supportsCreate = true,
        supportsExtract = true,
        supportsPreview = true,
        supportsPassword = false,
        supportsDirectoryListing = true,
        supportsStream = true,
    ),
    ZSTANDARD(
        label = "Zstandard (.zst)",
        supportsList = true,
        supportsCreate = false,
        supportsExtract = true,
        supportsPreview = true,
        supportsPassword = false,
        supportsDirectoryListing = false,
        supportsStream = true,
    ),
    TAR_ZSTANDARD(
        label = "TAR + Zstandard",
        supportsList = true,
        supportsCreate = true,
        supportsExtract = true,
        supportsPreview = true,
        supportsPassword = false,
        supportsDirectoryListing = true,
        supportsStream = true,
    ),
    UNKNOWN(
        label = "未知格式",
        supportsList = false,
        supportsCreate = false,
        supportsExtract = false,
        supportsPreview = false,
        supportsPassword = false,
        supportsDirectoryListing = false,
        supportsStream = false,
    ),
}

data class ArchiveItem(
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val compressedSize: Long = -1L,
    val modifiedTime: Long? = null,
    val isEncrypted: Boolean = false,
)

data class ArchiveProgress(
    val processedEntries: Int,
    val totalEntries: Int,
    val processedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0L -> (processedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            totalEntries > 0 -> (processedEntries.toFloat() / totalEntries).coerceIn(0f, 1f)
            else -> 0f
        }
}

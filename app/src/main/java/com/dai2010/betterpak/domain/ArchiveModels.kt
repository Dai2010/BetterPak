package com.dai2010.betterpak.domain

enum class ArchiveFormat(val label: String) {
    ZIP("ZIP"),
    RAR("RAR / RAR5"),
    SEVEN_Z("7z"),
    UNKNOWN("未知格式"),
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

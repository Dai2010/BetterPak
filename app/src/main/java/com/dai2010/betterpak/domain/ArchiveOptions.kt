package com.dai2010.betterpak.domain

enum class CompressionAlgorithm(val label: String) {
    LZMA2("LZMA2"),
    DEFLATE("Deflate"),
    BZIP2("BZip2"),
    COPY("仅存储"),
}

enum class OverwritePolicy(val label: String) {
    REPLACE("覆盖已有文件"),
    SKIP("跳过已有文件"),
    RENAME("自动重命名"),
}

data class ArchiveCreateOptions(
    val password: String = "",
    val algorithm: CompressionAlgorithm = CompressionAlgorithm.LZMA2,
    val compressionLevel: Int = 5,
    val threads: Int = 2,
)

data class ArchiveExtractOptions(
    val password: String = "",
    val overwritePolicy: OverwritePolicy = OverwritePolicy.REPLACE,
    val safePaths: Boolean = true,
    val maxEntries: Int = 100_000,
    val maxExpandedBytes: Long = 50L * 1024L * 1024L * 1024L,
    val threads: Int = 2,
)

data class ArchivePreview(
    val path: String,
    val mimeType: String,
    val text: String? = null,
    val bytes: ByteArray? = null,
    val truncated: Boolean = false,
)

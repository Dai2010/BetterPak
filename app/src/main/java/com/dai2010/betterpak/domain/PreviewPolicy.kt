package com.dai2010.betterpak.domain

enum class PreviewKind {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    EXTERNAL_DOCUMENT,
    UNSUPPORTED,
}

data class PreviewDecision(
    val kind: PreviewKind,
    val mimeType: String,
)

object PreviewPolicy {
    private val textExtensions = setOf(
        "c",
        "cc",
        "cpp",
        "css",
        "csv",
        "h",
        "html",
        "ini",
        "java",
        "js",
        "json",
        "kt",
        "log",
        "md",
        "py",
        "toml",
        "ts",
        "txt",
        "xml",
        "yaml",
        "yml",
    )

    private val externalDocumentMimeTypes = mapOf(
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
    )

    private val fallbackMimeTypes = mapOf(
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "mka" to "audio/x-matroska",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "oga" to "audio/ogg",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "webm" to "video/webm",
    )

    fun decide(path: String, mimeType: String = mimeTypeForName(path)): PreviewDecision {
        val extension = extensionOf(path)
        val normalizedMimeType = mimeType.lowercase()
        val kind = when {
            extension in externalDocumentMimeTypes -> PreviewKind.EXTERNAL_DOCUMENT
            normalizedMimeType.startsWith("text/") || extension in textExtensions -> PreviewKind.TEXT
            normalizedMimeType.startsWith("image/") -> PreviewKind.IMAGE
            normalizedMimeType.startsWith("audio/") -> PreviewKind.AUDIO
            normalizedMimeType.startsWith("video/") -> PreviewKind.VIDEO
            else -> PreviewKind.UNSUPPORTED
        }
        return PreviewDecision(kind, mimeType)
    }

    fun mimeTypeForName(name: String): String {
        val extension = extensionOf(name)
        return externalDocumentMimeTypes[extension]
            ?: fallbackMimeTypes[extension]
            ?: when (extension) {
                "bmp" -> "image/bmp"
                "gif" -> "image/gif"
                "jpeg", "jpg" -> "image/jpeg"
                "png" -> "image/png"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
    }

    fun isExternalDocument(path: String): Boolean =
        decide(path).kind == PreviewKind.EXTERNAL_DOCUMENT

    private fun extensionOf(path: String): String =
        path.substringAfterLast('/', path).substringAfterLast('.', "").lowercase()
}

package com.dai2010.betterpak.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.FileNotFoundException
import java.util.zip.ZipException

class ArchiveErrorsTest {
    @Test
    fun classifiesSafetyAndRetryFailures() {
        assertEquals(
            ArchiveErrorCode.INVALID_PATH,
            ArchiveErrorClassifier.classify(IllegalArgumentException("条目路径不安全")),
        )
        assertEquals(
            ArchiveErrorCode.LIMIT_EXCEEDED,
            ArchiveErrorClassifier.classify(IllegalArgumentException("压缩包展开大小超过限制")),
        )
        assertEquals(
            ArchiveErrorCode.PERMISSION_REVOKED,
            ArchiveErrorClassifier.classify(IllegalStateException("无法访问目标目录")),
        )
    }

    @Test
    fun prefersTypedAndTransportErrorMappings() {
        assertEquals(
            ArchiveErrorCode.INVALID_PATH,
            ArchiveErrorClassifier.classify(
                ArchiveOperationException(ArchiveErrorCode.INVALID_PATH, "invalid path"),
            ),
        )
        assertEquals(
            ArchiveErrorCode.PERMISSION_REVOKED,
            ArchiveErrorClassifier.classify(FileNotFoundException("private uri")),
        )
        assertEquals(
            ArchiveErrorCode.CORRUPT_ARCHIVE,
            ArchiveErrorClassifier.classify(ZipException("checksum mismatch")),
        )
    }

    @Test
    fun translatesInvalidUriToActionableChineseMessage() {
        val error = ArchiveErrorClassifier.wrap(
            IllegalArgumentException("Invalid URI: content://provider/document/item"),
        )

        assertEquals(ArchiveErrorCode.INVALID_PATH, ArchiveErrorClassifier.classify(error))
        assertEquals("所选内容不是有效的文件或目录，请重新选择后重试", error.message)
        assertFalse(error.message.orEmpty().contains("Invalid URI"))
    }

    @Test
    fun translatesRevokedPermissionToActionableChineseMessage() {
        val error = ArchiveErrorClassifier.wrap(FileNotFoundException("permission denied"))

        assertEquals(ArchiveErrorCode.PERMISSION_REVOKED, ArchiveErrorClassifier.classify(error))
        assertEquals(
            "无法访问所选内容，文件可能已被删除或权限已撤销，请重新选择后重试",
            error.message,
        )
    }
}

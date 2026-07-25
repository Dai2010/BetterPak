package com.dai2010.betterpak.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPolicyTest {
    @Test
    fun classifiesOfficeDocumentsForExternalHandling() {
        val decision = PreviewPolicy.decide("report.docx")

        assertEquals(PreviewKind.EXTERNAL_DOCUMENT, decision.kind)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            decision.mimeType,
        )
        assertTrue(PreviewPolicy.isExternalDocument("report.pdf"))
    }

    @Test
    fun classifiesSafeInAppPreviewTypes() {
        assertEquals(PreviewKind.TEXT, PreviewPolicy.decide("notes.md").kind)
        assertEquals(PreviewKind.IMAGE, PreviewPolicy.decide("photo.jpg").kind)
        assertEquals(PreviewKind.AUDIO, PreviewPolicy.decide("sound.flac").kind)
        assertEquals(PreviewKind.VIDEO, PreviewPolicy.decide("clip.mkv").kind)
        assertEquals(PreviewKind.UNSUPPORTED, PreviewPolicy.decide("payload.bin").kind)
    }
}

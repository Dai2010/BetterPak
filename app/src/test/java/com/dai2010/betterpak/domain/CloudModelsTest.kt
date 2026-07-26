package com.dai2010.betterpak.domain

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudModelsTest {
    @Test
    fun mapsHttpFailuresToSafeRetryPolicy() {
        assertEquals(CloudErrorCode.AUTH_EXPIRED, CloudErrorClassifier.fromHttpStatus(401))
        assertEquals(CloudErrorCode.NOT_FOUND, CloudErrorClassifier.fromHttpStatus(404))
        assertEquals(CloudErrorCode.QUOTA_EXCEEDED, CloudErrorClassifier.fromHttpStatus(429))
        assertEquals(CloudErrorCode.NETWORK_UNAVAILABLE, CloudErrorClassifier.fromHttpStatus(503))
        assertTrue(CloudErrorCode.AUTH_EXPIRED.retryable)
        assertFalse(CloudErrorCode.ACCESS_DENIED.retryable)
    }

    @Test
    fun classifiesTransportFailuresWithoutExposingDetails() {
        assertEquals(
            CloudErrorCode.TIMEOUT,
            CloudErrorClassifier.classify(SocketTimeoutException("token=must-not-be-logged")),
        )
        assertEquals(
            CloudErrorCode.NETWORK_UNAVAILABLE,
            CloudErrorClassifier.classify(IOException("private response body")),
        )
        assertEquals("CloudAccessToken(redacted)", CloudAccessToken("secret", 0L).toString())
        assertEquals(CloudErrorCode.LIMIT_EXCEEDED, CloudErrorClassifier.classify(
            CloudOperationException(CloudErrorCode.LIMIT_EXCEEDED, "too large"),
        ))
        assertTrue(CloudTransferPolicy.MAX_LOCAL_DOWNLOAD_BYTES > 0L)
    }
}

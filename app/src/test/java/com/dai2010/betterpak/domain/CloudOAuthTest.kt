package com.dai2010.betterpak.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudOAuthTest {
    @Test
    fun createsPkceSessionWithoutLoggingVerifier() {
        val session = CloudOAuth.createSession()

        assertTrue(session.state.isNotBlank())
        assertTrue(session.codeVerifier.isNotBlank())
        assertTrue(session.codeChallenge.isNotBlank())
        assertFalse(session.toString().contains(session.codeVerifier))
        assertEquals("CloudOAuthSession(redacted)", session.toString())
        assertTrue(CloudOAuth.validateCallback(session, session.state, "authorization-code"))
        assertFalse(CloudOAuth.validateCallback(session, "wrong-state", "authorization-code"))
        assertFalse(CloudOAuth.validateCallback(session, session.state, ""))
    }
}

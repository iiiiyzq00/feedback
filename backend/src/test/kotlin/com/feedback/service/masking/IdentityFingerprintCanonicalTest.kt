package com.feedback.service.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class IdentityFingerprintCanonicalTest {
    private val service = IdentityFingerprintService(
        "identity-secret-that-is-longer-than-32-characters", "test-key"
    )

    @Test
    fun `canonical batch path preserves governed normalization and fingerprint result`() {
        val canonical = service.normalizeCanonical("+86 138-0013-8000", "PHONE")

        assertEquals("13800138000", canonical)
        assertArrayEquals(service.fingerprint("+86 138-0013-8000", "PHONE"), service.fingerprintCanonical(canonical, "PHONE"))
    }

    @Test
    fun `canonical fingerprint rejects an unnormalized value`() {
        val error = assertThrows(ClientException::class.java) {
            service.fingerprintCanonical("+86 138-0013-8000", "PHONE")
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("IDENTITY_VALUE_NOT_CANONICAL", error.reasonCode)
    }
}

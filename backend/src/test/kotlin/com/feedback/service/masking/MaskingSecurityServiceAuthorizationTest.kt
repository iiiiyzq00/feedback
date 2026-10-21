package com.feedback.service.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.security.WebPrincipal
import com.feedback.service.imports.ImportRawCrypto
import com.feedback.security.sdk.TransportKeyService
import com.feedback.service.station.StationRawPayloadService
import com.feedback.repository.masking.MaskingSecurityRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class MaskingSecurityServiceAuthorizationTest {
    @AfterEach
    fun clearAuthentication() = SecurityContextHolder.clearContext()

    @Test
    fun `ordinary authenticated user cannot close masking exceptions through service`() {
        val principal = WebPrincipal(9, "viewer", "", "Viewer", setOf("USER"), setOf("feedback:read"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
        val service = MaskingSecurityService(
            mockk<MaskingSecurityRepository>(relaxed = true),
            mockk<ContactStorageCrypto>(),
            jacksonObjectMapper(),
            mockk<ImportRawCrypto>(),
            mockk<TransportKeyService>(),
            mockk<EnvelopeCrypto>(),
            mockk<StationRawPayloadService>()
        )

        val error = assertThrows(ClientException::class.java) {
            service.closeSdkExceptions(
                SdkExceptionCloseRequest(
                    receiptIds = listOf("00000000000000000000000000000000"),
                    reason = "NO_FURTHER_ACTION",
                    note = "只读用户不得关闭隔离记录"
                )
            )
        }
        assertEquals(ErrorCode.AUTH_FORBIDDEN, error.errorCode)
        assertEquals("MASKING_EXCEPTION_CLOSE_ROLE_REQUIRED", error.reasonCode)
    }
}

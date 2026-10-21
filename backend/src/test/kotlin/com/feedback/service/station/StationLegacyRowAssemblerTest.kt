package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.service.masking.ContactStorageCrypto
import com.feedback.service.masking.IdentityFingerprintService
import com.feedback.service.masking.MaskingPipeline
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StationLegacyRowAssemblerTest {
    private val masking = mockk<MaskingPipeline>()
    private val contactCrypto = mockk<ContactStorageCrypto>()
    private val fingerprints = mockk<IdentityFingerprintService>()

    @Test
    fun `plain governed mobile bypasses legacy decryptor and is normalized`() {
        val decryptor = mockk<LegacyStationPhoneDecryptor>()
        every { fingerprints.normalizeCanonical("13800138000", "PHONE") } returns "13800138000"
        val assembler = StationLegacyRowAssembler(masking, decryptor, contactCrypto, fingerprints)

        val result = assembler.phone("13800138000")

        assertEquals(StationLegacyPhoneDecision.Valid("13800138000"), result)
        verify(exactly = 0) { decryptor.available() }
        verify(exactly = 0) { decryptor.decrypt(any()) }
    }

    @Test
    fun `encrypted contact without configured key is a system failure`() {
        val assembler = StationLegacyRowAssembler(
            masking, LegacyStationPhoneDecryptor(""), contactCrypto, fingerprints
        )

        val error = assertThrows(ServiceException::class.java) { assembler.phone("encrypted-value") }

        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("STATION_LEGACY_PHONE_KEY_NOT_CONFIGURED", error.reasonCode)
    }

    @Test
    fun `bad ciphertext is isolated as an invalid source contact`() {
        val assembler = StationLegacyRowAssembler(
            masking, LegacyStationPhoneDecryptor("1234567890123456"), contactCrypto, fingerprints
        )

        val result = assembler.phone("not-base64!")

        assertInstanceOf(StationLegacyPhoneDecision.Invalid::class.java, result)
        assertEquals("STATION_PHONE_BASE64_INVALID", (result as StationLegacyPhoneDecision.Invalid).errorCode)
    }
}

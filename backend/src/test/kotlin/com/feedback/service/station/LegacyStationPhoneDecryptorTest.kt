package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LegacyStationPhoneDecryptorTest {
    @Test
    fun `decrypts legacy AES ECB base64 vector with padded key`() {
        val decryptor = LegacyStationPhoneDecryptor("legacy-key")
        // 由 AES/ECB/PKCS5Padding 生成，密钥字节为 "legacy-key000000"。
        assertEquals("13800138000", decryptor.decrypt("Y9GWaQhvub833G7hoGIfuA=="))
    }

    @Test
    fun `decrypts production legacy algorithm vector without embedding the key in application code`() {
        val decryptor = LegacyStationPhoneDecryptor("station-phone123")
        assertEquals("13800138000", decryptor.decrypt("ziAkP7BwGgwXxKqicIUCUQ=="))
    }

    @Test
    fun `rejects malformed legacy ciphertext with stable code`() {
        val error = assertThrows(ClientException::class.java) {
            LegacyStationPhoneDecryptor("legacy-key").decrypt("not-base64")
        }
        assertEquals("STATION_PHONE_BASE64_INVALID", error.reasonCode)
    }

    @Test
    fun `does not silently enable compatibility without configured key`() {
        val decryptor = LegacyStationPhoneDecryptor("")
        assertEquals(false, decryptor.available())
        assertThrows(ServiceException::class.java) { decryptor.decrypt("AA==") }
    }
}

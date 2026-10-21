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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StationPhoneResolverTest {
    private val decryptor = mockk<LegacyStationPhoneDecryptor>()
    private val users = mockk<StationUserDirectory>()
    private val resolver = StationPhoneResolver(decryptor, users)

    @Test
    fun `V1 ignores request identity and looks up authenticated user`() {
        every { users.findPhone("auth-user", "product") } returns "13800138000"
        assertEquals(ResolvedStationPhone("13800138000", "UC_USER_LOOKUP"),
            resolver.resolve("V1", null, "auth-user", "product"))
        verify(exactly = 0) { decryptor.decrypt(any()) }
        verify { users.findPhone("auth-user", "product") }
    }

    @Test
    fun `V2 decrypts supplied phone without UC lookup`() {
        every { decryptor.decrypt("ciphertext") } returns "13800138000"
        assertEquals(ResolvedStationPhone("13800138000", "SOURCE_V2_REQUEST"),
            resolver.resolve("V2", "ciphertext", "auth-user", "product"))
        verify(exactly = 0) { users.findPhone(any(), any()) }
    }

    @Test
    fun `V2 blank phone looks up authenticated user exactly like legacy source`() {
        every { users.findPhone("auth-user", "product") } returns null
        assertNull(resolver.resolve("V2", "", "auth-user", "product"))
        verify { users.findPhone("auth-user", "product") }
    }

    @Test
    fun `missing lookup identity fails closed`() {
        val error = assertThrows(ClientException::class.java) {
            resolver.resolve("V1", null, null, "product")
        }
        assertEquals("STATION_UC_USER_ID_MISSING", error.reasonCode)
    }
}

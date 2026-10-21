package com.feedback.repository.sdk

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.mapper.sdk.*
import com.feedback.model.po.sdk.*
import com.feedback.model.sdk.*
import com.feedback.repository.impl.sdk.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SdkMaterializationRepositoriesTest {
    @Test
    fun `source insert requires generated id`() {
        val mapper = mockk<SdkFeedbackSourceMutationMapper>()
        val repository = MyBatisSdkFeedbackSourceMutationRepositoryImpl(mapper)
        every { mapper.insert(any()) } answers { firstArg<SdkFeedbackSourcePo>().id = 42; 1 }

        assertEquals(42L, repository.insert(SdkFeedbackSourcePo(sourceRecordId = "client-1")).id)
    }

    @Test
    fun `bounded source collections reject 201 ids before mapper access`() {
        val mapper = mockk<SdkProcessingEnvelopeMapper>(relaxed = true)
        val repository = MyBatisSdkProcessingEnvelopeRepositoryImpl(mapper)

        val error = assertThrows(ClientException::class.java) {
            repository.findBySourceIds((1L..201L).toList())
        }
        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("SDK_MATERIALIZATION_SOURCE_IDS_INVALID", error.reasonCode)
        verify(exactly = 0) { mapper.findBySourceIds(any()) }
    }

    @Test
    fun `diagnostic batch rejects duplicate source ids`() {
        val mapper = mockk<SdkChannelMutationMapper>(relaxed = true)
        val repository = MyBatisSdkChannelMutationRepositoryImpl(mapper)

        val error = assertThrows(ClientException::class.java) {
            repository.upsertControlledDiagnostics(listOf(SdkChannelPo(sourceId = 1), SdkChannelPo(sourceId = 1)))
        }
        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("SDK_CHANNEL_DIAGNOSTIC_BATCH_INVALID", error.reasonCode)
        verify(exactly = 0) { mapper.upsertControlledDiagnostics(any()) }
    }

    @Test
    fun `idempotent upserts accept MySQL zero affected rows`() {
        val channelMapper = mockk<SdkChannelMutationMapper>()
        val identityMapper = mockk<SdkIdentityMutationMapper>()
        every { channelMapper.upsert(any()) } returns 0
        every { identityMapper.upsertBatch(any()) } returns 0

        assertDoesNotThrow { MyBatisSdkChannelMutationRepositoryImpl(channelMapper).upsert(SdkChannelPo(sourceId = 1)) }
        assertDoesNotThrow {
            MyBatisSdkIdentityMutationRepositoryImpl(identityMapper).upsertBatch(
                listOf(SdkIdentityPo(sourceId = 1, identityType = "DEVICE_SN", identityScope = "DEVICE"))
            )
        }
    }
}

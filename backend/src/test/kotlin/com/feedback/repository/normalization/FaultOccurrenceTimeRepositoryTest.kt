package com.feedback.repository.normalization

import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.normalization.FaultOccurrenceTimeContentMapper
import com.feedback.mapper.normalization.FaultOccurrenceTimeJiraMapper
import com.feedback.mapper.normalization.FaultOccurrenceTimeQualityMapper
import com.feedback.mapper.normalization.FaultOccurrenceTimeSourceMapper
import com.feedback.mapper.normalization.FaultOccurrenceTimeStationMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FaultOccurrenceTimeRepositoryTest {
    private val sourceMapper = mockk<FaultOccurrenceTimeSourceMapper>()
    private val contentMapper = mockk<FaultOccurrenceTimeContentMapper>()
    private val jiraMapper = mockk<FaultOccurrenceTimeJiraMapper>()
    private val stationMapper = mockk<FaultOccurrenceTimeStationMapper>()
    private val qualityMapper = mockk<FaultOccurrenceTimeQualityMapper>()
    private val repository = FaultOccurrenceTimeRepository(
        sourceMapper,
        contentMapper,
        jiraMapper,
        stationMapper,
        qualityMapper
    )

    @Test
    fun `201 source IDs are rejected before a mapper call`() {
        val sourceIds = (1L..201L).toList()

        assertThrows(ClientException::class.java) { repository.contents(sourceIds) }

        verify(exactly = 0) { contentMapper.findBySourceIds(any()) }
    }

    @Test
    fun `empty source IDs do not call auxiliary mapper`() {
        repository.quality(emptyList())

        verify(exactly = 0) { qualityMapper.findBySourceIds(any()) }
    }

    @Test
    fun `partial source update fails closed`() {
        val commands = listOf(
            FaultOccurrenceTimeUpdate(1, null, null, null, "SDK_CLIENT", "MISSING"),
            FaultOccurrenceTimeUpdate(2, null, null, null, "SDK_CLIENT", "MISSING")
        )
        every { sourceMapper.updateAll(any()) } returns 1

        assertThrows(ServiceException::class.java) { repository.updateAll(commands) }
    }
}

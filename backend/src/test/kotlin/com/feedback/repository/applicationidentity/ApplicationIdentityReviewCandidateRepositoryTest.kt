package com.feedback.repository.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityReviewCandidateRepositoryImpl

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.applicationidentity.ApplicationIdentityStationMapper
import com.feedback.model.po.applicationidentity.ApplicationIdentityStationCandidatePo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ApplicationIdentityReviewCandidateRepositoryTest {
    private val stationMapper = mockk<ApplicationIdentityStationMapper>()
    private val identities = mockk<ApplicationIdentityRepository>()
    private val repository = MyBatisApplicationIdentityReviewCandidateRepositoryImpl(stationMapper, identities)

    @Test
    fun `reviewed display group resolves authoritative alias without persisting the display name`() {
        every { stationMapper.findCandidatesByDisplayName("优学练习", 101) } returns listOf(
            ApplicationIdentityStationCandidatePo("  station-app  ", 60)
        )
        every { identities.activeAliases(any()) } returns emptyMap()

        val candidate = repository.resolve("优学练习", 60)

        assertEquals("station-app", candidate.key.normalizedAlias)
        assertEquals(ApplicationAliasType.STATION_SOURCE_APP_ID, candidate.key.aliasType)
        assertEquals(60, candidate.sourceCount)
    }

    @Test
    fun `reviewed display group fails closed on multiple source aliases`() {
        every { stationMapper.findCandidatesByDisplayName("优学练习", 101) } returns listOf(
            ApplicationIdentityStationCandidatePo("station-app-a", 30),
            ApplicationIdentityStationCandidatePo("station-app-b", 30)
        )
        every { identities.activeAliases(any()) } returns emptyMap()

        val error = assertThrows(ClientException::class.java) { repository.resolve("优学练习", 60) }

        assertEquals("APPLICATION_REVIEW_CANDIDATE_AMBIGUOUS", error.reasonCode)
    }

    @Test
    fun `reviewed display group detects count drift`() {
        every { stationMapper.findCandidatesByDisplayName("优学练习", 101) } returns listOf(
            ApplicationIdentityStationCandidatePo("station-app", 61)
        )
        every { identities.activeAliases(any()) } returns emptyMap()

        val error = assertThrows(ServiceException::class.java) { repository.resolve("优学练习", 60) }

        assertEquals(ErrorCode.APPLICATION_IDENTITY_CONFLICT, error.errorCode)
        assertEquals("APPLICATION_REVIEW_CANDIDATE_COUNT_CONFLICT", error.reasonCode)
    }

    @Test
    fun `same display name ignores already governed source alias and resolves unique unbound candidate`() {
        every { stationMapper.findCandidatesByDisplayName("希沃家长圈", 101) } returns listOf(
            ApplicationIdentityStationCandidatePo("governed-station-app", 17),
            ApplicationIdentityStationCandidatePo("reviewed-station-app", 47)
        )
        val governedKey = ApplicationAliasKey(
            ApplicationSourceChannel.STATION_FEEDBACK,
            ApplicationAliasType.STATION_SOURCE_APP_ID,
            "governed-station-app"
        )
        every { identities.activeAliases(any()) } returns mapOf(
            governedKey to ActiveApplicationAlias(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                governedKey,
                1,
                ApplicationIdentitySnapshot(1, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "希沃家长圈", 1)
            )
        )

        val candidate = repository.resolve("希沃家长圈", 47)

        assertEquals("reviewed-station-app", candidate.key.normalizedAlias)
        assertEquals(47, candidate.sourceCount)
    }
}

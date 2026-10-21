package com.feedback.repository.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentitySourceRepositoryImpl

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.applicationidentity.ApplicationIdentitySdkMapper
import com.feedback.model.po.applicationidentity.ApplicationIdentitySdkPo
import com.feedback.mapper.applicationidentity.ApplicationIdentitySourceMapper
import com.feedback.model.po.applicationidentity.ApplicationIdentitySourcePo
import com.feedback.mapper.applicationidentity.ApplicationIdentityStationMapper
import com.feedback.model.po.applicationidentity.ApplicationIdentityStationPo
import com.feedback.model.applicationidentity.ApplicationSourceChannel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 渠道扩展事实必须与主来源集合一一对应；物理缺行不能伪装为业务 alias 未解析。 */
class ApplicationIdentitySourceRepositoryTest {
    private val sourceMapper = mockk<ApplicationIdentitySourceMapper>()
    private val sdkMapper = mockk<ApplicationIdentitySdkMapper>()
    private val stationMapper = mockk<ApplicationIdentityStationMapper>()
    private val repository = MyBatisApplicationIdentitySourceRepositoryImpl(sourceMapper, sdkMapper, stationMapper)

    @Test
    /** 生命周期枚举必须完整覆盖 V237 已冻结的数据库编码。 */
    fun `identity lifecycle dictionary keeps active and retired database codes`() {
        assertEquals(ApplicationIdentityLifecycleStatus.ACTIVE, ApplicationIdentityLifecycleStatus.fromCode("ACTIVE"))
        assertEquals(ApplicationIdentityLifecycleStatus.RETIRED, ApplicationIdentityLifecycleStatus.fromCode("RETIRED"))
    }

    @Test
    fun `missing SDK extension rejects the whole input batch`() {
        every { sourceMapper.findSupportedAfter(0, 10) } returns listOf(source(1, ApplicationSourceChannel.SDK))
        every { sdkMapper.findBySourceIds(listOf(1)) } returns emptyList()

        val error = assertThrows(ServiceException::class.java) { repository.nextInputs(0, 10) }

        assertEquals(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, error.errorCode)
        assertEquals("APPLICATION_SDK_MISSING", error.reasonCode)
        verify(exactly = 0) { stationMapper.findBySourceIds(any()) }
    }

    @Test
    fun `missing station extension rejects a mixed batch instead of returning partial inputs`() {
        every { sourceMapper.findSupportedAfter(0, 10) } returns listOf(
            source(1, ApplicationSourceChannel.SDK),
            source(2, ApplicationSourceChannel.STATION_FEEDBACK)
        )
        every { sdkMapper.findBySourceIds(listOf(1)) } returns listOf(ApplicationIdentitySdkPo(1, "reader", null))
        every { stationMapper.findBySourceIds(listOf(2)) } returns emptyList()

        val error = assertThrows(ServiceException::class.java) { repository.nextInputs(0, 10) }

        assertEquals(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, error.errorCode)
        assertEquals("APPLICATION_STATION_MISSING", error.reasonCode)
    }

    @Test
    fun `present SDK extension with empty aliases remains a business unresolved input`() {
        every { sourceMapper.findSupportedAfter(0, 10) } returns listOf(source(1, ApplicationSourceChannel.SDK))
        every { sdkMapper.findBySourceIds(listOf(1)) } returns listOf(ApplicationIdentitySdkPo(1, null, null))

        val input = repository.nextInputs(0, 10).single()

        assertEquals(1, input.sourceId)
        assertEquals(ApplicationSourceChannel.SDK, input.sourceChannel)
        assertEquals(2, input.aliases.size)
        input.aliases.forEach { assertNull(it.rawAlias) }
        assertNull(input.displayNameEvidence)
    }

    @Test
    fun `four channel scan covers every channel and leaves unapproved channels unresolved`() {
        every { sourceMapper.findFourChannelAfter(0, 10) } returns listOf(
            source(1, ApplicationSourceChannel.STATION_FEEDBACK),
            source(2, ApplicationSourceChannel.JIRA),
            source(3, ApplicationSourceChannel.QUALITY),
            source(4, ApplicationSourceChannel.REDBLACK)
        )
        every { stationMapper.findBySourceIds(listOf(1)) } returns listOf(
            ApplicationIdentityStationPo(1, "station-app", "audited display only")
        )

        val inputs = repository.nextFourChannelInputs(0, 10)

        assertEquals(ApplicationSourceChannel.entries.filter { it != ApplicationSourceChannel.SDK }.toSet(),
            inputs.map { it.sourceChannel }.toSet())
        assertEquals(1, inputs.single { it.sourceChannel == ApplicationSourceChannel.STATION_FEEDBACK }.aliases.size)
        inputs.filter { it.sourceChannel in setOf(ApplicationSourceChannel.JIRA, ApplicationSourceChannel.QUALITY,
            ApplicationSourceChannel.REDBLACK) }.forEach { input ->
            assertEquals(emptyList<ApplicationAliasInput>(), input.aliases)
            assertNull(input.displayNameEvidence)
        }
        verify(exactly = 0) { sdkMapper.findBySourceIds(any()) }
    }

    @Test
    fun `duplicate and unexpected extension rows remain fail closed`() {
        every { sourceMapper.findSupportedAfter(0, 10) } returns listOf(source(1, ApplicationSourceChannel.SDK))
        every { sdkMapper.findBySourceIds(listOf(1)) } returns listOf(
            ApplicationIdentitySdkPo(1, "reader", null),
            ApplicationIdentitySdkPo(1, "reader", null)
        )

        val duplicate = assertThrows(ServiceException::class.java) { repository.nextInputs(0, 10) }
        assertEquals(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, duplicate.errorCode)
        assertEquals("APPLICATION_SDK_DUPLICATED", duplicate.reasonCode)

        every { sdkMapper.findBySourceIds(listOf(1)) } returns listOf(ApplicationIdentitySdkPo(2, "reader", null))
        val unexpected = assertThrows(ServiceException::class.java) { repository.nextInputs(0, 10) }
        assertEquals(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, unexpected.errorCode)
        assertEquals("APPLICATION_SDK_UNEXPECTED_ID", unexpected.reasonCode)
    }

    @Test
    fun `invalid pagination is a client error`() {
        val error = assertThrows(ClientException::class.java) { repository.nextInputs(-1, 10) }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("APPLICATION_SHADOW_CURSOR_INVALID", error.reasonCode)
    }

    private fun source(id: Long, channel: ApplicationSourceChannel) = ApplicationIdentitySourcePo(id, channel.name)
}

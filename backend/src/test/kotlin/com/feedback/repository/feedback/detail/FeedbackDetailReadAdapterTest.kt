package com.feedback.repository.feedback.detail

import com.feedback.config.JacksonConfig
import com.feedback.model.po.feedback.detail.*
import com.feedback.repository.attachment.SecureAttachmentRepository
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.service.query.detail.*
import com.feedback.application.port.feedback.detail.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedbackDetailReadAdapterTest {
    private val repository = mockk<FeedbackDetailRepository>()
    private val attachments = mockk<SecureAttachmentRepository>()
    private val navigation = mockk<FeedbackNavigationResolver>()
    private val adapter = FeedbackDetailReadRepository(repository, attachments, navigation, JacksonConfig().objectMapper())
    private val policy = DataFieldPolicy(true, emptySet(), emptySet())
    private val scope = DataAccessScope(setOf("SDK", "JIRA", "QUALITY", "REDBLACK", "STATION_FEEDBACK"), null, null)

    @Test
    fun `all five channels map to explicit immutable facts without extra channel reads`() {
        val channels = mapOf(1L to "SDK", 2L to "JIRA", 3L to "QUALITY", 4L to "REDBLACK", 5L to "STATION_FEEDBACK")
        channels.forEach { (id, channel) -> common(id, channel) }
        every { repository.sdk(1) } returns SdkDetailPo(clientFeedbackId = "sdk-1", diagnosticTypes = "DEVICE_SN")
        every { repository.jira(2) } returns JiraDetailPo(jiraKey = "FP-2", jiraStatusRaw = "处理中")
        every { repository.quality(3) } returns QualityDetailPo(returnOrderNo = "Q-3")
        every { repository.redblack(4) } returns RedblackDetailPo(originalIdRaw = "RB-4", url = "https://rb.example/4")
        every { repository.station(5) } returns StationDetailPo(productId = "P-5", ingestStatus = "INGESTED")

        val facts = channels.keys.map { adapter.read(it, scope).channelFacts }

        assertTrue(facts[0] is SdkFeedbackChannelFacts)
        assertEquals("sdk-1", (facts[0] as SdkFeedbackChannelFacts).clientFeedbackId)
        assertTrue(facts[1] is JiraFeedbackChannelFacts)
        assertEquals("处理中", (facts[1] as JiraFeedbackChannelFacts).jiraStatusRaw)
        assertTrue(facts[2] is QualityFeedbackChannelFacts)
        assertEquals("Q-3", (facts[2] as QualityFeedbackChannelFacts).returnOrderNo)
        assertTrue(facts[3] is RedblackFeedbackChannelFacts)
        assertEquals("RB-4", (facts[3] as RedblackFeedbackChannelFacts).originalIdRaw)
        assertTrue(facts[4] is StationFeedbackChannelFacts)
        assertEquals("P-5", (facts[4] as StationFeedbackChannelFacts).productId)

        verify(exactly = 1) { repository.sdk(1) }
        verify(exactly = 1) { repository.jira(2) }
        verify(exactly = 1) { repository.quality(3) }
        verify(exactly = 1) { repository.redblack(4) }
        verify(exactly = 1) { repository.station(5) }
        verify(exactly = 0) { repository.sdk(match { it != 1L }) }
        verify(exactly = 0) { repository.jira(match { it != 2L }) }
        verify(exactly = 0) { repository.quality(match { it != 3L }) }
        verify(exactly = 0) { repository.redblack(match { it != 4L }) }
        verify(exactly = 0) { repository.station(match { it != 5L }) }
        channels.keys.forEach { id ->
            verify(exactly = 1) { repository.source(id) }
            verify(exactly = 1) { repository.content(id) }
            verify(exactly = 1) { repository.ai(id) }
            verify(exactly = 1) { repository.workflow(id) }
            verify(exactly = 1) { repository.assignment(id) }
            verify(exactly = 1) { repository.fieldHistory(id) }
            verify(exactly = 1) { repository.identityTypes(id) }
            verify(exactly = 1) { repository.clusterSummary(id) }
            verify(exactly = 1) { attachments.hasActiveLog(id) }
            verify(exactly = 1) { attachments.findNonLogListFacts(id) }
        }
        verify(exactly = channels.size) { repository.users(emptySet()) }
        verify(exactly = channels.size) { navigation.resolve(any(), scope) }
    }

    private fun common(id: Long, channel: String) {
        val source = FeedbackDetailSourcePo(id = id, sourceChannel = channel, sourceRecordId = "$channel-$id")
        every { repository.source(id) } returns source
        every { repository.content(id) } returns FeedbackDetailContentPo(sourceId = id, maskingStatus = "PASS")
        every { repository.fieldHistory(id) } returns emptyList()
        every { repository.assignment(id) } returns null
        every { repository.users(emptySet()) } returns emptyMap()
        every { repository.ai(id) } returns null
        every { repository.workflow(id) } returns null
        every { repository.identityTypes(id) } returns emptyList()
        every { repository.clusterSummary(id) } returns null
        every { attachments.hasActiveLog(id) } returns false
        every { attachments.findNonLogListFacts(id) } returns emptyList()
        every { navigation.resolve(source = any(), scope = scope) } returns
            FeedbackNavigationSnapshot(previousId = null, nextId = null)
    }
}

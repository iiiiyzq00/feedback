package com.feedback.service.query.listprojection

import com.feedback.application.port.feedback.list.FeedbackListRecord
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.service.query.detail.QualityFeedbackChannelMetaResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedbackListAssemblerTest {
    private val assembler = FeedbackListAssembler(jacksonObjectMapper())
    private val policy = DataFieldPolicy(true, emptySet(), emptySet())

    @Test
    fun `AI severity is provisional only when canonical severity is absent`() {
        val aiOnly = assembler.row(
            row().copy(severityLevel = null, aiSeverity = "P2", aiReviewStatus = "PENDING"),
            null,
            policy
        )
        val canonical = assembler.row(
            row().copy(severityLevel = "P1", aiSeverity = "P2", aiReviewStatus = "CONFIRMED"),
            null,
            policy
        )

        assertEquals("P2", aiOnly.severity)
        assertTrue(aiOnly.severityProvisional)
        assertEquals("PENDING", aiOnly.severityReviewStatus)
        assertEquals("P1", canonical.severity)
        assertFalse(canonical.severityProvisional)
        assertEquals("CONFIRMED", canonical.severityReviewStatus)
    }

    @Test
    fun `learning machine application name covers SDK and station physical channels`() {
        val sdk = assembler.row(
            row().copy(sourceChannel = "SDK", channelAppId = "sdk-app", packageName = "sdk-package"),
            null,
            policy
        )
        val station = assembler.row(
            row().copy(
                sourceChannel = "STATION_FEEDBACK",
                stationAppId = "station-id",
                channelMetaJson = """{"source_app_name":"station-app","source_product_name":"station-product"}"""
            ),
            null,
            policy
        )

        assertEquals("sdk-app", sdk.learningMachineAppName)
        assertEquals("station-app", station.learningMachineAppName)
    }

    @Test
    fun `quality list row materializes its channel response type`() {
        val quality = assembler.row(
            row().copy(
                sourceChannel = "QUALITY",
                channelMetaJson = """{"return_order_no":"Q-11","product_name":"Learning Pad"}"""
            ),
            null,
            policy
        )

        val channelMeta = quality.channelMeta as QualityFeedbackChannelMetaResponse
        assertEquals("Q-11", channelMeta.returnOrderNo)
        assertEquals("Learning Pad", channelMeta.productName)
    }

    private fun row() = FeedbackListRecord(
        sourceId = 11,
        sourceChannel = "SDK",
        sourceRecordId = "SDK-11",
        title = "安全标题",
        description = "安全正文",
        functionModule = "COURSE",
        issueCategory = "CRASH",
        processingStatus = "NEW",
        distributionStatus = "UNASSIGNED"
    )
}

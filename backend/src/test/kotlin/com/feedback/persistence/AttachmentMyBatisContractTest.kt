package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttachmentMyBatisContractTest {
    private val resources = listOf(
        "mybatis/mapper/attachment/FeedbackAttachmentMapper.xml",
        "mybatis/mapper/attachment/AttachmentGrantMapper.xml",
        "mybatis/mapper/audit/AttachmentAuditMapper.xml"
    )

    @Test
    fun `approved mapper XML loads and contains only single-table statements`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        resources.forEach { resource ->
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), resource)
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }

        assertTrue(configuration.hasStatement("com.feedback.mapper.attachment.AttachmentGrantMapper.lockConsumableId"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.attachment.FeedbackAttachmentMapper.findNonLogListBySourceId"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.attachment.FeedbackAttachmentMapper.findListBySourceId"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.attachment.FeedbackAttachmentMapper.findObjectStorageLocationById"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.audit.AttachmentAuditMapper.insertAccessAudit"))

        val attachmentXml = Resources.getResourceAsReader("mybatis/mapper/attachment/FeedbackAttachmentMapper.xml")
            .use { it.readText() }
        assertTrue(attachmentXml.contains("sdk_ingest_id"))
    }
}

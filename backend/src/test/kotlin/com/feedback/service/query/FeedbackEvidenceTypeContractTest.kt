package com.feedback.service.query

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FeedbackEvidenceTypeContractTest {
    @Test
    fun `list statistics include every supported screenshot and video type`() {
        val source = Files.readString(Path.of("src/main/resources/mybatis/mapper/feedback/listprojection/FeedbackListAttachmentFactsMapper.xml"))
        assertTrue(source.contains("attachment_type IN ('IMAGE','SCREENSHOT','ANNOTATED_SCREENSHOT')"))
        assertTrue(source.contains("attachment_type IN ('VIDEO','SCREEN_RECORDING')"))
    }

    @Test
    fun `forward migration permits annotated screenshots without editing the original constraint`() {
        val migration = Files.readString(Path.of("src/main/resources/db/migration/V221__allow_annotated_screenshot_evidence.sql"))
        assertTrue(migration.contains("DROP CHECK chk_attachment_type"))
        assertTrue(migration.contains("'ANNOTATED_SCREENSHOT'"))
        assertTrue(migration.contains("'SCREEN_RECORDING'"))
    }
}

package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RemainingReadMyBatisContractTest {
    private val root = Path.of("src/main/resources/mybatis/mapper/feedback")

    @Test fun `diagnostic mappers use one physical table per statement and bounded reads`() {
        val files = Files.list(root.resolve("diagnostics")).use { stream -> stream.toList() }
        assertTrue(files.size == 5)
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        files.forEach { file ->
            val sql = Files.readString(file).uppercase()
            assertFalse(Regex("\\bJOIN\\b").containsMatchIn(sql), file.toString())
            if (!file.fileName.toString().contains("Audit")) assertTrue(sql.contains("LIMIT #{LIMIT}"), file.toString())
            val resource = "mybatis/mapper/feedback/diagnostics/${file.fileName}"
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.diagnostics.FeedbackDiagnosticAuditMapper.insertViewAudit"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.diagnostics.FeedbackDiagnosticIdentityMapper.findAuthorized"))
    }

    @Test fun `phase one split application services contain no jdbc or sql construction`() {
        val sources = listOf(
            "src/main/kotlin/com/feedback/service/attachment/FeedbackAttachmentApplicationService.kt",
            "src/main/kotlin/com/feedback/service/query/SearchSuggestionApplicationService.kt",
            "src/main/kotlin/com/feedback/service/query/RelatedFeedbackApplicationService.kt",
            "src/main/kotlin/com/feedback/service/query/FeedbackDiagnosticsApplicationService.kt"
        ).map { Files.readString(Path.of(it)) }
        sources.forEach { source ->
            assertFalse(source.contains("NamedParameterJdbcTemplate"))
            assertFalse(Regex("(?i)\\b(SELECT|INSERT|UPDATE|DELETE)\\s+(FROM|INTO|t_)").containsMatchIn(source))
        }
    }
}

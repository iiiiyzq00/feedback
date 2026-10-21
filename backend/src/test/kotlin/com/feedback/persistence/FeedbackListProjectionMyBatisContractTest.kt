package com.feedback.persistence

import com.feedback.model.query.feedback.list.FeedbackListReadCriteria
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class FeedbackListProjectionMyBatisContractTest {
    private val mapperNames = listOf(
        "FeedbackListProjectionGenerationMapper.xml",
        "FeedbackListGenerationReadMapper.xml",
        "FeedbackListProjectionDirtyMapper.xml",
        "FeedbackListProjectionControlMapper.xml",
        "FeedbackListProjectionActorMapper.xml",
        "FeedbackListProjectionMapper.xml",
        "FeedbackListProjectionReadMapper.xml",
        "FeedbackClusterMemberProjectionMapper.xml",
        "FeedbackClusterIdentityProjectionMapper.xml",
        "FeedbackListSourceFactsMapper.xml",
        "FeedbackListContentFactsMapper.xml",
        "FeedbackListAiFactsMapper.xml",
        "FeedbackListWorkflowFactsMapper.xml",
        "FeedbackListAssignmentFactsMapper.xml",
        "FeedbackListOwnerFactsMapper.xml",
        "FeedbackListHandoffFactsMapper.xml",
        "FeedbackListAttachmentFactsMapper.xml",
        "FeedbackListImportBatchFactsMapper.xml",
        "FeedbackListImportRowFactsMapper.xml",
        "FeedbackListClusterLinkFactsMapper.xml",
        "FeedbackListIdentityTypeFactsMapper.xml",
        "FeedbackListIdentityFingerprintFactsMapper.xml",
        "FeedbackListClusterDecisionFactsMapper.xml",
        "FeedbackListApplicationDictionaryMapper.xml",
        "FeedbackListSdkFactsMapper.xml",
        "FeedbackListJiraFactsMapper.xml",
        "FeedbackListQualityFactsMapper.xml",
        "FeedbackListRedblackFactsMapper.xml",
        "FeedbackListStationFactsMapper.xml"
    )
    private val resources = mapperNames.map { "mybatis/mapper/feedback/listprojection/$it" }

    @Test
    fun `projection mapper XML parses and every statement remains single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        resources.forEach { resource ->
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), resource)
            assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml), resource)
            val statements = Regex(
                "<(select|insert|update|delete)\\b[\\s\\S]*?</\\1>",
                setOf(RegexOption.IGNORE_CASE)
            ).findAll(xml).map { it.value }.toList()
            statements.forEach { statement ->
                val sqlBody = statement.substringAfter('>').substringBeforeLast('<')
                assertTrue(Regex("\\bSELECT\\b", RegexOption.IGNORE_CASE).findAll(sqlBody).count() <= 1, resource)
                assertTrue(Regex("\\bFROM\\b", RegexOption.IGNORE_CASE).findAll(sqlBody).count() <= 1, resource)
            }
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListSourceFactsMapper.findByIds"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListIdentityTypeFactsMapper.findBySourceIds"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListProjectionMapper.upsertAll"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackClusterMemberProjectionMapper.upsertAll"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackClusterIdentityProjectionMapper.upsertAll"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListProjectionDirtyMapper.findClaimableForUpdate"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListProjectionControlMapper.findForUpdate"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListSourceFactsMapper.findIdsAfterThroughFence"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListProjectionGenerationMapper.transitionStatus"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListProjectionGenerationMapper.extendBuildFence"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListGenerationReadMapper.findActive"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListProjectionReadMapper.findPage"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.listprojection.FeedbackListProjectionReadMapper.stats"))
        val generationXml = Resources.getResourceAsReader(
            "mybatis/mapper/feedback/listprojection/FeedbackListProjectionGenerationMapper.xml"
        ).use { it.readText() }
        val catchUpStatement = generationXml.substringAfter("<update id=\"extendBuildFence\">")
            .substringBefore("</update>")
        assertTrue(catchUpStatement.contains("expected_source_count=#{expectedSourceCount}"))
        assertTrue(catchUpStatement.contains("build_cursor_source_id=build_fence_source_id"))
        assertTrue(catchUpStatement.contains("verified_mutation_epoch=NULL"))
        val dirtyXml = Resources.getResourceAsReader(
            "mybatis/mapper/feedback/listprojection/FeedbackListProjectionDirtyMapper.xml"
        ).use { it.readText() }
        assertTrue(dirtyXml.contains("FOR UPDATE SKIP LOCKED"))
        assertTrue(dirtyXml.contains("(source_id,mutation_version) IN"))
        assertTrue(dirtyXml.contains("mutation_version=t_phase_one_feedback_list_projection_dirty.mutation_version+1"))
    }

    @Test
    fun `list BoundSql preserves traceability and deep analysis filters`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        val resource = "mybatis/mapper/feedback/listprojection/FeedbackListProjectionReadMapper.xml"
        Resources.getResourceAsReader(resource).use { reader ->
            XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
        }
        val criteria = populatedCriteria()
        val sql = configuration.getMappedStatement(
            "com.feedback.mapper.feedback.listprojection.FeedbackListProjectionReadMapper.findPage"
        ).getBoundSql(mapOf(
            "generationId" to 7L,
            "criteria" to criteria,
            "limit" to 20,
            "offset" to 0L
        )).sql.replace(Regex("\\s+"), " ").trim()

        assertTrue(sql.contains("JSON_CONTAINS(jira_import_batch_codes,JSON_QUOTE(?))"), sql)
        assertTrue(sql.contains("reported_at>=?"), sql)
        assertTrue(sql.contains("reported_at<=?"), sql)
        assertTrue(sql.contains("COALESCE(NULLIF(function_module,''),'UNKNOWN') IN"), sql)
        assertTrue(sql.contains("COALESCE(NULLIF(issue_category,''),'UNKNOWN') IN"), sql)
        assertTrue(sql.contains("COALESCE(NULLIF(reproducibility,''),'UNKNOWN') IN"), sql)
        assertTrue(sql.contains("ai_review_status=?"), sql)
        assertTrue(sql.contains("COALESCE(NULLIF(province_code,''),'UNKNOWN')=?"), sql)
        assertTrue(sql.contains("COALESCE(NULLIF(city_code,''),'UNKNOWN')=?"), sql)
        assertFalse(sql.contains("t_import_row", ignoreCase = true), sql)
        assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
    }

    @Test
    fun `V234 adds empty analysis projection structures without activating or backfilling`() {
        val migration = Files.readString(Path.of("src/main/resources/db/migration/V234__extend_phase_one_problem_analysis_projection.sql"))
        listOf(
            "analysis_projection_version", "verified_analysis_checksum", "verified_analysis_identity_checksum",
            "projected_analysis_identity_count", "CREATE TABLE t_phase_one_feedback_cluster_identity_projection",
            "logical_channel", "application_name", "region_value"
        ).forEach { assertTrue(migration.contains(it), it) }
        assertFalse(Regex("\\bINSERT\\s+INTO\\b", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(Regex("generation_status\\s*=\\s*'ACTIVE'", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertTrue(migration.contains("fingerprint BINARY(32)"))
        assertTrue(migration.contains("fingerprint_key_id VARCHAR(50)"))
    }

    @Test
    fun `V231 creates empty projection infrastructure without backfill or activation`() {
        val migration = Files.readString(Path.of("src/main/resources/db/migration/V231__create_phase_one_feedback_list_projection.sql"))
        listOf(
            "t_phase_one_feedback_list_projection_generation",
            "t_phase_one_feedback_list_projection",
            "t_phase_one_feedback_cluster_member_projection"
        ).forEach { assertTrue(migration.contains("CREATE TABLE $it"), it) }
        assertFalse(Regex("\\bINSERT\\s+INTO\\b", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(Regex("\\bUPDATE\\s+t_", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(Regex("\\bDELETE\\s+FROM\\b", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(migration.contains("owner_name", ignoreCase = true))
        assertFalse(migration.contains("source_fact_updated_at", ignoreCase = true))
        assertTrue(migration.contains("generation 全量对账"))
        listOf("ciphertext", "storage_path", "url VARCHAR", "token", "grant").forEach { forbidden ->
            assertFalse(migration.contains(forbidden, ignoreCase = true), forbidden)
        }
    }

    @Test
    fun `V232 creates only the durable dirty queue with versioned acknowledgement facts`() {
        val migration = Files.readString(Path.of("src/main/resources/db/migration/V232__queue_phase_one_feedback_list_projection_refresh.sql"))
        assertTrue(migration.contains("CREATE TABLE t_phase_one_feedback_list_projection_dirty"))
        assertTrue(migration.contains("mutation_version BIGINT NOT NULL DEFAULT 1"))
        assertTrue(migration.contains("source_id BIGINT NOT NULL PRIMARY KEY"))
        assertFalse(Regex("FOREIGN KEY", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(Regex("\\bINSERT\\s+INTO\\b", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(Regex("\\bUPDATE\\s+t_", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(Regex("\\bDELETE\\s+FROM\\b", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        listOf("ciphertext", "storage_path", "token", "grant", "description TEXT").forEach { forbidden ->
            assertFalse(migration.contains(forbidden, ignoreCase = true), forbidden)
        }
        assertTrue(migration.contains("Governed physical business table count is now 61"))
    }

    @Test
    fun `V233 adds only empty generation control and epoch infrastructure`() {
        val migration = Files.readString(Path.of("src/main/resources/db/migration/V233__govern_phase_one_list_projection_generations.sql"))
        assertTrue(migration.contains("build_fence_source_id"))
        assertTrue(migration.contains("catch-up"))
        assertTrue(migration.contains("verified_mutation_epoch"))
        assertTrue(migration.contains("cutover_mutation_epoch"))
        assertTrue(migration.contains("rollback_ready_slot"))
        assertTrue(migration.contains("CREATE TABLE t_phase_one_feedback_list_projection_control"))
        assertTrue(migration.contains("VALUES (1, 0)"))
        assertFalse(migration.contains("t_phase_one_feedback_list_projection(", ignoreCase = true))
        assertFalse(Regex("generation_status\\s*=\\s*'ACTIVE'", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(migration))
        assertFalse(migration.startsWith("version https://git-lfs.github.com/spec/v1"))
    }

    private fun populatedCriteria() = FeedbackListReadCriteria(
        allowedChannels = listOf("JIRA"),
        scopeModules = listOf("COURSE"),
        scopeRegions = listOf("GD"),
        channels = listOf("JIRA"),
        keywordPattern = "%issue%",
        severities = listOf("P1"),
        statuses = listOf("PROCESSING"),
        modules = listOf("COURSE"),
        problemTypes = listOf("CRASH"),
        reproducibilities = listOf("ALWAYS"),
        aiReviewStatus = "PENDING",
        province = "GD",
        city = "GZ",
        ownerId = 9,
        unassigned = false,
        handoffPending = true,
        startTime = LocalDateTime.of(2026, 8, 1, 0, 0),
        endTime = LocalDateTime.of(2026, 8, 31, 23, 59),
        appId = null,
        packageName = null,
        deviceModelPattern = null,
        osVersionPattern = null,
        networkType = null,
        appVersion = null,
        projectKey = "SP",
        issueKey = "SP-1",
        issueType = "BUG",
        priority = "HIGH",
        assignee = "owner",
        resolution = null,
        repairRegionPattern = null,
        applicationType = null,
        exchangeRequest = null,
        importBatchCode = "BATCH-1",
        sourcePlatform = null,
        emotionType = null,
        channelKeyword = null,
        listType = null,
        productId = null,
        sourceSubChannel = null,
        apiVersion = null,
        versionCompleteness = null,
        sourceResolutionState = "UNRESOLVED",
        sortBy = "reportedAt",
        sortDirection = "desc"
    )
}

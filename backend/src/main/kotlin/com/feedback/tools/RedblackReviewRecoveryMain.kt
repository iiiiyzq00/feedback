package com.feedback.tools

import com.feedback.model.masking.MaskingAudit
import com.feedback.mapper.masking.MaskingAuditMapper
import com.feedback.repository.MaskingAuditRepository
import com.feedback.service.masking.MaskingPipeline
import com.feedback.service.masking.NoopNerMasker
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

data class RedblackReviewRecoveryInput(
    val idHash: String,
    val headline: String,
    val content: String,
    val headlineHash: String,
    val contentHash: String,
    val sourceDeleted: Boolean
)

data class RedblackMaskedRecovery(
    val input: RedblackReviewRecoveryInput,
    val safeHeadline: String?,
    val safeContent: String?
)

class RedblackReviewRecoveryPlanner(private val masking: MaskingPipeline) {
    fun prepare(inputs: List<RedblackReviewRecoveryInput>): List<RedblackMaskedRecovery> {
        require(inputs.size == EXPECTED_TOTAL) { "REDBLACK_RECOVERY_TOTAL_MISMATCH" }
        require(inputs.map { it.idHash }.distinct().size == EXPECTED_TOTAL) { "REDBLACK_RECOVERY_DUPLICATE_ID_HASH" }
        require(inputs.count(RedblackReviewRecoveryInput::sourceDeleted) == EXPECTED_DELETED) {
            "REDBLACK_RECOVERY_DELETED_COUNT_MISMATCH"
        }
        return inputs.mapIndexed { index, input ->
            require(HASH.matches(input.idHash) && HASH.matches(input.headlineHash) && HASH.matches(input.contentHash)) {
                "REDBLACK_RECOVERY_HASH_INVALID"
            }
            if (input.sourceDeleted) return@mapIndexed RedblackMaskedRecovery(input, null, null)
            val headline = masking.mask(input.headline, "redblack_recovery_title_$index", recordAudit = false)
            val content = masking.mask(input.content, "redblack_recovery_content_$index", recordAudit = false)
            require(headline.maskingStatus == "PASS" && content.maskingStatus == "PASS") {
                "REDBLACK_RECOVERY_MASKING_NOT_PASS"
            }
            require(headline.maskedText.isNotBlank() && content.maskedText.isNotBlank()) {
                "REDBLACK_RECOVERY_SAFE_COPY_EMPTY"
            }
            require(headline.maskedText.length <= 500 && content.maskedText.toByteArray(Charsets.UTF_8).size <= 65_535) {
                "REDBLACK_RECOVERY_SAFE_COPY_TOO_LONG"
            }
            RedblackMaskedRecovery(input, headline.maskedText, content.maskedText)
        }
    }

    companion object {
        const val EXPECTED_TOTAL = 25
        const val EXPECTED_DELETED = 1
        const val EXPECTED_ELIGIBLE = 24
        private val HASH = Regex("^[0-9a-f]{64}$")
    }
}

private data class DatabaseRecoveryRow(
    val sourceId: Long,
    val maskingStatus: String,
    val contentDeleted: Boolean,
    val sourceDeleted: Boolean,
    val headlineHash: String,
    val contentHash: String
)

private enum class RecoveryMode { DRY_RUN, APPLY }

fun main() {
    val inputPath = requiredEnvironment("REDBLACK_RECOVERY_INPUT")
    val mode = runCatching { RecoveryMode.valueOf(requiredEnvironment("REDBLACK_RECOVERY_MODE")) }
        .getOrElse { error("REDBLACK_RECOVERY_MODE_INVALID") }
    if (mode == RecoveryMode.APPLY) {
        require(requiredEnvironment("REDBLACK_RECOVERY_CONFIRM") == APPLY_CONFIRMATION) {
            "REDBLACK_RECOVERY_CONFIRMATION_INVALID"
        }
        error("REDBLACK_REVIEW_RECOVERY_APPLY_RETIRED_AFTER_COMPLETION")
    }
    val inputBytes = Files.readAllBytes(Path.of(inputPath))
    val mapper = jacksonObjectMapper()
    val inputs: List<RedblackReviewRecoveryInput> = mapper.readValue(inputBytes)
    val noAuditRepository = MaskingAuditRepository(object : MaskingAuditMapper {
        override fun insert(audit: MaskingAudit): Int = error("REDBLACK_RECOVERY_UNEXPECTED_MASKING_AUDIT_WRITE")
    })
    val planner = RedblackReviewRecoveryPlanner(MaskingPipeline(noAuditRepository, mapper, NoopNerMasker))
    val prepared = planner.prepare(inputs)
    require(prepared.count { it.safeHeadline != null && it.safeContent != null } == RedblackReviewRecoveryPlanner.EXPECTED_ELIGIBLE) {
        "REDBLACK_RECOVERY_ELIGIBLE_COUNT_MISMATCH"
    }

    Class.forName("com.mysql.cj.jdbc.Driver")
    DriverManager.getConnection(
        requiredEnvironment("DB_URL"),
        requiredEnvironment("DB_USERNAME"),
        requiredEnvironment("DB_PASSWORD")
    ).use { connection ->
        connection.autoCommit = false
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.isReadOnly = mode == RecoveryMode.DRY_RUN
        try {
            val inputDigest = sha256(inputBytes)
            if (mode == RecoveryMode.APPLY) lockProjectionControl(connection)
            val databaseByHash = prepared.associate { recovery ->
                recovery.input.idHash to loadDatabaseRow(connection, recovery.input.idHash, mode == RecoveryMode.APPLY)
            }
            prepared.forEach { recovery -> validateDatabaseRow(recovery, databaseByHash.getValue(recovery.input.idHash)) }

            var recovered = 0
            var markedDeleted = 0
            if (mode == RecoveryMode.APPLY) {
                prepared.forEach { recovery ->
                    val row = databaseByHash.getValue(recovery.input.idHash)
                    if (recovery.input.sourceDeleted) {
                        markSourceDeleted(connection, row.sourceId)
                        insertAudit(connection, row.sourceId, inputDigest, "REDBLACK_SOURCE_DELETION_RECONCILED")
                        markedDeleted++
                    } else {
                        updateSafeContent(connection, row.sourceId, recovery.safeHeadline!!, recovery.safeContent!!)
                        insertAudit(connection, row.sourceId, inputDigest, "REDBLACK_MASKING_RECOVERY")
                        recovered++
                    }
                    markProjectionDirty(connection, row.sourceId)
                }
                incrementProjectionEpoch(connection)
                require(recovered == RedblackReviewRecoveryPlanner.EXPECTED_ELIGIBLE) { "REDBLACK_RECOVERY_UPDATED_COUNT_MISMATCH" }
                require(markedDeleted == RedblackReviewRecoveryPlanner.EXPECTED_DELETED) { "REDBLACK_RECOVERY_DELETED_UPDATE_MISMATCH" }
                connection.commit()
            } else {
                connection.rollback()
            }
            println(
                mapper.writeValueAsString(
                    sortedMapOf(
                        "eligible" to RedblackReviewRecoveryPlanner.EXPECTED_ELIGIBLE,
                        "markedDeleted" to markedDeleted,
                        "matched" to prepared.size,
                        "mode" to mode.name,
                        "recovered" to recovered,
                        "sourceDeleted" to RedblackReviewRecoveryPlanner.EXPECTED_DELETED,
                    )
                )
            )
        } catch (error: Exception) {
            connection.rollback()
            throw error
        }
    }
}

private fun loadDatabaseRow(connection: Connection, idHash: String, forUpdate: Boolean): DatabaseRecoveryRow {
    val sql = """
        SELECT s.id,c.masking_status,c.is_deleted,rb.is_deleted_src,
               LOWER(SHA2(COALESCE(rb.headline,''),256)),LOWER(SHA2(COALESCE(rb.content_text,''),256))
        FROM t_feedback_source s
        JOIN t_feedback_content c ON c.source_id=s.id
        JOIN t_channel_redblack rb ON rb.source_id=s.id
        WHERE s.source_channel='REDBLACK' AND LOWER(SHA2(s.source_record_id,256))=?
    """.trimIndent() + if (forUpdate) " FOR UPDATE" else ""
    connection.prepareStatement(sql).use { statement ->
        statement.setString(1, idHash)
        statement.executeQuery().use { results ->
            require(results.next()) { "REDBLACK_RECOVERY_DATABASE_ROW_MISSING" }
            val row = DatabaseRecoveryRow(
                sourceId = results.getLong(1),
                maskingStatus = results.getString(2),
                contentDeleted = results.getBoolean(3),
                sourceDeleted = results.getBoolean(4),
                headlineHash = results.getString(5),
                contentHash = results.getString(6)
            )
            require(!results.next()) { "REDBLACK_RECOVERY_DATABASE_ROW_AMBIGUOUS" }
            return row
        }
    }
}

private fun validateDatabaseRow(recovery: RedblackMaskedRecovery, row: DatabaseRecoveryRow) {
    require(row.maskingStatus == "REVIEW_NEEDED") { "REDBLACK_RECOVERY_STATUS_CHANGED" }
    require(!row.contentDeleted) { "REDBLACK_RECOVERY_CONTENT_ALREADY_DELETED" }
    require(row.sourceDeleted == recovery.input.sourceDeleted) { "REDBLACK_RECOVERY_SOURCE_DELETION_MISMATCH" }
}

private fun updateSafeContent(connection: Connection, sourceId: Long, headline: String, content: String) {
    connection.prepareStatement(
        """UPDATE t_feedback_content c
           JOIN t_channel_redblack rb ON rb.source_id=c.source_id
           SET c.title=?,c.description=?,c.masking_status='PASS',c.updated_at=NOW(3)
           WHERE c.source_id=? AND c.masking_status='REVIEW_NEEDED' AND c.is_deleted=0 AND rb.is_deleted_src=0"""
    ).use { statement ->
        statement.setString(1, headline)
        statement.setString(2, content)
        statement.setLong(3, sourceId)
        require(statement.executeUpdate() == 1) { "REDBLACK_RECOVERY_CONTENT_UPDATE_FAILED" }
    }
}

private fun markSourceDeleted(connection: Connection, sourceId: Long) {
    connection.prepareStatement(
        """UPDATE t_feedback_content c
           JOIN t_channel_redblack rb ON rb.source_id=c.source_id
           SET c.is_deleted=1,c.updated_at=NOW(3)
           WHERE c.source_id=? AND c.masking_status='REVIEW_NEEDED' AND c.is_deleted=0 AND rb.is_deleted_src=1"""
    ).use { statement ->
        statement.setLong(1, sourceId)
        require(statement.executeUpdate() == 1) { "REDBLACK_RECOVERY_SOURCE_DELETE_UPDATE_FAILED" }
    }
}

private fun lockProjectionControl(connection: Connection) {
    connection.prepareStatement(
        "SELECT mutation_epoch FROM t_phase_one_feedback_list_projection_control WHERE control_id=1 FOR UPDATE"
    ).use { statement ->
        statement.executeQuery().use { results -> require(results.next()) { "REDBLACK_RECOVERY_PROJECTION_CONTROL_MISSING" } }
    }
}

private fun incrementProjectionEpoch(connection: Connection) {
    connection.prepareStatement(
        "UPDATE t_phase_one_feedback_list_projection_control SET mutation_epoch=mutation_epoch+1 WHERE control_id=1"
    ).use { statement -> require(statement.executeUpdate() == 1) { "REDBLACK_RECOVERY_PROJECTION_EPOCH_UPDATE_FAILED" } }
}

private fun markProjectionDirty(connection: Connection, sourceId: Long) {
    connection.prepareStatement(
        """INSERT INTO t_phase_one_feedback_list_projection_dirty(
               source_id,mutation_version,dirty_reason,task_status,retry_count,next_retry_at,
               lease_owner,lease_expires_at,last_error_code,last_error_summary
           ) VALUES (?,1,'CONTENT','PENDING',0,NULL,NULL,NULL,NULL,NULL) AS incoming
           ON DUPLICATE KEY UPDATE
               mutation_version=t_phase_one_feedback_list_projection_dirty.mutation_version+1,
               dirty_reason=incoming.dirty_reason,task_status='PENDING',retry_count=0,next_retry_at=NULL,
               lease_owner=NULL,lease_expires_at=NULL,last_error_code=NULL,last_error_summary=NULL"""
    ).use { statement ->
        statement.setLong(1, sourceId)
        require(statement.executeUpdate() >= 1) { "REDBLACK_RECOVERY_PROJECTION_DIRTY_FAILED" }
    }
}

private fun insertAudit(connection: Connection, sourceId: Long, inputDigest: String, operation: String) {
    connection.prepareStatement(
        """INSERT INTO t_system_audit_log(operation,audit_class,outcome,target_type,target_id,operation_detail,event_time)
           VALUES (?,'SECURITY','SUCCESS','FEEDBACK_SOURCE',CAST(? AS CHAR),
                   JSON_OBJECT('ruleVersion',?,'inputSha256',?,'releaseMode','RULE_MASKED_AUTO_RELEASE'),NOW(3))"""
    ).use { statement ->
        statement.setString(1, operation)
        statement.setLong(2, sourceId)
        statement.setString(3, MaskingPipeline.RULE_VERSION)
        statement.setString(4, inputDigest)
        require(statement.executeUpdate() == 1) { "REDBLACK_RECOVERY_AUDIT_INSERT_FAILED" }
    }
}

private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
    ?: error("${name}_REQUIRED")

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private const val APPLY_CONFIRMATION = "RECOVER_24_AND_MARK_1_REDBLACK_SOURCE_DELETED"

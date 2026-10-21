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

data class RedblackSourceDeletionCorrectionInput(
    val sourceFileHash: String,
    val idHash: String,
    val headline: String,
    val content: String,
    val headlineHash: String,
    val contentHash: String
)

data class RedblackSourceDeletionCorrection(
    val input: RedblackSourceDeletionCorrectionInput,
    val safeHeadline: String,
    val safeContent: String
)

class RedblackSourceDeletionCorrectionPlanner(private val masking: MaskingPipeline) {
    fun prepare(input: RedblackSourceDeletionCorrectionInput): RedblackSourceDeletionCorrection {
        require(input.sourceFileHash == EXPECTED_SOURCE_FILE_HASH) {
            "REDBLACK_SOURCE_DELETION_CORRECTION_SOURCE_FILE_CHANGED"
        }
        require(HASH.matches(input.idHash) && HASH.matches(input.headlineHash) && HASH.matches(input.contentHash)) {
            "REDBLACK_SOURCE_DELETION_CORRECTION_HASH_INVALID"
        }
        val headline = masking.mask(input.headline, "redblack_source_deletion_correction_title", recordAudit = false)
        val content = masking.mask(input.content, "redblack_source_deletion_correction_content", recordAudit = false)
        require(headline.maskingStatus == "PASS" && content.maskingStatus == "PASS") {
            "REDBLACK_SOURCE_DELETION_CORRECTION_MASKING_NOT_PASS"
        }
        require(headline.maskedText.isNotBlank() && content.maskedText.isNotBlank()) {
            "REDBLACK_SOURCE_DELETION_CORRECTION_SAFE_COPY_EMPTY"
        }
        require(headline.maskedText.length <= 500 && content.maskedText.toByteArray(Charsets.UTF_8).size <= 65_535) {
            "REDBLACK_SOURCE_DELETION_CORRECTION_SAFE_COPY_TOO_LONG"
        }
        return RedblackSourceDeletionCorrection(input, headline.maskedText, content.maskedText)
    }

    companion object {
        private val HASH = Regex("^[0-9a-f]{64}$")
        const val EXPECTED_SOURCE_FILE_HASH =
            "68f1155c20189c9a97d6f5b54f073d7a285649c708290d071778b709b5864128"
    }
}

private data class CorrectionDatabaseRow(
    val sourceId: Long,
    val maskingStatus: String,
    val contentDeleted: Boolean,
    val sourceDeleted: Boolean,
    val aiFactCount: Int
)

private enum class CorrectionMode { DRY_RUN, APPLY }

fun main() {
    val inputPath = correctionEnvironment("REDBLACK_SOURCE_DELETION_CORRECTION_INPUT")
    val mode = runCatching {
        CorrectionMode.valueOf(correctionEnvironment("REDBLACK_SOURCE_DELETION_CORRECTION_MODE"))
    }.getOrElse { error("REDBLACK_SOURCE_DELETION_CORRECTION_MODE_INVALID") }
    if (mode == CorrectionMode.APPLY) {
        require(correctionEnvironment("REDBLACK_SOURCE_DELETION_CORRECTION_CONFIRM") == CORRECTION_APPLY_CONFIRMATION) {
            "REDBLACK_SOURCE_DELETION_CORRECTION_CONFIRMATION_INVALID"
        }
    }
    val inputBytes = Files.readAllBytes(Path.of(inputPath))
    val mapper = jacksonObjectMapper()
    val input: RedblackSourceDeletionCorrectionInput = mapper.readValue(inputBytes)
    val noAuditRepository = MaskingAuditRepository(object : MaskingAuditMapper {
        override fun insert(audit: MaskingAudit): Int =
            error("REDBLACK_SOURCE_DELETION_CORRECTION_UNEXPECTED_MASKING_AUDIT_WRITE")
    })
    val prepared = RedblackSourceDeletionCorrectionPlanner(
        MaskingPipeline(noAuditRepository, mapper, NoopNerMasker)
    ).prepare(input)

    Class.forName("com.mysql.cj.jdbc.Driver")
    DriverManager.getConnection(
        correctionEnvironment("DB_URL"),
        correctionEnvironment("DB_USERNAME"),
        correctionEnvironment("DB_PASSWORD")
    ).use { connection ->
        connection.autoCommit = false
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.isReadOnly = mode == CorrectionMode.DRY_RUN
        try {
            if (mode == CorrectionMode.APPLY) lockProjectionControlForCorrection(connection)
            val row = loadCorrectionRow(connection, input.idHash, mode == CorrectionMode.APPLY)
            validateCorrectionRow(connection, prepared, row, mode == CorrectionMode.APPLY)
            var recovered = 0
            if (mode == CorrectionMode.APPLY) {
                restoreSafeContent(connection, row.sourceId, prepared.safeHeadline, prepared.safeContent)
                markAiPending(connection, row.sourceId, row.aiFactCount)
                insertCorrectionAudit(connection, row.sourceId, sha256Correction(inputBytes))
                markProjectionDirtyForCorrection(connection, row.sourceId)
                incrementProjectionEpochForCorrection(connection)
                connection.commit()
                recovered = 1
            } else {
                connection.rollback()
            }
            println(mapper.writeValueAsString(sortedMapOf(
                "matched" to 1,
                "mode" to mode.name,
                "recovered" to recovered,
                "sourceDeletionFactPreserved" to true
            )))
        } catch (error: Exception) {
            connection.rollback()
            throw error
        }
    }
}

private fun loadCorrectionRow(connection: Connection, idHash: String, forUpdate: Boolean): CorrectionDatabaseRow {
    val sql = """
        SELECT s.id,c.masking_status,c.is_deleted,rb.is_deleted_src,
               (SELECT COUNT(*) FROM t_feedback_ai a WHERE a.source_id=s.id)
        FROM t_feedback_source s
        JOIN t_feedback_content c ON c.source_id=s.id
        JOIN t_channel_redblack rb ON rb.source_id=s.id
        WHERE s.source_channel='REDBLACK' AND LOWER(SHA2(s.source_record_id,256))=?
    """.trimIndent() + if (forUpdate) " FOR UPDATE" else ""
    connection.prepareStatement(sql).use { statement ->
        statement.setString(1, idHash)
        statement.executeQuery().use { results ->
            require(results.next()) { "REDBLACK_SOURCE_DELETION_CORRECTION_DATABASE_ROW_MISSING" }
            val row = CorrectionDatabaseRow(
                results.getLong(1), results.getString(2), results.getBoolean(3), results.getBoolean(4),
                results.getInt(5)
            )
            require(!results.next()) { "REDBLACK_SOURCE_DELETION_CORRECTION_DATABASE_ROW_AMBIGUOUS" }
            return row
        }
    }
}

private fun validateCorrectionRow(
    connection: Connection,
    correction: RedblackSourceDeletionCorrection,
    row: CorrectionDatabaseRow,
    lockLinks: Boolean
) {
    require(row.maskingStatus == "REVIEW_NEEDED" && row.contentDeleted) {
        "REDBLACK_SOURCE_DELETION_CORRECTION_CONTENT_STATE_CHANGED"
    }
    require(row.sourceDeleted) { "REDBLACK_SOURCE_DELETION_CORRECTION_SOURCE_FACT_CHANGED" }
    require(row.aiFactCount in 0..1) { "REDBLACK_SOURCE_DELETION_CORRECTION_AI_FACT_AMBIGUOUS" }
    val suffix = if (lockLinks) " FOR UPDATE" else ""
    connection.prepareStatement(
        "SELECT id FROM t_cluster_link WHERE feedback_source_id=? AND is_active=1" + suffix
    ).use { statement ->
        statement.setLong(1, row.sourceId)
        statement.executeQuery().use { results ->
            require(!results.next()) { "REDBLACK_SOURCE_DELETION_CORRECTION_ACTIVE_LINK_PRESENT" }
        }
    }
}

private fun restoreSafeContent(connection: Connection, sourceId: Long, headline: String, content: String) {
    connection.prepareStatement(
        """UPDATE t_feedback_content c
           JOIN t_channel_redblack rb ON rb.source_id=c.source_id
           SET c.title=?,c.description=?,c.masking_status='PASS',c.is_deleted=0,c.updated_at=NOW(3)
           WHERE c.source_id=? AND c.masking_status='REVIEW_NEEDED' AND c.is_deleted=1 AND rb.is_deleted_src=1"""
    ).use { statement ->
        statement.setString(1, headline)
        statement.setString(2, content)
        statement.setLong(3, sourceId)
        require(statement.executeUpdate() == 1) { "REDBLACK_SOURCE_DELETION_CORRECTION_UPDATE_FAILED" }
    }
}

private fun markAiPending(connection: Connection, sourceId: Long, expected: Int) {
    connection.prepareStatement(
        """UPDATE t_feedback_ai SET search_index_status='PENDING',vector_index_status='PENDING',
           search_document_id=NULL,milvus_vector_id=NULL,updated_at=NOW(3) WHERE source_id=?"""
    ).use { statement ->
        statement.setLong(1, sourceId)
        require(statement.executeUpdate() == expected) { "REDBLACK_SOURCE_DELETION_CORRECTION_AI_UPDATE_FAILED" }
    }
}

private fun lockProjectionControlForCorrection(connection: Connection) {
    connection.prepareStatement(
        "SELECT mutation_epoch FROM t_phase_one_feedback_list_projection_control WHERE control_id=1 FOR UPDATE"
    ).use { statement ->
        statement.executeQuery().use { results ->
            require(results.next()) { "REDBLACK_SOURCE_DELETION_CORRECTION_PROJECTION_CONTROL_MISSING" }
        }
    }
}

private fun incrementProjectionEpochForCorrection(connection: Connection) {
    connection.prepareStatement(
        "UPDATE t_phase_one_feedback_list_projection_control SET mutation_epoch=mutation_epoch+1 WHERE control_id=1"
    ).use { statement ->
        require(statement.executeUpdate() == 1) { "REDBLACK_SOURCE_DELETION_CORRECTION_PROJECTION_EPOCH_UPDATE_FAILED" }
    }
}

private fun markProjectionDirtyForCorrection(connection: Connection, sourceId: Long) {
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
        require(statement.executeUpdate() >= 1) { "REDBLACK_SOURCE_DELETION_CORRECTION_PROJECTION_DIRTY_FAILED" }
    }
}

private fun insertCorrectionAudit(connection: Connection, sourceId: Long, inputDigest: String) {
    connection.prepareStatement(
        """INSERT INTO t_system_audit_log(operation,audit_class,outcome,target_type,target_id,operation_detail,event_time)
           VALUES ('REDBLACK_SOURCE_DELETION_INTERPRETATION_CORRECTED','SECURITY','SUCCESS',
                   'FEEDBACK_SOURCE',CAST(? AS CHAR),
                   JSON_OBJECT('ruleVersion',?,'inputSha256',?,'sourceDeletionFactPreserved',TRUE),NOW(3))"""
    ).use { statement ->
        statement.setLong(1, sourceId)
        statement.setString(2, MaskingPipeline.RULE_VERSION)
        statement.setString(3, inputDigest)
        require(statement.executeUpdate() == 1) { "REDBLACK_SOURCE_DELETION_CORRECTION_AUDIT_FAILED" }
    }
}

private fun correctionEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
    ?: error("${name}_REQUIRED")

private fun sha256Correction(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

const val CORRECTION_APPLY_CONFIRMATION =
    "RESTORE_ONE_REDBLACK_SOURCE_DELETED_CONTENT_WITHOUT_CHANGING_SOURCE_FACT"

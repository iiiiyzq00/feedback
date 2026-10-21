package com.feedback.service.imports

import com.feedback.repository.imports.ImportGovernanceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ImportBatchCleanupResult(
    val supersededBatches: Int,
    val noChangeBatches: Int,
    val activeBatches: Int,
    val preservedAuditFacts: Boolean = true
)

@Service
class ImportBatchGovernanceService(private val repository: ImportGovernanceRepository) {
    @Transactional
    fun cleanup(actorId: Long, reason: String): ImportBatchCleanupResult {
        val normalizedReason = reason.trim()
        ensureImport(normalizedReason.length in 10..500) { "IMPORT_BATCH_CLEANUP_REASON_REQUIRED" }
        val (superseded, noChange, active) = repository.cleanup(actorId, normalizedReason, java.time.LocalDateTime.now())
        return ImportBatchCleanupResult(superseded, noChange, active)
    }
}

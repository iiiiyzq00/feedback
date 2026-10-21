package com.feedback.repository.imports

import com.feedback.model.po.imports.ImportGovernanceBatchPo
import com.feedback.mapper.imports.ImportGovernanceMapper
import com.feedback.model.po.imports.ImportSupersedePo
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Base64

@Repository
class ImportGovernanceRepository(private val mapper: ImportGovernanceMapper) {
    fun cleanup(actor: Long, reason: String, now: LocalDateTime): Triple<Int, Int, Int> {
        val rows = mapper.findApiBatches()
        val eligibleNewer = rows.filter {
            it.triggerType == "BACKFILL" &&
            it.batchStatus in setOf("COMPLETED", "PARTIAL_SUCCESS") &&
                it.reconciliationStatus == "MATCHED"
        }.groupBy(::identityKey)
        val covered = rows.asSequence()
            .filter { it.governanceStatus == "ACTIVE" && it.totalRows > 0 }
            .mapNotNull { prior ->
                eligibleNewer[identityKey(prior)].orEmpty().asSequence()
                    .filter { newer -> newer.id > prior.id && covers(newer, prior) }
                    .maxByOrNull { it.id }
                    ?.let { ImportSupersedePo(prior.id, it.id) }
            }.toList()
        val superseded = covered.chunked(200).sumOf { mapper.supersede(it, reason, actor, now) }
        val noChange = mapper.archiveNoChange("$reason；零变更批次归档", actor, now)
        return Triple(superseded, noChange, mapper.countActive())
    }

    private fun identityKey(row: ImportGovernanceBatchPo) = listOf(
        row.sourceChannel,
        row.sourceInstanceHash?.let { Base64.getEncoder().encodeToString(it) }.orEmpty(),
        row.mappingConfigVersion.orEmpty()
    ).joinToString("\u0000")

    private fun covers(newer: ImportGovernanceBatchPo, prior: ImportGovernanceBatchPo): Boolean =
        (prior.syncWindowFrom == null || newer.syncWindowFrom?.let { !it.isAfter(prior.syncWindowFrom) } == true) &&
            (prior.syncWindowTo == null || newer.syncWindowTo?.let { !it.isBefore(prior.syncWindowTo) } == true)
}

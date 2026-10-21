package com.feedback.repository.masking

import com.feedback.mapper.masking.*
import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.springframework.stereotype.Repository

@Repository
class MaskingEvaluationEvidenceRepository(
    private val configs: MaskingEvidenceConfigMapper,
    private val audits: MaskingEvaluationAuditMapper
) {
    fun insert(row: MaskingEvidenceInsertPo, prefix: String): Long {
        if (configs.insert(row) != 1 || row.id <= 0) throw ServiceException(ErrorCode.INTERNAL_ERROR, "MASKING_EVIDENCE_INSERT_FAILED")
        if (configs.finalizeKey(row.id, prefix) != 1) throw ServiceException(ErrorCode.INTERNAL_ERROR, "MASKING_EVIDENCE_KEY_FINALIZE_FAILED")
        return row.id
    }
    fun reviewDataset(version: String, actor: Long) = configs.reviewDataset(version, actor) == 1
    fun approveDataset(version: String, actor: Long) = configs.approveDataset(version, actor) == 1
    fun datasets() = configs.findDatasets()
    fun releaseForUpdate(version: String) = configs.lockRelease(version)
    fun liveDataset(version: String) = configs.findLiveDataset(version)
    fun runExists(releaseId: Long, datasetId: Long, engine: String) = configs.countRun(releaseId, datasetId, engine) > 0
    fun runForUpdate(runId: Long) = configs.lockRun(runId)
    fun config(configType: String, businessId: Long) = configs.findByBusinessId(configType, businessId)
    fun approveRun(id: Long, actor: Long, reason: String) = configs.approveRun(id, actor, reason) == 1
    fun insertAudit(row: MaskingEvaluationAuditInsertPo): Long {
        if (audits.insert(row) != 1 || row.id <= 0) throw ServiceException(ErrorCode.INTERNAL_ERROR, "MASKING_EVALUATION_AUDIT_INSERT_FAILED")
        return row.id
    }
    fun evaluateRelease(id: Long, runId: Long, auditId: Long, actor: Long) =
        configs.evaluateRelease(id, runId, auditId, actor) == 1
    fun runs(version: String): List<Pair<MaskingEvidenceConfigPo, MaskingEvidenceConfigPo>> {
        val runs = configs.findRuns(version)
        val ids = runs.mapNotNull { it.recordVersion }.distinct()
        val datasets = if (ids.isEmpty()) emptyMap() else configs.findDatasetsByBusinessIds(ids).associateBy { it.businessId() }
        return runs.mapNotNull { run -> run.recordVersion?.let { datasets[it] }?.let { run to it } }
    }
    fun expireDatasets() = configs.expireDatasets()
}

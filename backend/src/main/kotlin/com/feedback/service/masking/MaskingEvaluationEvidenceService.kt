package com.feedback.service.masking

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.feedback.model.po.masking.MaskingEvaluationAuditInsertPo
import com.feedback.model.po.masking.MaskingEvidenceInsertPo
import com.feedback.repository.masking.MaskingEvaluationEvidenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDateTime

data class MaskingDatasetInput(val datasetVersion:String,val datasetSha256:String,val evidenceKind:String,
    val storageReference:String,val sampleCount:Int,val positiveCount:Int,val negativeCount:Int,val boundaryCount:Int,
    val piiApprovalReference:String?=null,val expiresAt:LocalDateTime)
data class MaskingFailureSummary(val caseIdHash:String,val category:String,val expectedType:String,val actualType:String){
    @JsonAnySetter fun rejectUnsafeField(name:String,value:Any?){maskingInvalid("MASKING_FAILURE_SUMMARY_UNKNOWN_FIELD")}
}
data class MaskingEvaluationRunInput(val datasetVersion:String,val engineVersion:String,
    val nerTp:Int,val nerFp:Int,val nerFn:Int,val endTp:Int,val endFp:Int,val endFn:Int,
    val highRiskTotal:Int,val highRiskMissed:Int,val negativeUnits:Int,val falseMaskedUnits:Int,
    val executionLogSha256:String,val failures:List<MaskingFailureSummary>)
data class MaskingRunApprovalInput(val reason:String)

@Service
class MaskingEvaluationEvidenceService(private val repository:MaskingEvaluationEvidenceRepository,private val mapper:ObjectMapper){
    @Transactional fun registerDataset(input:MaskingDatasetInput,actor:Long):Map<String,Any>{
        if(!input.datasetVersion.matches(Regex("[A-Za-z0-9._-]{3,80}"))||!input.datasetSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) maskingInvalid("MASKING_DATASET_ID_INVALID")
        if(input.evidenceKind !in setOf("FIXTURE","APPROVED_HISTORICAL")) maskingInvalid("MASKING_DATASET_KIND_INVALID")
        if(input.storageReference.trim().length !in 6..500||input.storageReference.contains("..")) maskingInvalid("MASKING_DATASET_REFERENCE_INVALID")
        if(input.sampleCount<100||input.sampleCount!=input.positiveCount+input.negativeCount||input.positiveCount<=0||input.negativeCount<=0||input.boundaryCount !in 0..input.sampleCount) maskingInvalid("MASKING_DATASET_DISTRIBUTION_INVALID")
        if(input.evidenceKind=="APPROVED_HISTORICAL"&&(input.boundaryCount<20||input.piiApprovalReference.isNullOrBlank()||input.piiApprovalReference.length>200)) maskingInvalid("MASKING_HISTORICAL_APPROVAL_BOUNDARY_REQUIRED")
        if(input.expiresAt<=LocalDateTime.now().plusDays(1)) maskingInvalid("MASKING_DATASET_EXPIRY_INVALID")
        val value=mapper.writeValueAsString(mapOf("storageReference" to input.storageReference.trim(),
            "positiveCount" to input.positiveCount,"negativeCount" to input.negativeCount,
            "boundaryCount" to input.boundaryCount,"piiApprovalReference" to input.piiApprovalReference))
        repository.insert(MaskingEvidenceInsertPo(configType="MASKING_DATASET",configVersion=input.datasetVersion,
            configValueJson=value,scopeKey=input.datasetSha256.lowercase(),configSubtype=input.evidenceKind,
            subjectUserId=actor,recordStatus="REGISTERED",recordVersion=input.sampleCount.toLong(),
            effectiveTo=input.expiresAt,createdBy=actor),"MASKING_DATASET:")
        return mapOf("datasetVersion" to input.datasetVersion,"status" to "REGISTERED")
    }
    @Transactional fun reviewDataset(version:String,actor:Long):Map<String,Any>{if(!repository.reviewDataset(version,actor)) maskingInvalid("MASKING_DATASET_INDEPENDENT_REVIEW_REQUIRED");return mapOf("datasetVersion" to version,"status" to "REVIEWED")}
    @Transactional fun approveDataset(version:String,actor:Long):Map<String,Any>{if(!repository.approveDataset(version,actor)) maskingInvalid("MASKING_DATASET_INDEPENDENT_APPROVAL_REQUIRED");return mapOf("datasetVersion" to version,"status" to "APPROVED")}
    fun datasets()=repository.datasets().map { row -> linkedMapOf<String,Any?>("datasetVersion" to row.configVersion,
        "datasetSha256" to row.scopeKey,"evidenceKind" to row.configSubtype,"storageReference" to row.storageReference,
        "sampleCount" to row.recordVersion,"positiveCount" to row.positiveCount,"negativeCount" to row.negativeCount,
        "boundaryCount" to row.boundaryCount,"piiApprovalReference" to row.piiApprovalReference,"status" to row.recordStatus,
        "annotatedBy" to row.subjectUserId,"reviewedBy" to row.reviewedBy,"approvedBy" to row.secondaryUserId,
        "expiresAt" to row.effectiveTo,"createdAt" to row.createdAt) }

    @Transactional fun execute(version:String,input:MaskingEvaluationRunInput,actor:Long):Map<String,Any>{validateRun(input)
        val release=repository.releaseForUpdate(version)?:maskingInvalid("MASKING_RELEASE_NOT_DRAFT")
        if(release.recordStatus!="DRAFT") maskingInvalid("MASKING_RELEASE_NOT_DRAFT")
        val dataset=repository.liveDataset(input.datasetVersion)?:maskingInvalid("MASKING_DATASET_NOT_APPROVED_OR_EXPIRED")
        if(dataset.recordStatus!="APPROVED") maskingInvalid("MASKING_DATASET_NOT_APPROVED_OR_EXPIRED")
        if(repository.runExists(release.businessId(),dataset.businessId(),input.engineVersion)) maskingInvalid("MASKING_EVALUATION_RUN_ALREADY_EXISTS")
        val nerF1=f1(input.nerTp,input.nerFp,input.nerFn);val endF1=f1(input.endTp,input.endFp,input.endFn);val highRecall=ratio(input.highRiskTotal-input.highRiskMissed,input.highRiskTotal);val falseRate=ratio(input.falseMaskedUnits,input.negativeUnits)
        val passed=nerF1>=bd("0.75")&&endF1>=bd("0.90")&&highRecall>=bd("0.98")&&falseRate<bd("0.05")
        val failuresJson=mapper.writeValueAsString(input.failures);val failureSha=sha256(failuresJson)
        val value=mapper.writeValueAsString(mapOf("nerTp" to input.nerTp,"nerFp" to input.nerFp,"nerFn" to input.nerFn,
            "endTp" to input.endTp,"endFp" to input.endFp,"endFn" to input.endFn,"highRiskTotal" to input.highRiskTotal,
            "highRiskMissed" to input.highRiskMissed,"negativeUnits" to input.negativeUnits,"falseMaskedUnits" to input.falseMaskedUnits,
            "nerF1" to nerF1,"endToEndF1" to endF1,"highRiskRecall" to highRecall,"falseMaskingRate" to falseRate,
            "executionLogSha256" to input.executionLogSha256.lowercase(),"failureSummary" to input.failures,
            "failureSummarySha256" to failureSha))
        val id=repository.insert(MaskingEvidenceInsertPo(configType="MASKING_RUN",configVersion=input.engineVersion,
            configValueJson=value,scopeKey=version,configSubtype=if(passed)"PASSED" else "FAILED",subjectUserId=actor,
            recordStatus="COMPLETED",recordVersion=dataset.businessId(),sourceConfigId=release.businessId(),createdBy=actor),"MASKING_RUN:")
        return mapOf("runId" to id,"gate" to if(passed)"PASSED" else "FAILED","nerF1" to nerF1,"endToEndF1" to endF1,"highRiskRecall" to highRecall,"falseMaskingRate" to falseRate,"failureSummarySha256" to failureSha)
    }
    @Transactional fun approveRun(runId:Long,input:MaskingRunApprovalInput,actor:Long):Map<String,Any>{if(input.reason.trim().length !in 6..500) maskingInvalid("MASKING_RUN_APPROVAL_REASON_REQUIRED")
        val run=repository.runForUpdate(runId)?:maskingInvalid("MASKING_PRODUCTION_RUN_APPROVAL_GATE_FAILED")
        val datasetId=run.recordVersion?:maskingInvalid("MASKING_PRODUCTION_RUN_APPROVAL_GATE_FAILED")
        val releaseId=run.sourceConfigId?:maskingInvalid("MASKING_PRODUCTION_RUN_APPROVAL_GATE_FAILED")
        val dataset=repository.config("MASKING_DATASET",datasetId)?:maskingInvalid("MASKING_PRODUCTION_RUN_APPROVAL_GATE_FAILED")
        val release=repository.config("MASKING_RELEASE",releaseId)?:maskingInvalid("MASKING_PRODUCTION_RUN_APPROVAL_GATE_FAILED")
        if(!(run.recordStatus=="COMPLETED"&&run.configSubtype=="PASSED"&&run.subjectUserId!=actor&&
            dataset.configSubtype=="APPROVED_HISTORICAL"&&dataset.recordStatus=="APPROVED"&&
            dataset.effectiveTo?.isAfter(LocalDateTime.now())==true&&(dataset.recordVersion?:0)>=100)) maskingInvalid("MASKING_PRODUCTION_RUN_APPROVAL_GATE_FAILED")
        if(!repository.approveRun(run.id,actor,input.reason.trim())) maskingFailed("MASKING_RUN_STATE_CHANGED")
        val details=mapper.writeValueAsString(mapOf("runId" to run.id,"datasetVersion" to dataset.configVersion,
            "datasetSha256" to dataset.scopeKey,"failureSummarySha256" to run.failureSummarySha256,
            "executionLogSha256" to run.executionLogSha256))
        val audit=repository.insertAudit(MaskingEvaluationAuditInsertPo(maskingRuleVersion=release.configVersion,
            reviewer=actor,evaluationKey="run:${run.id}",evalF1Score=bd(run.endToEndF1?:"0"),nerF1Score=bd(run.nerF1?:"0"),
            highRiskRecall=bd(run.highRiskRecall?:"0"),falseMaskingRate=bd(run.falseMaskingRate?:"0"),
            samples=(dataset.recordVersion?:0).toInt(),errorDetailsJson=details))
        if(!repository.evaluateRelease(release.id,runId,audit,actor)) maskingFailed("MASKING_RELEASE_STATE_CHANGED")
        return mapOf("runId" to runId,"status" to "APPROVED","releaseStatus" to "EVALUATED")
    }
    fun runs(version:String)=repository.runs(version).map { (run,dataset) -> linkedMapOf<String,Any?>(
        "runId" to run.businessId(),"datasetVersion" to dataset.configVersion,"evidenceKind" to dataset.configSubtype,
        "engineVersion" to run.configVersion,"status" to run.recordStatus,"gateResult" to run.configSubtype,
        "nerF1" to run.nerF1,"endToEndF1" to run.endToEndF1,"highRiskRecall" to run.highRiskRecall,
        "falseMaskingRate" to run.falseMaskingRate,"executionLogSha256" to run.executionLogSha256,
        "failureSummarySha256" to run.failureSummarySha256,"executedBy" to run.subjectUserId,
        "approvedBy" to run.secondaryUserId,"executedAt" to run.effectiveFrom,"approvedAt" to run.approvedAt) }
    @Transactional fun expireDatasets()=repository.expireDatasets()
    private fun validateRun(i:MaskingEvaluationRunInput){if(!i.engineVersion.matches(Regex("[A-Za-z0-9._:-]{3,100}"))||!i.executionLogSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) maskingInvalid("MASKING_RUN_IDENTITY_INVALID");if(!listOf(i.nerTp,i.nerFp,i.nerFn,i.endTp,i.endFp,i.endFn,i.highRiskTotal,i.highRiskMissed,i.negativeUnits,i.falseMaskedUnits).all{it>=0}||i.highRiskTotal<=0||i.highRiskMissed>i.highRiskTotal||i.negativeUnits<=0||i.falseMaskedUnits>i.negativeUnits) maskingInvalid("MASKING_RUN_COUNTS_INVALID");if(i.failures.size>500||i.failures.any{!it.caseIdHash.matches(Regex("^[a-fA-F0-9]{64}$"))||!it.category.matches(Regex("[A-Z_]{2,50}"))||!it.expectedType.matches(Regex("[A-Z_]{2,50}"))||!it.actualType.matches(Regex("[A-Z_]{2,50}"))}) maskingInvalid("MASKING_FAILURE_SUMMARY_UNSAFE")}
    private fun f1(tp:Int,fp:Int,fn:Int):BigDecimal{val p=ratio(tp,tp+fp);val r=ratio(tp,tp+fn);return if(p+r==BigDecimal.ZERO)BigDecimal.ZERO else bd("2").multiply(p).multiply(r).divide(p+r,6,RoundingMode.HALF_UP)}
    private fun ratio(a:Int,b:Int)=if(b==0)BigDecimal.ZERO else BigDecimal(a).divide(BigDecimal(b),6,RoundingMode.HALF_UP)
    private fun bd(v:String)=BigDecimal(v)
    private fun sha256(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}
}

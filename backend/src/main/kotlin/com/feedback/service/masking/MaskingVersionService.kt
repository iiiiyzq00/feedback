package com.feedback.service.masking

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.masking.MaskingConfigPo
import com.feedback.model.po.masking.MaskingRuntimeConfigWritePo
import com.feedback.repository.masking.MaskingConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class MaskingEvaluationInput(val nerF1:Double,val endToEndF1:Double,val highRiskRecall:Double,val falseMaskingRate:Double,val passedSamples:Int,val totalSamples:Int,val note:String="")
data class MaskingCandidateInput(val enabledRules:Set<String>,val allowlistVersion:String,val reason:String)
data class MaskingVersionCommandResponse(val version:String,val status:String)
data class MaskingVersionView(
    val id:Long,val version:String,val enabledRules:JsonNode,val allowlistVersion:String?,val status:String?,
    val evaluationAuditId:Long?,val evaluationRunId:Long?,val reason:String?,val createdAt:LocalDateTime?,
    val publishedAt:LocalDateTime?,val gateResult:String?,val nerF1:String?,val endToEndF1:String?,
    val highRiskRecall:String?,val falseMaskingRate:String?,val executionLogSha256:String?,val failureSummarySha256:String?,
    val datasetVersion:String?,val datasetSha256:String?,val evidenceKind:String?,val datasetStatus:String?,
    val datasetExpiresAt:LocalDateTime?
)

@Service
class MaskingVersionService(private val configs:MaskingConfigRepository,private val mapper:ObjectMapper) {
    fun versions():List<MaskingVersionView> {
        val runs=configs.byType("MASKING_RUN").associateBy{it.configKey}
        val datasets=configs.byType("MASKING_DATASET").associateBy{it.configKey}
        return configs.byType("MASKING_RELEASE").map{release->
            val value=mapper.readTree(release.configValue)
            val run=release.recordVersion?.let{runs["MASKING_RUN:$it"]}
            val runValue=run?.configValue?.let(mapper::readTree)
            val dataset=run?.recordVersion?.let{datasets["MASKING_DATASET:$it"]}
            MaskingVersionView(
                id=value.path("legacyId").longValue().takeIf{it>0}?:release.id,version=release.configVersion,
                enabledRules=value.path("enabledRules"),allowlistVersion=release.scopeKey,status=release.recordStatus,
                evaluationAuditId=value.path("evaluationAuditId").longValue().takeIf{it>0},evaluationRunId=release.recordVersion,
                reason=release.configDesc,createdAt=release.createdAt,publishedAt=release.effectiveFrom,gateResult=run?.configSubtype,
                nerF1=runValue?.path("nerF1")?.asText(null),endToEndF1=runValue?.path("endToEndF1")?.asText(null),
                highRiskRecall=runValue?.path("highRiskRecall")?.asText(null),falseMaskingRate=runValue?.path("falseMaskingRate")?.asText(null),
                executionLogSha256=runValue?.path("executionLogSha256")?.asText(null),failureSummarySha256=runValue?.path("failureSummarySha256")?.asText(null),
                datasetVersion=dataset?.configVersion,datasetSha256=dataset?.scopeKey,evidenceKind=dataset?.configSubtype,
                datasetStatus=dataset?.recordStatus,datasetExpiresAt=dataset?.effectiveTo)
        }
    }

    @Transactional
    fun candidate(input:MaskingCandidateInput,actor:Long):MaskingVersionCommandResponse {
        if(input.enabledRules.isEmpty()||input.enabledRules.any{it !in RULES}) maskingInvalid("MASKING_RULE_SET_INVALID")
        if(CRITICAL.any{it !in input.enabledRules}) maskingInvalid("MASKING_CRITICAL_RULE_REQUIRED")
        if(!input.allowlistVersion.matches(Regex("^allowlist-v[0-9]+$"))) maskingInvalid("NER_ALLOWLIST_VERSION_INVALID")
        if(input.reason.trim().length !in 2..500) maskingInvalid("MASKING_CHANGE_REASON_REQUIRED")
        val version="masking-v${configs.nextVersionNumber()}"
        configs.insertRelease(version,mapper.writeValueAsString(input.enabledRules.sorted()),input.allowlistVersion,input.reason.trim(),actor)
        return MaskingVersionCommandResponse(version,"DRAFT")
    }

    fun evaluate(version:String,input:MaskingEvaluationInput,actor:Long):MaskingVersionCommandResponse {
        maskingFailed("MASKING_LEGACY_METRIC_INPUT_DISABLED_USE_EVIDENCE_RUN")
    }

    @Transactional
    fun publish(version:String,reason:String,actor:Long):MaskingVersionCommandResponse {
        if(reason.trim().length !in 2..500) maskingInvalid("MASKING_PUBLISH_REASON_REQUIRED")
        val release=configs.releaseForUpdate(version)?:maskingNotFound("MASKING_RELEASE_NOT_FOUND")
        val runId=release.recordVersion?:maskingInvalid("MASKING_RELEASE_EVIDENCE_GATE_NOT_PASSED")
        val run=configs.byTypeAndKey("MASKING_RUN","MASKING_RUN:$runId")?:maskingInvalid("MASKING_RELEASE_EVIDENCE_GATE_NOT_PASSED")
        val datasetRunId=run.recordVersion?:maskingInvalid("MASKING_RELEASE_EVIDENCE_GATE_NOT_PASSED")
        val dataset=configs.byTypeAndKey("MASKING_DATASET","MASKING_DATASET:$datasetRunId")?:maskingInvalid("MASKING_RELEASE_EVIDENCE_GATE_NOT_PASSED")
        val runValue=mapper.readTree(run.configValue)
        if(!(release.recordStatus=="EVALUATED"&&run.recordStatus=="APPROVED"&&run.configSubtype=="PASSED"&&
            dataset.configSubtype=="APPROVED_HISTORICAL"&&dataset.recordStatus=="APPROVED"&&
            dataset.effectiveTo?.isAfter(LocalDateTime.now())==true&&
            runValue.path("executionLogSha256").asText().matches(Regex("^[a-f0-9]{64}$"))&&
            runValue.path("failureSummarySha256").asText().matches(Regex("^[a-f0-9]{64}$"))&&run.subjectUserId!=actor)) maskingInvalid("MASKING_RELEASE_EVIDENCE_GATE_NOT_PASSED")
        val releaseValue=mapper.readTree(release.configValue)
        configs.publishRelease(release.id,reason.trim(),actor)
        configs.replaceRuntime(listOf(
            MaskingRuntimeConfigWritePo("masking.rules-enabled",version,mapper.writeValueAsString(releaseValue.path("enabledRules")),"规则集",actor),
            MaskingRuntimeConfigWritePo("masking.rule-version",version,mapper.writeValueAsString(version),"运行版本",actor),
            MaskingRuntimeConfigWritePo("masking.ner-allowlist-version",version,mapper.writeValueAsString(release.scopeKey),"NER白名单版本",actor)))
        return MaskingVersionCommandResponse(version,"PUBLISHED")
    }

    companion object {
        val RULES=setOf("CREDENTIAL","ID_CARD","PASSPORT","DEVICE_ID","IMEI_IMSI","STUDENT_EMPLOYEE_ID","COORDINATE","BANK_CARD","PHONE","LANDLINE","EMAIL","MAC","IP","ACCOUNT")
        val CRITICAL=RULES-setOf("MAC","IP")
    }
}

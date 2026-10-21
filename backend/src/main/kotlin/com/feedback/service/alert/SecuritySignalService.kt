package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.SecuritySignalPo
import com.feedback.repository.alert.SecuritySignalRepository
import com.feedback.security.WebPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime

data class SecuritySignalInput(val signalType:String,val evidenceLevel:String,val riskLevel:String,val sourceType:String,val sourceReference:String,val evidenceCodes:List<String>)
data class SecuritySignalConclusionInput(val status:String,val conclusion:String)
data class SecuritySignalRecordResponse(val recorded:Boolean,val signalType:String,val evidenceLevel:String,val rawContentStored:Boolean)
data class SecuritySignalConclusionResponse(val id:Long,val status:String)

@Service
class SecuritySignalService(
    private val repository: SecuritySignalRepository,
    private val mapper: ObjectMapper
) {
    fun recordResidual(reference:String, findingTypes:List<String>) =
        record("SENSITIVE_RESIDUAL_BLOCKED","STRONG","HIGH","MASKING_PIPELINE",reference,findingTypes)

    @Transactional
    fun report(input:SecuritySignalInput):SecuritySignalRecordResponse {
        ensureAlert(input.signalType in setOf("DEVICE_SAFETY","SENSITIVE_EXPOSURE_CONFIRMED"))
        ensureAlert(input.evidenceLevel in setOf("SUSPECTED","STRONG","CONFIRMED"))
        ensureAlert(input.riskLevel in setOf("MEDIUM","HIGH","CRITICAL"))
        ensureAlert(input.sourceType.matches(Regex("[A-Z_]{2,30}")))
        ensureAlert(input.sourceReference.length in 3..200)
        ensureAlert(input.evidenceCodes.isNotEmpty() && input.evidenceCodes.size<=20 && input.evidenceCodes.all{it.matches(Regex("[A-Z0-9_:-]{2,50}"))})
        return record(input.signalType,input.evidenceLevel,input.riskLevel,input.sourceType,input.sourceReference,input.evidenceCodes)
    }

    fun metric(type:String,level:String?=null,at:LocalDateTime=LocalDateTime.now()):Double =
        repository.countOpen(type, level, at).toDouble()

    fun list() = repository.latest(200)

    @Transactional
    fun conclude(id:Long,input:SecuritySignalConclusionInput):SecuritySignalConclusionResponse {
        ensureAlert(input.status in setOf("MITIGATED","CLOSED"))
        ensureAlert(input.conclusion.trim().length in 10..500)
        ensureAlert(repository.conclude(id,input.status,input.conclusion.trim(),actor())) { "安全信号不存在或已闭环" }
        return SecuritySignalConclusionResponse(id,input.status)
    }

    private fun record(type:String,level:String,risk:String,source:String,reference:String,codes:List<String>):SecuritySignalRecordResponse {
        val ref=sha(reference)
        val snapshot=mapper.writeValueAsString(SecuritySignalEvidenceSnapshot(codes.distinct(),false,ref))
        repository.insertIgnore(SecuritySignalPo(
            signalType=type,evidenceLevel=level,riskLevel=risk,sourceType=source,
            sourceReferenceHash=ref,evidenceSnapshot=snapshot
        ))
        return SecuritySignalRecordResponse(true,type,level,false)
    }

    private fun actor()=(SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId
        ?:alertStateAbort("未认证")
    private fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString(""){"%02x".format(it)}
}

private data class SecuritySignalEvidenceSnapshot(
    val evidenceCodes: List<String>,
    val containsRawContent: Boolean,
    val sourceRefHash: String
)

package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.GovernanceAcceptancePo
import com.feedback.repository.alert.AlertProductionGovernanceRepository
import com.feedback.security.WebPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class AlertContractInput(val contractCode:String,val schemaVersion:String,val ownerTeam:String,val sourceSystem:String,val freshnessSeconds:Int,val completenessThreshold:Double)
data class GovernanceValidationInput(val evidenceSha256:String,val summary:String,val expiresAt:LocalDateTime)
data class ProviderAcceptanceInput(val channel:String,val providerCode:String,val environment:String="PRODUCTION",val capabilities:Map<String,Boolean>,val dailyQuota:Long,val idempotencySupported:Boolean,val evidenceSha256:String,val evidenceReference:String,val expiresAt:LocalDateTime)
data class RuleImplementationInput(val replayEvidenceSha256:String)
data class AlertRulePublishInput(val reason:String,val enable:Boolean=true)

@Service
class AlertProductionGovernanceService(private val repository:AlertProductionGovernanceRepository,private val mapper:ObjectMapper){
    @Transactional fun expireGovernance():Int=repository.expire()

    fun contracts()=repository.contracts()

    @Transactional fun saveContract(input:AlertContractInput):Map<String,Any>{
        ensureAlert(input.contractCode.matches(Regex("[A-Za-z0-9._-]{2,100}"))){"ALERT_CONTRACT_CODE_INVALID"};ensureAlert(input.schemaVersion.matches(Regex("[A-Za-z0-9._-]{2,50}"))){"ALERT_CONTRACT_SCHEMA_INVALID"}
        ensureAlert(input.ownerTeam.isNotBlank()&&input.ownerTeam.length<=100&&input.sourceSystem.isNotBlank()&&input.sourceSystem.length<=100){"ALERT_CONTRACT_OWNER_SOURCE_REQUIRED"};ensureAlert(input.freshnessSeconds in 1..2_592_000&&input.completenessThreshold in .9..1.0){"ALERT_CONTRACT_QUALITY_GATE_INVALID"}
        repository.saveContract(input.contractCode,input.schemaVersion,input.ownerTeam.trim(),input.sourceSystem.trim(),input.freshnessSeconds,input.completenessThreshold)
        return mapOf("contractCode" to input.contractCode,"status" to "DRAFT")
    }
    @Transactional fun validateContract(code:String,input:GovernanceValidationInput):Map<String,Any>{val actor=actor();validateEvidence(input);ensureAlert(repository.validateContract(code,input.evidenceSha256.lowercase(),input.summary.trim(),actor,input.expiresAt)){"ALERT_CONTRACT_NOT_VALIDATABLE"};return mapOf("contractCode" to code,"status" to "VALIDATED")}
    @Transactional fun approveContract(code:String):Map<String,Any>{val actor=actor();ensureAlert(repository.approveContract(code,actor)){"ALERT_CONTRACT_INDEPENDENT_APPROVAL_REQUIRED"};return mapOf("contractCode" to code,"status" to "APPROVED")}
    @Transactional fun suspendContract(code:String,reason:String):Map<String,Any>{ensureAlert(reason.trim().length in 3..200);ensureAlert(repository.suspendContract(code)){"ALERT_CONTRACT_NOT_SUSPENDABLE"};audit("ALERT_CONTRACT_SUSPEND",code,mapOf("reason" to reason.trim()));return mapOf("contractCode" to code,"status" to "SUSPENDED")}

    fun providers()=repository.providers()

    @Transactional fun validateProvider(input:ProviderAcceptanceInput):Map<String,Any>{
        val actor=actor();val channel=input.channel.uppercase();ensureAlert(channel in NotificationPolicy.CHANNELS){"PROVIDER_CHANNEL_INVALID"};ensureAlert(channel!="IN_APP"){"IN_APP_PROVIDER_IS_BUILT_IN"}
        ensureAlert(input.providerCode.matches(Regex("[A-Za-z0-9._-]{2,100}"))&&input.environment in setOf("TEST","STAGING","PRODUCTION")){"PROVIDER_IDENTITY_INVALID"};ensureAlert(input.dailyQuota>0&&input.idempotencySupported){"PROVIDER_QUOTA_IDEMPOTENCY_REQUIRED"};ensureAlert(input.evidenceSha256.matches(Regex("^[a-fA-F0-9]{64}$"))&&input.evidenceReference.trim().length in 6..500&&input.expiresAt>LocalDateTime.now()){"PROVIDER_EVIDENCE_INVALID"};requireCapabilities(channel,input.capabilities)
        repository.validateProvider(channel,input.providerCode,input.environment,mapper.writeValueAsString(input.capabilities),input.dailyQuota,input.evidenceSha256.lowercase(),input.evidenceReference.trim(),actor,input.expiresAt)
        return mapOf("channel" to channel,"providerCode" to input.providerCode,"status" to "VALIDATED")
    }
    @Transactional fun approveProvider(id:Long):Map<String,Any>{val actor=actor();ensureAlert(repository.approveProvider(id,actor)){"PROVIDER_INDEPENDENT_PRODUCTION_APPROVAL_REQUIRED"};return mapOf("id" to id,"status" to "APPROVED")}
    @Transactional fun suspendProvider(id:Long,reason:String):Map<String,Any>{ensureAlert(reason.trim().length in 3..200);ensureAlert(repository.suspendProvider(id)){"PROVIDER_NOT_SUSPENDABLE"};audit("NOTIFICATION_PROVIDER_SUSPEND",id.toString(),mapOf("reason" to reason.trim()));return mapOf("id" to id,"status" to "SUSPENDED")}

    @Transactional fun markRuleImplemented(code:String,input:RuleImplementationInput):Map<String,Any>{
        ensureAlert(input.replayEvidenceSha256.matches(Regex("^[a-fA-F0-9]{64}$"))){"ALERT_RULE_REPLAY_EVIDENCE_REQUIRED"};val actor=actor();val missing=repository.requirements(code).count{!it.ready};ensureAlert(missing==0){"ALERT_RULE_DATA_CONTRACTS_NOT_READY:$missing"};ensureAlert(repository.implement(code,input.replayEvidenceSha256.lowercase(),actor)){"ALERT_RULE_NOT_IMPLEMENTABLE"};return mapOf("ruleCode" to code,"implementationStatus" to "IMPLEMENTED")
    }

    fun activationGaps(ruleId:Long):Map<String,Any?>{
        val row=alertStateRequired(repository.rule(ruleId));val code=row.ruleCode;val catalog=repository.catalog(code);val contracts=repository.requirements(code)
        val channels=mapper.readTree(row.routing).path("channels").map{it.asText().uppercase()}.toSet();val missingProviders=repository.missingProviders(channels);val missingContracts=contracts.filter{!it.ready}.map{it.contractCode};val acceptance=repository.latestAcceptance(ruleId);val acceptanceReady=acceptance?.status=="APPROVED"&&acceptance.routingOutcome=="PASSED"
        val ownerTeam=row.ownerTeam?.trim();val requiresOncall=row.severity in setOf("P0","P1","P2");val eligibleOncallCount=if(!requiresOncall||ownerTeam.isNullOrBlank())0 else repository.eligibleOncall(ownerTeam);val missingEligibleOncall=requiresOncall&&eligibleOncallCount==0
        val governanceReady=catalog?.governanceStatus=="APPROVED";val implementationReady=catalog?.implementationStatus=="IMPLEMENTED"&&catalog.implementationEvidence!=null;val baseReady=governanceReady&&implementationReady&&missingContracts.isEmpty()&&missingProviders.isEmpty()
        return mapOf("ruleCode" to code,"versionStatus" to row.versionStatus,"ownerTeam" to ownerTeam,"governanceReady" to governanceReady,"implementationReady" to implementationReady,"acceptanceReady" to acceptanceReady,"latestAcceptance" to acceptance?.let(::acceptanceMap),"contracts" to contracts.map{mapOf("contractCode" to it.contractCode,"status" to it.status,"expiresAt" to it.expiresAt,"ready" to if(it.ready)1 else 0)},"missingContracts" to missingContracts,"missingProviders" to missingProviders,"eligibleOncallCount" to eligibleOncallCount,"missingEligibleOncall" to missingEligibleOncall,"oncallConfigurationPath" to "/admin/alerts?section=oncall","ready" to baseReady,"readyToPublish" to (baseReady&&acceptanceReady&&!missingEligibleOncall))
    }

    @Transactional fun publishShadow(ruleId:Long,input:AlertRulePublishInput):Map<String,Any>{
        val reason=input.reason.trim();ensureAlert(reason.length in 3..500){"ALERT_RULE_PUBLISH_REASON_REQUIRED"};actor();val locked=alertStateRequired(repository.lockRule(ruleId));ensureAlert(locked.versionStatus=="SHADOW"){"ALERT_RULE_NOT_SHADOW"};val gaps=activationGaps(ruleId);ensureAlert(gaps["readyToPublish"]==true){"ALERT_RULE_NOT_PUBLISHABLE:${mapper.writeValueAsString(gaps)}"};repository.retireActive(locked.ruleCode);ensureAlert(repository.activate(ruleId,input.enable)){"ALERT_RULE_PUBLISH_CONFLICT"};audit("ALERT_RULE_PUBLISH",ruleId.toString(),mapOf("ruleCode" to locked.ruleCode,"reason" to reason,"enabled" to input.enable));return mapOf("id" to ruleId,"ruleCode" to locked.ruleCode,"versionStatus" to "ACTIVE","enabled" to input.enable)
    }
    @Transactional fun enforceRuntimeReady(ruleId:Long):Boolean{val gaps=activationGaps(ruleId);if(gaps["ready"]==true)return true;repository.disable(ruleId);repository.autoDisabled(ruleId,mapper.writeValueAsString(gaps));return false}

    private fun acceptanceMap(row:GovernanceAcceptancePo)=mapOf("id" to row.id,"status" to row.status,"precisionRate" to row.precisionRate,"recallRate" to row.recallRate,"routingOutcome" to row.routingOutcome,"assessedBy" to row.assessedBy,"approvedBy" to row.approvedBy,"assessedAt" to row.assessedAt,"approvedAt" to row.approvedAt)
    private fun requireCapabilities(channel:String,c:Map<String,Boolean>){val common=setOf("accepted","failureReceipt","rateLimitTested","credentialRotationTested");val specific=when(channel){"VOICE_CALL"->setOf("connectedReceipt","noAnswerReceipt","dtmf1Ack");"EMAIL"->setOf("deliveryReceipt","bounceReceipt");else->setOf("providerReceipt")};ensureAlert((common+specific).all{c[it]==true}){"PROVIDER_CAPABILITY_EVIDENCE_INCOMPLETE"}}
    private fun validateEvidence(input:GovernanceValidationInput){ensureAlert(input.evidenceSha256.matches(Regex("^[a-fA-F0-9]{64}$"))&&input.summary.trim().length in 6..500&&input.expiresAt>LocalDateTime.now()){"GOVERNANCE_EVIDENCE_INVALID"}}
    private fun actor()=(SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId?:alertStateAbort("AUTHENTICATION_REQUIRED")
    private fun audit(operation:String,id:String,detail:Map<String,Any?>){repository.audit(actor(),operation,id,mapper.writeValueAsString(detail))}
}

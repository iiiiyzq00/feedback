package com.feedback.service.masking

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.masking.MaskingAudit
import com.feedback.repository.MaskingAuditRepository
import com.feedback.repository.masking.MaskingConfigRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class MaskingRuleView(val id:Int,val name:String,val type:String,val pattern:String,val replacement:String,val priority:Int,val enabled:Boolean,val critical:Boolean)
data class MaskingRuleUpdate(val enabled:Boolean)
data class MaskingRuleUpdateResponse(val id:Int,val requestedEnabled:Boolean,val runtimeEnabled:Boolean,val candidateVersion:String,val status:String)

@Service
class MaskingRuleRegistry(
    private val configs:MaskingConfigRepository,
    private val mapper:ObjectMapper,
    private val audits:MaskingAuditRepository,
    private val versions:MaskingVersionService
) {
    @Value("\${app.masking.development-candidate-version:}") private var developmentCandidateVersion:String=""
    @org.springframework.beans.factory.annotation.Autowired(required=false) private var environment:Environment?=null
    private val catalog=listOf(
        MaskingRuleView(1,"CREDENTIAL","REGEX","凭证键值模式","[CREDENTIAL_REMOVED]",1,true,true),
        MaskingRuleView(2,"ID_CARD","REGEX","中国居民身份证","[ID_CARD_REMOVED]",2,true,true),
        MaskingRuleView(10,"PASSPORT","REGEX","护照及通行证等证件号","[DOCUMENT_REMOVED]",3,true,true),
        MaskingRuleView(11,"DEVICE_ID","REGEX","带类型标识的设备标识符","[DEVICE_ID_REMOVED]",4,true,true),
        MaskingRuleView(12,"IMEI_IMSI","REGEX","15 位 IMEI / IMSI","[DEVICE_ID_REMOVED]",5,true,true),
        MaskingRuleView(13,"STUDENT_EMPLOYEE_ID","REGEX","学号、工号及内部账号","[ACCOUNT_REMOVED]",6,true,true),
        MaskingRuleView(14,"COORDINATE","REGEX","经纬度坐标","[LOCATION_REMOVED]",7,true,true),
        MaskingRuleView(3,"BANK_CARD","REGEX","16-23 位银行卡格式","[BANK_CARD_REMOVED]",8,true,true),
        MaskingRuleView(4,"PHONE","REGEX","中国大陆手机号","[PHONE]",9,true,true),
        MaskingRuleView(5,"LANDLINE","REGEX","固定电话","[LANDLINE_REMOVED]",10,true,true),
        MaskingRuleView(6,"EMAIL","REGEX","电子邮箱","[EMAIL]",11,true,true),
        MaskingRuleView(7,"MAC","REGEX","MAC 地址","[MAC_REMOVED]",12,true,false),
        MaskingRuleView(8,"IP","REGEX","IPv4 地址","部分掩码",13,true,false),
        MaskingRuleView(9,"ACCOUNT","REGEX","账号上下文","[ACCOUNT_REMOVED]",14,true,true))

    fun enabledNames():Set<String> {
        val all=catalog.map{it.name}.toSet()
        val raw=developmentCandidate()?.configValue ?: configs.active("masking.rules-enabled")?.configValue ?: return all
        val node=mapper.readTree(raw)
        val configured=runCatching{(if(node.isObject)node.path("enabledRules") else node).map{it.asText()}.toSet()}.getOrDefault(all)
        return configured+MaskingVersionService.CRITICAL
    }
    fun list()=enabledNames().let{enabled->catalog.map{it.copy(enabled=it.name in enabled)}}

    @Transactional
    fun update(id:Int,input:MaskingRuleUpdate,actor:Long=0):MaskingRuleUpdateResponse {
        val rule=catalog.find{it.id==id}?:maskingNotFound("MASKING_RULE_NOT_FOUND")
        if(!input.enabled&&rule.critical) maskingInvalid("MASKING_CRITICAL_RULE_DISABLE_FORBIDDEN")
        val enabled=enabledNames().toMutableSet().apply{if(input.enabled)add(rule.name)else remove(rule.name)}
        val allowlist=configs.active("masking.ner-allowlist-version")?.configValue?.trim('"')?:"allowlist-v1"
        val candidate=versions.candidate(MaskingCandidateInput(enabled,allowlist,"切换规则 ${rule.name}=${input.enabled}"),actor)
        audits.save(MaskingAudit(auditType="RULE_CHANGE",maskingRuleVersion=candidate.version,reviewConclusion="PENDING_REVIEW",
            evaluationKey="toggle:${rule.name}",evalErrorDetails=mapper.writeValueAsString(mapOf("rule" to rule.name,"enabled" to input.enabled,"runtime_unchanged" to true))))
        return MaskingRuleUpdateResponse(id,input.enabled,rule.enabled,candidate.version,"DRAFT")
    }

    fun activeVersion()=developmentCandidate()?.configVersion
        ?:configs.active("masking.rule-version")?.configValue?.trim('"')?:MaskingPipeline.RULE_VERSION

    private fun developmentCandidate():com.feedback.model.po.masking.MaskingConfigPo? {
        val version=developmentCandidateVersion.trim();if(version.isBlank())return null
        if(environment?.activeProfiles?.none{it.equals("prod",true)||it.equals("production",true)}==false) maskingFailed("MASKING_DEVELOPMENT_CANDIDATE_FORBIDDEN_IN_PRODUCTION")
        return configs.draftRelease(version)?:maskingFailed("MASKING_DEVELOPMENT_CANDIDATE_NOT_FOUND")
    }
}

package com.feedback.service.masking

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import com.feedback.repository.masking.OutboundPrivacyRepository
import java.security.MessageDigest

class OutboundPrivacyException(val target:String):IllegalStateException("OUTBOUND_PRIVACY_GATE_REJECTED:$target")

@Service
class OutboundPrivacyGate(
    private val masking:MaskingPipeline,
    private val repository: OutboundPrivacyRepository,
    private val mapper:ObjectMapper
) {
    fun activeVersion():String=masking.activeVersion()
    fun requireSafe(target:String,sourceId:Long?=null,vararg texts:String?) {
        val results=texts.filterNotNull().filter(String::isNotBlank).map { it to masking.mask(it,"outbound_${target.lowercase()}") }
        // 这道闸门校验的是已经物化的出站载荷，它不返回脱敏后的副本。
        // 因此规则后置命中时必须拒绝这次发送（否则调用方会把原文
        // 发出去），但绝不能因此产生一个人工复核状态。
        val unsafe=results.filter { (_,result)->
            result.maskingStatus!="PASS" || result.findingTypes.isNotEmpty()
        }
        if(unsafe.isEmpty())return
        if (sourceId != null && hasBoundFalsePositiveOverride(sourceId, texts.filterNotNull().filter(String::isNotBlank))) return
        repository.reject(target.take(50), sourceId?.toString()?:"AGGREGATE", masking.activeVersion(),
            mapper.writeValueAsString(unsafe.flatMap{it.second.findingTypes}.distinct()), unsafe.size)
        throw OutboundPrivacyException(target)
    }

    private fun hasBoundFalsePositiveOverride(sourceId:Long,texts:List<String>):Boolean {
        val hash=MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(texts))
        val version = masking.activeVersion()
        if(!repository.hasBoundOverride(sourceId, version, hash))return false
        repository.override(sourceId, version)
        return true
    }

    fun requireJsonSafe(target:String,json:String) {
        val freeTextNames=setOf("title","description","summary","contentSample","clusterName","canonicalSymptom","generatedSummary","question")
        val values=mutableListOf<String>()
        fun visit(node:com.fasterxml.jackson.databind.JsonNode,name:String?=null){
            when { node.isObject->node.fields().forEachRemaining{(k,v)->visit(v,k)};node.isArray->node.forEach{visit(it,name)}
                node.isTextual && name in freeTextNames->values+=node.asText() }
        }
        visit(mapper.readTree(json));requireSafe(target,null,*values.toTypedArray())
    }
}

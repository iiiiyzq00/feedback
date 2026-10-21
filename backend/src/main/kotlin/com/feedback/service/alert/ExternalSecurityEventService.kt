package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.repository.alert.ExternalSecurityEventRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ExternalSecurityEventInput(val providerCode:String,val externalEventId:String,val ruleCode:String,val occurredAt:Instant,
    val confirmed:Boolean,val scopeHash:String,val evidenceCodes:List<String>)

@Service
class ExternalSecurityEventService(private val repository:ExternalSecurityEventRepository,private val mapper:ObjectMapper,
    @Value("\${app.alert.external-security-secrets:{}}") secretsJson:String) {
    private val secrets=runCatching{mapper.readValue(secretsJson,Map::class.java).entries.associate{it.key.toString() to it.value.toString()}}.getOrDefault(emptyMap())
    @Transactional fun accept(raw:String,timestamp:String,signature:String):Map<String,Any>{val input=runCatching{mapper.readValue(raw,ExternalSecurityEventInput::class.java)}.getOrElse{alertAbort("EXTERNAL_SECURITY_EVENT_INVALID_JSON")};validate(input);val epoch=timestamp.toLongOrNull()?:alertAbort("EXTERNAL_SECURITY_TIMESTAMP_INVALID");ensureAlert(kotlin.math.abs(Instant.now().epochSecond-epoch)<=300){"EXTERNAL_SECURITY_TIMESTAMP_EXPIRED"};val secret=secrets[input.providerCode]?:alertAbort("EXTERNAL_SECURITY_PROVIDER_NOT_CONFIGURED");ensureAlert(secret.length>=32){"EXTERNAL_SECURITY_SECRET_TOO_SHORT"};val expected=hmac(secret,"$timestamp\n$raw");ensureAlert(signature.matches(Regex("^[a-fA-F0-9]{64}$"))&&MessageDigest.isEqual(expected.hex(),signature.lowercase().hex())){"EXTERNAL_SECURITY_SIGNATURE_INVALID"}
        val key="${input.providerCode}:${input.externalEventId}";val existing=repository.receipt(key);if(existing!=null)return mapOf("accepted" to true,"duplicate" to true,"alertInstanceId" to existing.alertInstanceId,"incidentId" to existing.incidentId)
        val occurred=LocalDateTime.ofInstant(input.occurredAt,ZoneOffset.UTC);val alertId=repository.createAlert(input.ruleCode,occurred)
        val evidenceMap=mapOf("providerCode" to input.providerCode,"externalEventId" to input.externalEventId,"scopeHash" to input.scopeHash.lowercase(),"evidenceCodes" to input.evidenceCodes.distinct(),"confirmed" to true,"rawContentStored" to false);val evidence=mapper.writeValueAsString(evidenceMap);repository.occurrence(alertId,evidence,occurred)
        val title=if(input.ruleCode=="UNAUTHORIZED_ACCESS_DETECTED")"越权访问确认" else "密钥凭据暴露";val incidentId=repository.createIncident(title);repository.link(incidentId,alertId)
        val detail=mapper.writeValueAsString(mapOf("providerCode" to input.providerCode,"externalEventId" to input.externalEventId,"ruleCode" to input.ruleCode,"scopeHash" to input.scopeHash.lowercase(),"payloadSha256" to sha(raw),"evidence" to evidenceMap,"alertInstanceId" to alertId,"incidentId" to incidentId));repository.audit(alertId.toString(),detail,occurred,key);return mapOf("accepted" to true,"duplicate" to false,"severity" to "P0","alertInstanceId" to alertId,"incidentId" to incidentId,"manualClosureRequired" to true)}
    private fun validate(i:ExternalSecurityEventInput){ensureAlert(i.providerCode.matches(Regex("[A-Za-z0-9._-]{2,100}"))&&i.externalEventId.matches(Regex("[A-Za-z0-9._:-]{8,100}")));ensureAlert(i.ruleCode in setOf("UNAUTHORIZED_ACCESS_DETECTED","KEY_MATERIAL_EXPOSURE")&&i.confirmed);ensureAlert(i.scopeHash.matches(Regex("^[a-fA-F0-9]{64}$")));ensureAlert(i.evidenceCodes.isNotEmpty()&&i.evidenceCodes.size<=20&&i.evidenceCodes.all{it.matches(Regex("[A-Z0-9_:-]{2,50}"))});ensureAlert(!i.occurredAt.isAfter(Instant.now().plusSeconds(60))&&i.occurredAt.isAfter(Instant.now().minusSeconds(86400*31)))}
    private fun hmac(secret:String,value:String)=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(secret.toByteArray(),"HmacSHA256"));doFinal(value.toByteArray()).joinToString(""){"%02x".format(it)}}
    private fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun String.hex()=chunked(2).map{it.toInt(16).toByte()}.toByteArray()
}

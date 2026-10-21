package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.ProviderReceiptAuditPo
import com.feedback.repository.alert.NotificationProviderReceiptRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ProviderReceiptInput(val callbackId:String,val providerCode:String,val channel:String,val providerMessageId:String,
    val eventType:String,val eventTime:Instant,val dtmfDigit:String?=null)

@Service
class NotificationProviderReceiptService(
    private val repository:NotificationProviderReceiptRepository,private val mapper:ObjectMapper,
    @Value("\${app.alert.provider-receipt-secrets:{}}") secretsJson:String,
    private val escalation:NotificationEscalationService?=null
){
    private val secrets:Map<String,String> = runCatching { mapper.readValue(secretsJson,Map::class.java).entries.associate{it.key.toString() to it.value.toString()} }.getOrDefault(emptyMap())

    @Transactional fun accept(rawBody:String,timestamp:String,signature:String):Map<String,Any>{
        val input=runCatching{mapper.readValue(rawBody,ProviderReceiptInput::class.java)}.getOrElse{alertAbort("PROVIDER_RECEIPT_INVALID_JSON")}
        ensureAlert(input.callbackId.matches(Regex("[A-Za-z0-9._:-]{8,100}"))&&input.providerCode.matches(Regex("[A-Za-z0-9._-]{2,100}"))){"PROVIDER_RECEIPT_ID_INVALID"}
        val epoch=timestamp.toLongOrNull()?:alertAbort("PROVIDER_RECEIPT_TIMESTAMP_INVALID");ensureAlert(kotlin.math.abs(Instant.now().epochSecond-epoch)<=300){"PROVIDER_RECEIPT_TIMESTAMP_EXPIRED"}
        val secret=secrets[input.providerCode]?:alertAbort("PROVIDER_RECEIPT_PROVIDER_NOT_CONFIGURED");ensureAlert(secret.length>=32){"PROVIDER_RECEIPT_SECRET_TOO_SHORT"}
        val expected=hmac(secret,"$timestamp\n$rawBody");ensureAlert(signature.matches(Regex("^[a-fA-F0-9]{64}$"))&&MessageDigest.isEqual(expected.hexToBytes(),signature.lowercase().hexToBytes())){"PROVIDER_RECEIPT_SIGNATURE_INVALID"}
        val channel=input.channel.uppercase();val event=input.eventType.uppercase();ensureAlert(channel in (NotificationPolicy.CHANNELS-"IN_APP")&&event in setOf("ACCEPTED","DELIVERED","FAILED","BOUNCED","CONNECTED","NO_ANSWER","DTMF_ACK")){"PROVIDER_RECEIPT_EVENT_INVALID"}
        ensureAlert((event=="DTMF_ACK"&&input.dtmfDigit=="1")||(event!="DTMF_ACK"&&input.dtmfDigit==null)){"PROVIDER_RECEIPT_DTMF_INVALID"}
        val notification=repository.notificationId(input.providerMessageId,channel)
        val inserted=repository.record(input.callbackId,input.providerCode,channel,input.providerMessageId,notification,event,input.dtmfDigit,
            LocalDateTime.ofInstant(input.eventTime,ZoneOffset.UTC),sha256(rawBody))
        // 本事务期间按 notificationId 锁住通知行。更早的
        // 提供方事件仍可审计，但不能覆盖更新的状态，
        // 也不能推动升级链路。
        val applicableNotification=notification?.takeIf{inserted&&repository.isLatest(it,input.providerCode,input.callbackId)}
        // CONNECTED 只能证明通话被接起。升级链路只在
        // DTMF 1 或应用内已认证的确认时才停止。
        val deliveryApplied=applicableNotification!=null&&event in setOf("DELIVERED","DTMF_ACK")&&repository.markDelivered(applicableNotification)
        val connectionApplied=applicableNotification!=null&&event=="CONNECTED"&&repository.markConnected(applicableNotification)
        val failureApplied=applicableNotification!=null&&event in setOf("FAILED","BOUNCED","NO_ANSWER")&&repository.markFailed(applicableNotification,event)
        val incidentAcknowledged=if(applicableNotification!=null&&event=="DTMF_ACK")escalation?.acknowledgeByNotification(applicableNotification,"DTMF_ACK")==true else false
        if(failureApplied)escalation?.notificationFailed(applicableNotification,"供应商回执：$event")
        return mapOf("accepted" to true,"duplicate" to !inserted,"notificationMatched" to (notification!=null),"notificationStateApplied" to (deliveryApplied||connectionApplied||failureApplied),"incidentAcknowledged" to incidentAcknowledged)
    }
    fun receipts(provider:String?,channel:String?,limit:Int):List<ProviderReceiptAuditPo>{ensureAlert(limit in 1..200);return repository.receipts(provider?.takeIf{it.isNotBlank()},channel?.takeIf{it.isNotBlank()}?.uppercase(),limit)}
    private fun hmac(secret:String,value:String):String=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(secret.toByteArray(),"HmacSHA256"));doFinal(value.toByteArray()).joinToString(""){"%02x".format(it)}}
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun String.hexToBytes()=chunked(2).map{it.toInt(16).toByte()}.toByteArray()
}

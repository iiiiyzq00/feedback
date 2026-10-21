package com.feedback.service.masking

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.masking.IngestAlertInstancePo
import com.feedback.model.po.masking.IngestAlertOccurrencePo
import com.feedback.model.po.masking.IngestIncidentPo
import com.feedback.model.po.sdk.SdkProcessingEnvelopePo
import com.feedback.repository.masking.IngestFailureAlertRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.util.UUID

/**
 * SDK 安全处理重试耗尽后的失败事实发布器。
 * 反馈不启用站内通知中心，但必须在同一事务内保留告警、发生快照、Incident 和关联事实。
 */
@Service
class IngestFailureAlertPublisher(
    private val repository: IngestFailureAlertRepository,
    private val mapper: ObjectMapper
) {
    @Transactional
    fun publish(ingest:SdkProcessingEnvelopePo,errorCode:String) {
        val rule=repository.activeRule()?:return
        val severity=if(ingest.urgencyClass=="URGENT")"P1" else "P2"
        val instance=repository.insertInstance(IngestAlertInstancePo(
            ruleId=rule.id,feedbackSourceId=ingest.feedbackSourceId,severity=severity))
        val snapshot=mapper.writeValueAsString(IngestFailureSnapshot(
            receiptId=ingest.receiptId.joinToString(""){"%02x".format(it)},urgency=ingest.urgencyClass,
            retryCount=ingest.retryCount,errorCode=errorCode,containsSensitivePayload=false))
        repository.insertOccurrence(IngestAlertOccurrencePo(instanceId=instance.id,snapshotJson=snapshot))
        val incident=repository.insertIncident(IngestIncidentPo(
            publicId=uuidBytes(UUID.randomUUID()),title="接入信封处理失败（$errorCode）",severity=severity))
        repository.linkIncident(incident.id,instance.id)
    }

    private fun uuidBytes(value:UUID)=ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()
}

private data class IngestFailureSnapshot(
    val receiptId:String,val urgency:String,val retryCount:Int,val errorCode:String,val containsSensitivePayload:Boolean
)

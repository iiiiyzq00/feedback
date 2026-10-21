package com.feedback.service.imports

import com.feedback.service.alert.AlertNotificationEvent
import com.feedback.service.alert.NotificationDispatcher
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.repository.masking.MaskingConfigRepository
import com.feedback.repository.imports.UnifiedQualityRepository
import com.feedback.model.po.imports.UnifiedQualityThresholdOccurrencePo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.time.LocalDateTime

data class DataQualityThresholds(val requiredCompletenessMin:Double=.95,val primaryKeyDuplicateMax:Double=.05,
    val timeValidMin:Double=.98,val enumValidMin:Double=.95,val mappingMin:Double=.90,val maskingPassMin:Double=.99,
    val importSuccessMin:Double=.90,val crossChannelDuplicateMax:Double=.30,val quarantineMax:Long=0)

@Service
class DataQualityThresholdPolicy(private val configs:MaskingConfigRepository,private val mapper:ObjectMapper){
    fun current():DataQualityThresholds=runCatching{
        val json=configs.active("data-quality.thresholds")?.configValue
        if(json==null)DataQualityThresholds() else mapper.readValue(json,DataQualityThresholds::class.java)
    }.getOrDefault(DataQualityThresholds())
}

@Service
class UnifiedDataQualityService(
    private val repository:UnifiedQualityRepository,
    private val mapper:ObjectMapper,
    private val alerts:DataQualityThresholdAlertPublisher?=null,
    private val thresholdPolicy:DataQualityThresholdPolicy?=null
) {
    fun scheduledRefresh(){refreshChannels()}

    @Transactional
    fun refreshChannels():List<Map<String,Any?>> = CHANNELS.map{channel->
        val row=repository.formalFacts(channel,LocalDateTime.now())
        val importFacts=repository.importFacts(channel)
        fun n(key:String)=when(key){"total"->row.total;"complete"->row.complete;"timeValid"->row.timeValid;
          "enumValid"->row.enumValid;"appMapped"->row.appMapped;"moduleMapped"->row.moduleMapped;
          "versionMapped"->row.versionMapped;"maskingPassed"->row.maskingPassed;"anomalies"->row.anomalies;
          "quarantined"->row.quarantined;"accessFailures"->row.accessFailures;"crossDuplicates"->row.crossDuplicates;
          else->importAbort("UNIFIED_QUALITY_FACT_UNKNOWN:$key")}.toDouble()
        val formalTotal=n("total")
        val importQuarantined=importFacts.quarantinedRows.toDouble()
        val rawTotal=formalTotal+importQuarantined
        if(rawTotal==0.0){
          val noData=linkedMapOf<String,Any?>("channel" to channel,"totalCount" to 0L,
            "requiredCompletenessRate" to null,"primaryKeyDuplicateRate" to null,"timeValidRate" to null,
            "enumValidRate" to null,"appMappingRate" to null,"moduleMappingRate" to null,"versionMappingRate" to null,
            "maskingPassRate" to null,"crossChannelSuspectedDuplicateRate" to null,"anomalyCount" to 0L,
            "quarantineCount" to 0L,"accessFailureCount" to 0L,"qualityScore" to null,
            "thresholdStatus" to "NO_DATA","metricVersion" to VERSION)
          repository.insertSnapshot(channel,0,null,VERSION,mapper.writeValueAsString(noData))
          return@map noData
        }
        fun formalRate(key:String)=if(formalTotal==0.0)0.0 else n(key)/formalTotal
        val required=formalRate("complete");val time=formalRate("timeValid");val enums=formalRate("enumValid");val app=formalRate("appMapped")
        val module=formalRate("moduleMapped");val version=formalRate("versionMapped")
        val masking=n("maskingPassed")/rawTotal;val cross=formalRate("crossDuplicates")
        val anomalyCount=n("anomalies")+importQuarantined
        val quarantineCount=n("quarantined")+importQuarantined
        val accessFailureCount=n("accessFailures")+importFacts.accessFailureRows.toDouble()
        val duplicateRows=importFacts.duplicateRows.toDouble()
        val rowsSeen=importFacts.rowsSeen.toDouble()
        val primaryKeyDuplicate=if(rowsSeen==0.0)0.0 else duplicateRows/rowsSeen
        val score=d(required*.25+time*.10+enums*.10+((app+module+version)/3)*.20+masking*.30+(1-cross)*.05)
        val limits=thresholdPolicy?.current()?:DataQualityThresholds()
        val threshold=rawTotal>0&&(required<limits.requiredCompletenessMin||time<limits.timeValidMin||enums<limits.enumValidMin||
          minOf(app,module,version)<limits.mappingMin||masking<limits.maskingPassMin||primaryKeyDuplicate>limits.primaryKeyDuplicateMax||
          cross>limits.crossChannelDuplicateMax||quarantineCount>limits.quarantineMax)
        val detail=mapOf("channel" to channel,"totalCount" to rawTotal.toLong(),"requiredCompletenessRate" to d(required),
          "primaryKeyDuplicateRate" to d(primaryKeyDuplicate),"timeValidRate" to d(time),"enumValidRate" to d(enums),
          "appMappingRate" to d(app),"moduleMappingRate" to d(module),"versionMappingRate" to d(version),
          "maskingPassRate" to d(masking),"crossChannelSuspectedDuplicateRate" to d(cross),
          "anomalyCount" to anomalyCount.toLong(),"quarantineCount" to quarantineCount.toLong(),
          "accessFailureCount" to accessFailureCount.toLong(),
          "qualityScore" to score,"thresholdStatus" to if(threshold)"ALERT" else "PASS","metricVersion" to VERSION)
        val snapshotId=repository.insertSnapshot(channel,rawTotal.toLong(),score,VERSION,mapper.writeValueAsString(detail))
        if(threshold&&rawTotal>0)alerts?.publish(channel,null,snapshotId,score.toDouble())
        mapOf("channel" to channel,"totalCount" to rawTotal.toLong(),"requiredCompletenessRate" to d(required),
          "primaryKeyDuplicateRate" to d(primaryKeyDuplicate),"timeValidRate" to d(time),"enumValidRate" to d(enums),"appMappingRate" to d(app),
          "moduleMappingRate" to d(module),"versionMappingRate" to d(version),"maskingPassRate" to d(masking),
          "crossChannelSuspectedDuplicateRate" to d(cross),"anomalyCount" to anomalyCount.toLong(),
          "quarantineCount" to quarantineCount.toLong(),"accessFailureCount" to accessFailureCount.toLong(),
          "qualityScore" to score,"thresholdStatus" to if(threshold)"ALERT" else "PASS")
    }

    fun latest():List<Map<String,Any?>> = repository.latestSnapshots().map{snapshot->
      val node=mapper.readTree(snapshot.evaluationSnapshot)
      fun decimal(name:String)=node[name]?.takeUnless{it.isNull}?.decimalValue()
      mapOf("channel" to snapshot.dimensionKey,"totalCount" to (node["totalCount"]?.asLong()?:snapshot.sampleCount),
        "requiredCompletenessRate" to decimal("requiredCompletenessRate"),"primaryKeyDuplicateRate" to decimal("primaryKeyDuplicateRate"),
        "timeValidRate" to decimal("timeValidRate"),"enumValidRate" to decimal("enumValidRate"),
        "appMappingRate" to decimal("appMappingRate"),"moduleMappingRate" to decimal("moduleMappingRate"),
        "versionMappingRate" to decimal("versionMappingRate"),"maskingPassRate" to decimal("maskingPassRate"),
        "crossChannelSuspectedDuplicateRate" to decimal("crossChannelSuspectedDuplicateRate"),
        "anomalyCount" to (node["anomalyCount"]?.asLong()?:0L),"quarantineCount" to (node["quarantineCount"]?.asLong()?:0L),
        "accessFailureCount" to (node["accessFailureCount"]?.asLong()?:0L),"qualityScore" to decimal("qualityScore"),
        "thresholdStatus" to node["thresholdStatus"]?.asText(),"metricVersion" to snapshot.dataContractVersion,
        "windowStart" to snapshot.windowStart,"windowEnd" to snapshot.windowEnd)
    }

    private fun d(v:Double)=BigDecimal.valueOf(v.coerceIn(0.0,1.0)).setScale(6,RoundingMode.HALF_UP)
    companion object{val CHANNELS=listOf("SDK","STATION_FEEDBACK","JIRA","QUALITY","REDBLACK");const val VERSION="data-quality-v3"}
}

@Service
class DataQualityThresholdAlertPublisher(private val repository:UnifiedQualityRepository,private val mapper:ObjectMapper,
    private val notifications:NotificationDispatcher){
    @Transactional fun publish(channel:String,batchId:Long?,snapshotId:Long,score:Double){
        val dimension=if(batchId==null)"CHANNEL:$channel" else "BATCH:$channel:$batchId"
        if(repository.recentThreshold(dimension,LocalDateTime.now().minusMinutes(30)))return
        val rule=repository.activeRule()?:return
        val ruleId=rule.id
        val instanceId=repository.insertAlertInstance(ruleId)
        val snapshot=mapper.writeValueAsString(mapOf("channel" to channel,"batchId" to batchId,"qualitySnapshotId" to snapshotId,"qualityScore" to score))
        repository.insertThreshold(UnifiedQualityThresholdOccurrencePo(instanceId=instanceId,dimensionKey=dimension,
          sourceTable=if(batchId==null)"t_alert_occurrence" else "t_import_batch",snapshotId=snapshotId,snapshotJson=snapshot))
        val routing=mapper.readTree(rule.routingConfig);val channels=routing["channels"]?.map{it.asText()}?:listOf("IN_APP")
        notifications.dispatch(AlertNotificationEvent(eventId=UUID.randomUUID().toString(),eventType="ALERT_FIRED",
          title=rule.ruleName,summary="$dimension 数据质量超过阈值",severity="P2",status="FIRING",
          ruleCode="DATA_QUALITY_THRESHOLD_BREACH",ruleName=rule.ruleName,metric="DATA_QUALITY_SCORE",
          currentValue=score*100,threshold=85.0,window="LATEST",recoveryThreshold=90.0,instanceId=instanceId,
          firedAt=OffsetDateTime.now(ZoneId.systemDefault()).toString(),detailUrl="/monitor/ingestion"),ruleId,channels)
    }
}

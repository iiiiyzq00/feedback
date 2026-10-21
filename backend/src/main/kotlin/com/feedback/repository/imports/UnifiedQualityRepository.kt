package com.feedback.repository.imports

import com.feedback.mapper.imports.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Base64

data class UnifiedQualityFormalFacts(
    val total:Long,val complete:Long,val timeValid:Long,val enumValid:Long,val appMapped:Long,
    val moduleMapped:Long,val versionMapped:Long,val maskingPassed:Long,val anomalies:Long,
    val quarantined:Long,val accessFailures:Long,val crossDuplicates:Long
)
data class UnifiedQualityImportFacts(val rowsSeen:Long,val duplicateRows:Long,val quarantinedRows:Long,val accessFailureRows:Long)

@Repository
class UnifiedQualityRepository(
    private val sources:UnifiedQualitySourceMapper,
    private val contents:UnifiedQualityContentMapper,
    private val sdk:UnifiedQualitySdkMapper,
    private val station:UnifiedQualityStationMapper,
    private val batches:UnifiedQualityBatchMapper,
    private val rows:UnifiedQualityImportRowMapper,
    private val links:UnifiedQualityLinkMapper,
    private val snapshots:UnifiedQualitySnapshotMapper,
    private val rules:UnifiedQualityAlertRuleMapper,
    private val instances:UnifiedQualityAlertInstanceMapper
) {
    fun formalFacts(channel:String,now:LocalDateTime):UnifiedQualityFormalFacts {
        val sourceRows=sources.findByChannel(channel)
        val sourceIds=sourceRows.map{it.id}
        val contentBySource=sourceIds.chunked(MAX_BATCH).flatMap(contents::findActiveBySourceIds).associateBy{it.sourceId}
        val sdkBySource=sourceIds.chunked(MAX_BATCH).flatMap(sdk::findBySourceIds).associateBy{it.sourceId}
        val stationBySource=sourceIds.chunked(MAX_BATCH).flatMap(station::findBySourceIds).associateBy{it.sourceId}
        val represented=sourceRows.filter{contentBySource.containsKey(it.id)}
        val ownLinks=sourceIds.chunked(MAX_BATCH).flatMap(links::findActiveBySourceIds)
        val clusterIds=ownLinks.map{it.clusterId}.distinct()
        val allLinks=clusterIds.chunked(MAX_BATCH).flatMap(links::findActiveByClusterIds)
        val relatedIds=allLinks.map{it.feedbackSourceId}.distinct()
        val channelBySource=relatedIds.chunked(MAX_BATCH).flatMap(sources::findByIds).associate{it.id to it.sourceChannel}
        val clustersBySource=ownLinks.groupBy{it.feedbackSourceId};val linksByCluster=allLinks.groupBy{it.clusterId}
        fun nonBlank(value:String?)=!value.isNullOrBlank()
        var complete=0L;var timeValid=0L;var enumValid=0L;var appMapped=0L;var moduleMapped=0L
        var versionMapped=0L;var maskingPassed=0L;var anomalies=0L;var quarantined=0L;var accessFailures=0L;var cross=0L
        represented.forEach{source->
            val content=contentBySource.getValue(source.id);val stationRow=stationBySource[source.id]
            if(nonBlank(source.sourceRecordId)&&nonBlank(content.description))complete++
            if(source.sourceEventTime?.let{!it.isBefore(LocalDateTime.of(2000,1,1,0,0))&&!it.isAfter(now.plusDays(1))}==true)timeValid++
            if(!content.mappingError)enumValid++
            if(when(channel){"JIRA"->true;"STATION_FEEDBACK"->nonBlank(stationRow?.productId);else->nonBlank(source.packageName)||nonBlank(sdkBySource[source.id]?.appId)})appMapped++
            if(channel in setOf("JIRA","STATION_FEEDBACK")||nonBlank(content.functionModule))moduleMapped++
            if(channel=="JIRA"||nonBlank(source.appVersion))versionMapped++
            if(content.maskingStatus=="PASS")maskingPassed++
            if(content.maskingStatus!="PASS"||(content.dataQualityScore?.toDouble()?.let{it<.8}==true))anomalies++
            if(content.maskingStatus in setOf("PENDING","REVIEW_NEEDED","FAIL"))quarantined++
            if(channel=="STATION_FEEDBACK"&&content.maskingStatus!="REVIEW_NEEDED"&&stationRow?.ingestStatus in setOf("REVIEW_NEEDED","FAILED_FINAL"))accessFailures++
            if(clustersBySource[source.id].orEmpty().any{own->linksByCluster[own.clusterId].orEmpty().any{other->channelBySource[other.feedbackSourceId]?.let{it!=channel}==true}})cross++
        }
        return UnifiedQualityFormalFacts(represented.size.toLong(),complete,timeValid,enumValid,appMapped,moduleMapped,
            versionMapped,maskingPassed,anomalies,quarantined,accessFailures,cross)
    }

    fun importFacts(channel:String):UnifiedQualityImportFacts {
        val batchRows=batches.findActiveByChannel(channel);if(batchRows.isEmpty())return UnifiedQualityImportFacts(0,0,0,0)
        val batchIds=batchRows.map{it.id};val observed=batchIds.chunked(MAX_BATCH).flatMap(rows::findObserved)
        val candidates=batchIds.chunked(MAX_BATCH).flatMap(rows::findCurrentCandidates)
        val hashes=candidates.mapNotNull{it.businessKeyHash}.distinctBy(::hashKey)
        val linked=hashes.chunked(MAX_BATCH).flatMap(rows::findByBusinessHashes)
        val linkedSourceIds=linked.mapNotNull{it.feedbackSourceId}.distinct()
        val linkedSources=linkedSourceIds.chunked(MAX_BATCH).flatMap(sources::findByIds).associateBy{it.id}
        val unsafeSources=linkedSourceIds.chunked(MAX_BATCH).flatMap(contents::findUnsafeBySourceIds).map{it.sourceId}.toSet()
        val unsafeHashes=linked.asSequence().filter{row->row.feedbackSourceId?.let{linkedSources[it]?.sourceChannel==channel&&it in unsafeSources}==true}
            .mapNotNull{it.businessKeyHash?.let(::hashKey)}.toSet()
        val createdAt=batchRows.associate{it.id to it.createdAt}
        val latest=candidates.groupBy{it.businessKeyHash?.let(::hashKey)?:"ROW:${it.id}"}.values.mapNotNull{group->
            group.maxWithOrNull(compareBy<UnifiedQualityImportRowPo>({createdAt[it.batchId]?:LocalDateTime.MIN},{it.id}))
        }.filter{it.businessKeyHash?.let(::hashKey) !in unsafeHashes}
        val isolated=latest.filter{it.rowStatus in setOf("REVIEW_NEEDED","FAILED")}
        return UnifiedQualityImportFacts(observed.size.toLong(),observed.count{it.rowStatus=="SKIPPED_DUPLICATE"}.toLong(),
            isolated.size.toLong(),isolated.count{it.rowStatus=="FAILED"}.toLong())
    }

    fun insertSnapshot(channel:String,total:Long,score:java.math.BigDecimal?,version:String,json:String):Long {
        val row=UnifiedQualitySnapshotPo(dimensionKey=channel,sampleCount=total,completeness=score,dataContractVersion=version,evaluationSnapshot=json,windowStart=null,windowEnd=null)
        if(snapshots.insert(row)!=1||row.id<=0) importRepositoryFailed("UNIFIED_QUALITY_SNAPSHOT_INSERT_FAILED");return row.id
    }
    fun latestSnapshots()=snapshots.findLatest()
    fun recentThreshold(dimension:String,since:LocalDateTime)=snapshots.countRecentThreshold(dimension,since)>0
    fun activeRule()=rules.findActive()
    fun insertAlertInstance(ruleId:Long):Long {val row=UnifiedQualityAlertInstancePo(ruleId=ruleId);if(instances.insert(row)!=1||row.id<=0) importRepositoryFailed("UNIFIED_QUALITY_ALERT_INSERT_FAILED");return row.id}
    fun insertThreshold(row:UnifiedQualityThresholdOccurrencePo){if(snapshots.insertThreshold(row)!=1||row.id<=0) importRepositoryFailed("UNIFIED_QUALITY_THRESHOLD_INSERT_FAILED")}

    private fun hashKey(value:ByteArray)=Base64.getEncoder().encodeToString(value)
    companion object{private const val MAX_BATCH=200}
}

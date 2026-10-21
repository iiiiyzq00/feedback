package com.feedback.repository.alert
import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
@Repository class AlertTrendBaselineRepository(private val sources:AlertTrendSourceMapper,private val contents:AlertTrendContentMapper,
 private val links:AlertTrendLinkMapper,private val occurrences:AlertTrendOccurrenceMapper){
 data class HourFacts(val clusters:Map<String,Long>,val negative:Long)
 fun feedbackFacts(start:LocalDateTime,end:LocalDateTime):HourFacts{val clusterSources=mutableMapOf<String,MutableSet<Long>>();var negative=0L;var after=0L
  while(true){val ids=sources.findIds(start,end,after,500);if(ids.isEmpty())break;val safe=contents.findSafe(ids);negative+=safe.count{it.userSentiment=="NEGATIVE"};val safeIds=safe.map{it.sourceId};if(safeIds.isNotEmpty())links.findMain(safeIds).forEach{clusterSources.computeIfAbsent(it.clusterId.toString()){mutableSetOf()}.add(it.feedbackSourceId)};after=ids.last();if(ids.size<500)break}
  return HourFacts(clusterSources.mapValues{it.value.size.toLong()},negative)}
 fun sdkCount(start:LocalDateTime,end:LocalDateTime)=occurrences.countSdk(start,end)
 fun upsert(rows:List<TrendBaselineWritePo>){rows.chunked(500).forEach{if(it.isNotEmpty())occurrences.upsert(it)}}
 fun baselines(metric:String,dimensions:List<String>,start:LocalDateTime,end:LocalDateTime)=if(dimensions.isEmpty())emptyList() else occurrences.findBaselines(metric,dimensions,start,end)
}

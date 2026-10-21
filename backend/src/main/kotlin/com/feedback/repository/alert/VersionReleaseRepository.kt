package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class VersionFeedbackFact(val source:VersionSourcePo,val content:VersionContentPo,val clusterIds:Set<Long>)
@Repository class VersionReleaseRepository(private val configs:VersionReleaseConfigMapper,private val sources:VersionReleaseSourceMapper,private val contents:VersionReleaseContentMapper,private val links:VersionReleaseClusterLinkMapper){
 fun list()=configs.list()
 fun activeBefore(end:LocalDateTime)=configs.activeBefore(end)
 fun save(packageName:String,appVersion:String,controlVersion:String,releasedAt:LocalDateTime,status:String,actor:Long){
  if(configs.update(packageName,appVersion,controlVersion,releasedAt,status)==0)ensureAlertRepositoryState(configs.insert(packageName,appVersion,controlVersion,releasedAt,status,actor)==1)
 }
 fun forEachFact(releases:List<VersionReleasePo>,end:LocalDateTime,startOverride:LocalDateTime?=null,consumer:(VersionFeedbackFact)->Unit){
  if(releases.isEmpty())return
  val packages=releases.map{it.packageName}.distinct();val versions=releases.flatMap{listOf(it.appVersion,it.controlVersion)}.distinct()
  val start=startOverride?:minOf(end.minusMinutes(30),releases.minOf{it.releasedAt.minusDays(7)})
  var after=0L
  while(true){
   val page=sources.page(after,packages,versions,start,end,500);if(page.isEmpty())break;after=page.last().id
   val ids=page.map{it.id};val contentRows=contents.safe(ids);val groupedContent=contentRows.groupBy{it.sourceId};ensureAlertRepositoryState(groupedContent.values.none{it.size>1}){"VERSION_CONTENT_DUPLICATED"};val safe=groupedContent.mapValues{it.value.single()};val clusters=links.activeMain(ids).groupBy{it.sourceId}.mapValues{(_,v)->v.map{it.clusterId}.toSet()}
   page.forEach{source->safe[source.id]?.let{consumer(VersionFeedbackFact(source,it,clusters[source.id].orEmpty()))}}
   if(page.size<500)break
  }
 }
}

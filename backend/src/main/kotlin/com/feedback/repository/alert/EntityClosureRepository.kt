package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

data class EntityClosureSourceFact(val id:Long,val sourceChannel:String,val functionModule:String?,val provinceCode:String?,val cityCode:String?,val processingStatus:String)
data class EntityClosureCounts(val clusters:Long,val resolvedClusters:Long,val rcas:Long,val reviewedRcas:Long,val rcasWithPublishedKnowledge:Long,val publishedKnowledge:Long,val effectiveKnowledge:Long)
data class EntityClosureFacts(val incident:ClosureIncidentPo,val sources:List<EntityClosureSourceFact>,val counts:EntityClosureCounts)

@Repository class EntityClosureRepository(
 private val incidents:EntityClosureIncidentMapper,private val rootLinks:EntityClosureRootLinkMapper,
 private val clusters:EntityClosureClusterMapper,private val rcas:EntityClosureRcaMapper,
 private val knowledge:EntityClosureKnowledgeMapper,private val audits:EntityClosureAuditMapper,
 private val clusterLinks:EntityClosureClusterLinkMapper,private val sources:EntityClosureSourceMapper,
 private val contents:EntityClosureContentMapper){
 fun linkedCount(incidentId:Long)=rootLinks.count(incidentId)
 fun facts(incidentId:Long):EntityClosureFacts{
  val incident=alertRepositoryStateRequired(incidents.find(incidentId))
  val links=mutableListOf<ClosureRootLinkPo>();var after=0L
  while(true){val page=rootLinks.page(incidentId,after,500);if(page.isEmpty())break;links+=page;after=page.last().id;if(page.size<500)break}
  val clusterIds=links.map{it.clusterId}.distinct();val rcaIds=links.map{it.rcaId}.distinct()
  val clusterMap=clusterIds.chunked(500).flatMap(clusters::find).associateBy{it.id};val rcaMap=rcaIds.chunked(500).flatMap(rcas::find).associateBy{it.id}
  val validLinks=links.filter{it.clusterId in clusterMap&&it.rcaId in rcaMap};val validClusterIds=validLinks.map{it.clusterId}.toSet();val validRcaIds=validLinks.map{it.rcaId}.toSet()
  val knowledgeRows=validRcaIds.toList().chunked(500).flatMap(knowledge::findByRcas);val knowledgeIds=knowledgeRows.map{it.id}.toSet();val published=knowledgeRows.filter{it.status=="PUBLISHED"};val publishedIds=published.map{it.id}.toSet()
  val effective=knowledgeIds.map(Long::toString).chunked(500).flatMap{audits.effectiveKnowledge(it,"INCIDENT:$incidentId")}.mapNotNull{it.targetId.toLongOrNull()}.toSet()
  val sourceIds=clusterIds.chunked(500).flatMap(clusterLinks::sourceIds).map{it.sourceId}.distinct();val sourceMap=sourceIds.chunked(500).flatMap(sources::find).associateBy{it.id}
  val contentRows=sourceIds.chunked(500).flatMap(contents::findActive);val groupedContent=contentRows.groupBy{it.sourceId};ensureAlertRepositoryState(groupedContent.values.none{it.size>1}){"ENTITY_CLOSURE_CONTENT_DUPLICATED"}
  val sourceFacts=sourceIds.mapNotNull{id->val source=sourceMap[id]?:return@mapNotNull null;val content=groupedContent[id]?.singleOrNull()?:return@mapNotNull null;EntityClosureSourceFact(id,source.sourceChannel,content.functionModule,source.provinceCode,source.cityCode,content.processingStatus)}
  val counts=EntityClosureCounts(validClusterIds.size.toLong(),validClusterIds.count{clusterMap[it]?.status=="RESOLVED"}.toLong(),validRcaIds.size.toLong(),validRcaIds.count{rcaMap[it]?.status in setOf("CONFIRMED","CORRECTED")}.toLong(),validRcaIds.count{rca->published.any{it.sourceRcaId==rca}}.toLong(),publishedIds.size.toLong(),effective.intersect(knowledgeIds).size.toLong())
  return EntityClosureFacts(incident,sourceFacts,counts)
 }
 fun closeSources(incidentId:Long,actor:Long){
  val clusterIds=allRootLinks(incidentId).map{it.clusterId}.distinct();val sourceIds=clusterIds.chunked(500).flatMap(clusterLinks::sourceIds).map{it.sourceId}.distinct()
  sourceIds.chunked(500).forEach{contents.close(it,actor)}
 }
 fun auditClose(incidentId:Long,actor:Long,reason:String){ensureAlertRepositoryState(audits.insertClose(actor,incidentId.toString(),reason)==1)}
 private fun allRootLinks(incidentId:Long):List<ClosureRootLinkPo>{val result=mutableListOf<ClosureRootLinkPo>();var after=0L;while(true){val page=rootLinks.page(incidentId,after,500);if(page.isEmpty())break;result+=page;after=page.last().id;if(page.size<500)break};return result}
}

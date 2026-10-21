package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

data class AlertVisibilityFact(val channel:String,val module:String?,val province:String?,val city:String?)

@Repository
class AlertQueryRepository(
 private val instances:AlertQueryInstanceMapper,private val rules:AlertQueryRuleMapper,
 private val incidentLinks:AlertQueryIncidentLinkMapper,private val occurrences:AlertQueryOccurrenceMapper,
 private val clusterLinks:AlertQueryClusterLinkMapper,private val sources:AlertQuerySourceMapper,
 private val contents:AlertQueryContentMapper,private val users:AlertQueryUserMapper,
 private val roles:AlertQueryUserRoleMapper,private val incidents:AlertQueryIncidentMapper,
 private val rcas:AlertQueryRcaMapper,private val clusterRcas:AlertQueryClusterRcaMapper,
 private val knowledge:AlertQueryKnowledgeMapper,private val notifications:AlertQueryNotificationMapper,
 private val audits:AlertQueryAuditMapper){
 fun page(query:AlertQueryCriteria,offset:Int,limit:Int)=instances.page(query,offset,limit)
 fun pageAll(afterId:Long,limit:Int)=instances.pageAll(afterId,limit)
 fun instance(id:Long)=instances.find(id)
 fun lockInstance(id:Long)=instances.lock(id)
 fun status(id:Long)=instances.status(id)
 fun ruleMap(rows:Collection<AlertQueryInstancePo>):Map<Long,AlertQueryRulePo>{val ids=rows.map{it.ruleId}.distinct();if(ids.isEmpty())return emptyMap();val result=rules.find(ids);ensureAlertRepositoryState(result.groupBy{it.id}.values.none{it.size>1}){"ALERT_RULE_DUPLICATED"};return result.associateBy{it.id}}
 fun visibility(rows:Collection<AlertQueryInstancePo>):Map<Long,List<AlertVisibilityFact>>{
  val clusterIds=rows.mapNotNull{it.clusterId}.distinct();val byCluster=if(clusterIds.isEmpty())emptyMap() else clusterLinks.sources(clusterIds).groupBy{it.clusterId}
  val alertSources=rows.associate{row->row.id to (listOfNotNull(row.feedbackSourceId)+row.clusterId?.let{byCluster[it].orEmpty().map{link->link.sourceId}}.orEmpty()).distinct()}
  val ids=alertSources.values.flatten().distinct();if(ids.isEmpty())return alertSources.mapValues{emptyList()}
  val sourceMap=sources.find(ids).associateBy{it.id};val contentRows=contents.safe(ids);val grouped=contentRows.groupBy{it.sourceId};ensureAlertRepositoryState(grouped.values.none{it.size>1}){"ALERT_VISIBILITY_CONTENT_DUPLICATED"}
  return alertSources.mapValues{(_,sourceIds)->sourceIds.mapNotNull{id->val source=sourceMap[id]?:return@mapNotNull null;val content=grouped[id]?.singleOrNull()?:return@mapNotNull null;AlertVisibilityFact(source.channel,content.module,source.province,source.city)}}
 }
 fun latestIncidents(ids:Collection<Long>):Map<Long,Long>{val unique=ids.distinct();return if(unique.isEmpty())emptyMap() else incidentLinks.active(unique).groupBy{it.alertInstanceId}.mapValues{it.value.first().incidentId}}
 fun activeIncidentLinks(id:Long)=incidentLinks.active(listOf(id))
 fun latestOccurrences(ids:Collection<Long>):Map<Long,AlertQueryOccurrencePo>{val unique=ids.distinct();return if(unique.isEmpty())emptyMap() else occurrences.latest(unique).associateBy{it.instanceId}}
 fun assignees():List<AlertQueryUserPo>{val ids=roles.eligibleIds().map{it.userId}.distinct();return if(ids.isEmpty())emptyList() else users.active(ids).distinctBy{it.id}.sortedBy{it.displayName}.take(100)}
 fun userName(id:Long?)=id?.let{users.active(listOf(it)).firstOrNull()?.displayName}
 fun eligibleUser(id:Long):Boolean=roles.eligible(id)>0&&users.active(listOf(id)).isNotEmpty()
 fun incidentCount()=incidents.count()
 fun incidentPage(limit:Int,offset:Int)=incidents.page(limit,offset)
 fun incident(id:Long)=incidents.find(id)
 fun incidentFull(id:Long)=incidents.full(id)
 fun transferIncident(id:Long,user:Long,reason:String,actor:Long,version:Long)=incidents.transfer(id,user,reason,actor,version)==1
 fun rca(incidentId:Long,type:String)=rcas.latest(incidentId,type)
 fun knowledge(clusterId:Long):List<AlertQueryKnowledgePo>{val ids=clusterRcas.activeRcas(clusterId).map{it.rcaId}.distinct();if(ids.isEmpty())return emptyList();return knowledge.published(ids).sortedWith(compareBy<AlertQueryKnowledgePo>{it.effectivenessScore==null}.thenByDescending{it.effectivenessScore}.thenByDescending{it.publishedAt}).take(3)}
 fun activeSilence(id:Long)=notifications.activeSilence(id)
 fun hasActiveSilence(id:Long)=notifications.activeSilenceCount(id)>0
 fun insertSilence(id:Long,reason:String,minutes:Int,actor:Long){ensureAlertRepositoryState(notifications.insertSilence(id,reason,minutes,actor)==1)}
 fun chain(id:Long)=notifications.chain(id)
 fun chainTargets(id:Long)=notifications.targets(id)
 fun notificationTimeline(id:Long)=notifications.timeline(id)
 fun targetNames(ids:Collection<Long>):Map<Long,String?>{val unique=ids.distinct();return if(unique.isEmpty())emptyMap() else notifications.targetNames(unique).associate{it.compactId to it.displayName}}
 fun auditTimeline(id:Long)=audits.timeline(id.toString())
 fun audit(actor:Long,operation:String,id:Long,detail:String){ensureAlertRepositoryState(audits.insert(actor,operation,id.toString(),detail)==1)}
}

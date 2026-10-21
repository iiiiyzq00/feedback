package com.feedback.repository.alert
import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
@Repository class AlertReviewGovernanceRepository(private val links:ReviewMetricClusterLinkMapper,
 private val configs:ReviewMetricConfigMapper,private val sdk:ReviewMetricSdkMapper,private val imports:ReviewMetricImportMapper,
 private val occurrences:ReviewMetricOccurrenceMapper){
 fun clusterBreaches(at:LocalDateTime)=links.countBreaches(at)
 fun maskingBreaches(at:LocalDateTime):Double{val slas=configs.findSlas();val sdkRules=slas.filter{it.scopeKey=="SDK"&&it.subtype!="ALL"};val fallback=slas.firstOrNull{it.scopeKey=="SDK"&&it.subtype=="ALL"};val sdkCount=if(sdkRules.isEmpty()&&fallback==null)0.0 else sdk.countBreaches(at,sdkRules,sdkRules.map{it.subtype}.toSet(),fallback);val importSla=slas.firstOrNull{it.scopeKey=="IMPORT"&&it.subtype=="ALL"};return sdkCount+(importSla?.let{imports.countBreaches(at,it.minutes)}?:0.0)}
 fun decisionCount(at:LocalDateTime)=occurrences.decisionCount(at)
 fun overturnRate(at:LocalDateTime)=occurrences.overturnRate(at)
 fun inputCompleteness(at:LocalDateTime)=occurrences.inputCompleteness(at)?:100.0
 fun snapshot(start:LocalDateTime,end:LocalDateTime){val contracts=configs.dataContracts().groupBy{it.contractCode};val rows=configs.requiredRules().groupBy{it.ruleCode}.mapNotNull{(ruleCode,rules)->val matched=rules.flatMap{contracts[it.contractCode].orEmpty()};if(matched.isEmpty())null else{val present=matched.count{it.sourceSystem!="UNASSIGNED"&&it.schemaVersion!="unapproved-v1"}.toLong();RuleInputSnapshotPo(ruleCode,start,end,matched.size.toLong(),present,present.toDouble()/matched.size)}};rows.chunked(500).forEach{if(it.isNotEmpty())occurrences.insertSnapshots(it)}}
}

package com.feedback.service.cluster

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.repository.cluster.ClusterAiTaskRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

data class ClusterLabelCase(val caseId:String,val channel:String,val predictedSame:Boolean,val expectedSame:Boolean,
    val decisionType:String,val humanOverturned:Boolean=false,val containsIdentitySignal:Boolean=false,
    val predictedClusterId:String?=null,val expectedClusterId:String?=null,val similarity:BigDecimal?=null,val evidence:String?=null)
data class ClusterQualityAnnotation(val clusterId:String,val verdict:String,val reason:String)
data class ClusterAcceptanceThresholds(val f1Min:BigDecimal=BigDecimal("0.750000"),
    val errorMergeRateMax:BigDecimal=BigDecimal("0.050000"),val humanOverturnRateMax:BigDecimal=BigDecimal("0.150000"),
    val purityMin:BigDecimal=BigDecimal("0.800000"),val falseSplitRateMax:BigDecimal=BigDecimal("0.150000"),
    val unclusteredRateMax:BigDecimal=BigDecimal("0.150000"))
data class ClusterEvaluationRequest(val datasetVersion:String,val decisionVersion:String,val model:String,
    val shadow:Boolean=true,val evidenceKind:String="FIXTURE",val datasetSha256:String?=null,
    val annotatedBy:String?=null,val reviewedBy:String?=null,val approvalRef:String?=null,
    val thresholds:ClusterAcceptanceThresholds=ClusterAcceptanceThresholds(),val cases:List<ClusterLabelCase>,
    val clusterAnnotations:List<ClusterQualityAnnotation> = emptyList())

@Service
class ClusterAiEvaluationService(private val tasks:ClusterAiTaskRepository,private val mapper:ObjectMapper){
    fun evaluate(input:ClusterEvaluationRequest,actor:Long?):Map<String,Any?>{
        ensureClusterRequest(input.datasetVersion.matches(Regex("[A-Za-z0-9._-]{3,50}"))){"CLUSTER_EVAL_DATASET_VERSION_INVALID"}
        ensureClusterRequest(input.decisionVersion.matches(Regex("[A-Za-z0-9._-]{3,50}"))){"CLUSTER_EVAL_DECISION_VERSION_INVALID"}
        ensureClusterRequest(input.model.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{1,99}$"))){"CLUSTER_EVAL_MODEL_INVALID"}
        ensureClusterRequest(input.shadow){"CLUSTER_EVAL_MUST_BE_SHADOW"}
        ensureClusterRequest(input.evidenceKind in setOf("FIXTURE","APPROVED_HISTORICAL")){"CLUSTER_EVAL_EVIDENCE_KIND_INVALID"}
        if(input.evidenceKind=="APPROVED_HISTORICAL"){
            ensureClusterRequest(input.datasetSha256?.matches(Regex("^[a-fA-F0-9]{64}$"))==true){"CLUSTER_EVAL_DATASET_SHA256_REQUIRED"}
            ensureClusterRequest(!input.annotatedBy.isNullOrBlank()&&!input.reviewedBy.isNullOrBlank()&&input.annotatedBy!=input.reviewedBy){"CLUSTER_EVAL_INDEPENDENT_REVIEW_REQUIRED"}
            ensureClusterRequest(input.approvalRef?.matches(Regex("[A-Za-z0-9._:/-]{6,100}"))==true){"CLUSTER_EVAL_APPROVAL_REF_REQUIRED"}
        }
        ensureClusterRequest(input.cases.size in 100..5000){"CLUSTER_EVAL_MINIMUM_100_CASES"}
        ensureClusterRequest(input.cases.map{it.caseId}.distinct().size==input.cases.size){"CLUSTER_EVAL_CASE_ID_DUPLICATE"}
        ensureClusterRequest(input.cases.none{it.containsIdentitySignal}){"CLUSTER_EVAL_IDENTITY_SIGNAL_FORBIDDEN"}
        ensureClusterRequest(input.cases.map{it.channel.uppercase()}.toSet().containsAll(setOf("SDK","JIRA","QUALITY","REDBLACK"))){"CLUSTER_EVAL_FOUR_CHANNELS_REQUIRED"}
        ensureClusterRequest(input.cases.all{it.similarity==null||it.similarity in BigDecimal.ZERO..BigDecimal.ONE}){"CLUSTER_EVAL_SIMILARITY_INVALID"}
        ensureClusterRequest(input.cases.all{it.evidence==null||it.evidence.length<=500}){"CLUSTER_EVAL_EVIDENCE_TOO_LONG"}
        ensureClusterRequest(input.clusterAnnotations.map{it.clusterId}.distinct().size==input.clusterAnnotations.size){"CLUSTER_EVAL_ANNOTATION_DUPLICATE"}
        ensureClusterRequest(input.clusterAnnotations.all{it.verdict in setOf("GOOD","BAD")&&it.reason.trim().length in 2..500}){"CLUSTER_EVAL_ANNOTATION_INVALID"}
        validateThresholds(input.thresholds)
        val tp=input.cases.count{it.predictedSame&&it.expectedSame};val fp=input.cases.count{it.predictedSame&&!it.expectedSame}
        val fn=input.cases.count{!it.predictedSame&&it.expectedSame};val precision=ratio(tp,tp+fp);val recall=ratio(tp,tp+fn)
        val f1=if(precision+recall==BigDecimal.ZERO)BigDecimal.ZERO else BigDecimal(2).multiply(precision).multiply(recall).divide(precision+recall,6,RoundingMode.HALF_UP)
        val auto=input.cases.filter{it.decisionType.startsWith("AUTO")};val errorMerge=ratio(auto.count{it.predictedSame&&!it.expectedSame},auto.size)
        val overturn=ratio(input.cases.count{it.humanOverturned},input.cases.size)
        val slices=input.cases.groupBy{it.channel.uppercase()}.mapValues{(_,v)->mapOf("count" to v.size,"accuracy" to ratio(v.count{it.predictedSame==it.expectedSame},v.size))}
        val clusterMetricsAvailable=input.cases.any{it.expectedClusterId!=null||it.predictedClusterId!=null}
        if(clusterMetricsAvailable) ensureClusterRequest(input.cases.all{!it.expectedClusterId.isNullOrBlank()}){"CLUSTER_EVAL_EXPECTED_CLUSTER_REQUIRED"}
        val assigned=input.cases.filter{!it.predictedClusterId.isNullOrBlank()}
        val predictedGroups=assigned.groupBy{it.predictedClusterId!!}
        val expectedGroups=if(clusterMetricsAvailable)input.cases.groupBy{it.expectedClusterId!!}else emptyMap()
        val purity=if(!clusterMetricsAvailable||assigned.isEmpty())null else ratio(predictedGroups.values.sumOf{group->group.groupingBy{it.expectedClusterId}.eachCount().values.maxOrNull()?:0},assigned.size)
        val falseSplit=if(!clusterMetricsAvailable)null else ratio(expectedGroups.values.count{group->group.mapNotNull{it.predictedClusterId}.distinct().size>1},expectedGroups.size)
        val unclustered=if(!clusterMetricsAvailable)null else ratio(input.cases.count{it.predictedClusterId.isNullOrBlank()},input.cases.size)
        val annotationByCluster=input.clusterAnnotations.associateBy{it.clusterId}
        ensureClusterRequest(input.clusterAnnotations.all{it.clusterId in predictedGroups}){"CLUSTER_EVAL_ANNOTATION_CLUSTER_UNKNOWN"}
        val annotationsComplete=predictedGroups.isNotEmpty()&&predictedGroups.keys.all{it in annotationByCluster}
        val averageClusterSize=if(predictedGroups.isEmpty())null else BigDecimal(assigned.size).divide(BigDecimal(predictedGroups.size),6,RoundingMode.HALF_UP)
        val singletonRate=if(predictedGroups.isEmpty())null else ratio(predictedGroups.values.count{it.size==1},predictedGroups.size)
        val similarities=input.cases.mapNotNull{it.similarity}.sorted()
        val similarityDistribution=if(similarities.isEmpty())null else mapOf("count" to similarities.size,"min" to similarities.first(),
            "p50" to percentile(similarities,0.50),"p95" to percentile(similarities,0.95),"max" to similarities.last(),
            "below050" to similarities.count{it<BigDecimal("0.50")},"from050To084" to similarities.count{it>=BigDecimal("0.50")&&it<BigDecimal("0.85")},
            "atLeast085" to similarities.count{it>=BigDecimal("0.85")})
        val clusterSummaries=predictedGroups.entries.sortedByDescending{it.value.size}.take(100).map{(clusterId,group)->
            val expectedCounts=group.groupingBy{it.expectedClusterId?:"UNKNOWN"}.eachCount();val dominant=expectedCounts.maxByOrNull{it.value}
            mapOf("clusterId" to clusterId,"sampleCount" to group.size,"dominantExpectedClusterId" to dominant?.key,
                "purity" to ratio(dominant?.value?:0,group.size),"channels" to group.map{it.channel.uppercase()}.distinct().sorted(),
                "anomalyCaseIds" to group.filter{it.expectedClusterId!=dominant?.key}.map{it.caseId}.take(20),
                "qualityVerdict" to annotationByCluster[clusterId]?.verdict,"qualityReason" to annotationByCluster[clusterId]?.reason)
        }
        val errorCases=input.cases.filter{it.predictedSame!=it.expectedSame||it.humanOverturned||
            (clusterMetricsAvailable&&!it.predictedClusterId.isNullOrBlank()&&clusterSummaries.any{s->s["clusterId"]==it.predictedClusterId&&(s["anomalyCaseIds"] as List<*>).contains(it.caseId)})}
            .take(100).map{mapOf("caseId" to it.caseId,"channel" to it.channel.uppercase(),"predictedSame" to it.predictedSame,
                "expectedSame" to it.expectedSame,"predictedClusterId" to it.predictedClusterId,"expectedClusterId" to it.expectedClusterId,
                "similarity" to it.similarity,"evidence" to it.evidence,"humanOverturned" to it.humanOverturned)}
        val thresholds=input.thresholds
        val clusterGate=clusterMetricsAvailable&&annotationsComplete&&input.clusterAnnotations.none{it.verdict=="BAD"}&&purity!!>=thresholds.purityMin&&falseSplit!!<=thresholds.falseSplitRateMax&&unclustered!!<=thresholds.unclusteredRateMax
        val passed=f1>=thresholds.f1Min&&errorMerge<=thresholds.errorMergeRateMax&&overturn<=thresholds.humanOverturnRateMax
            &&slices.values.all{(it["count"] as Int)>=10}&&clusterGate
        val conclusion=when{
            !clusterMetricsAvailable||!annotationsComplete->"COMPLETE"
            passed->"KEEP"
            falseSplit!!>thresholds.falseSplitRateMax&&errorMerge<=thresholds.errorMergeRateMax&&purity!!>=thresholds.purityMin->"MERGE"
            else->"REFACTOR"
        }
        val conclusionLabel=mapOf("KEEP" to "保留","COMPLETE" to "补齐","REFACTOR" to "重构","MERGE" to "合并").getValue(conclusion)
        val summary=linkedMapOf<String,Any?>("gate" to if(passed)"PASSED" else "FAILED","sampleCount" to input.cases.size,
            "precision" to precision,"recall" to recall,"f1" to f1,"errorMergeRate" to errorMerge,"humanOverturnRate" to overturn,
            "clusterMetricsAvailable" to clusterMetricsAvailable,"clusterCount" to predictedGroups.size,"expectedClusterCount" to expectedGroups.size,
            "purity" to purity,"falseSplitRate" to falseSplit,"unclusteredRate" to unclustered,
            "averageClusterSize" to averageClusterSize,"singletonClusterRate" to singletonRate,"similarityDistribution" to similarityDistribution,
            "annotationsComplete" to annotationsComplete,"goodClusterCount" to input.clusterAnnotations.count{it.verdict=="GOOD"},
            "badClusterCount" to input.clusterAnnotations.count{it.verdict=="BAD"},"remediationConclusion" to conclusion,
            "remediationConclusionLabel" to conclusionLabel,
            "clusterSummaries" to clusterSummaries,"errorCases" to errorCases,
            "channelSlices" to slices,"datasetVersion" to input.datasetVersion,"decisionVersion" to input.decisionVersion,
            "model" to input.model,"shadow" to true,"evidenceKind" to input.evidenceKind,
            "datasetSha256" to input.datasetSha256,"annotatedBy" to input.annotatedBy,"reviewedBy" to input.reviewedBy,
            "approvalRef" to input.approvalRef,"thresholdPolicyVersion" to "cluster-acceptance-v1",
            "thresholds" to mapOf("f1Min" to thresholds.f1Min,"errorMergeRateMax" to thresholds.errorMergeRateMax,
                "humanOverturnRateMax" to thresholds.humanOverturnRateMax,"purityMin" to thresholds.purityMin,
                "falseSplitRateMax" to thresholds.falseSplitRateMax,"unclusteredRateMax" to thresholds.unclusteredRateMax))
        tasks.saveEvaluation("CLUSTER_EVAL:${input.datasetVersion}:${input.decisionVersion}:${input.model}", passed,
            mapper.writeValueAsString(mapOf("caseIds" to input.cases.map{it.caseId})),
            mapper.writeValueAsString(summary), actor, input.model)
        return summary+mapOf("passed" to passed)
    }
    fun latest():Map<String,Any?> = tasks.latestEvaluation()?.let { mapOf(
        "id" to it.id, "status" to it.status, "model" to it.model,
        "summary" to it.summary, "completedAt" to it.completedAt
    ) } ?: mapOf("status" to "MISSING")
    fun exportLatest():ByteArray{
        val latest=latest();ensureClusterRequest(latest["status"]!="MISSING", ErrorCode.NOT_FOUND){"CLUSTER_EVAL_REPORT_NOT_FOUND"}
        val summary=latest["summary"]
        val parsed=if(summary is String)mapper.readTree(summary)else summary
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(mapOf("status" to latest["status"],"model" to latest["model"],
            "completedAt" to latest["completedAt"],"summary" to parsed))
    }
    private fun validateThresholds(value:ClusterAcceptanceThresholds){
        listOf(value.f1Min,value.errorMergeRateMax,value.humanOverturnRateMax,value.purityMin,value.falseSplitRateMax,value.unclusteredRateMax)
            .forEach{ensureClusterRequest(it in BigDecimal.ZERO..BigDecimal.ONE){"CLUSTER_EVAL_THRESHOLD_INVALID"}}
        ensureClusterRequest(value.f1Min>=BigDecimal("0.750000")&&value.purityMin>=BigDecimal("0.800000")&&
            value.errorMergeRateMax<=BigDecimal("0.050000")&&value.humanOverturnRateMax<=BigDecimal("0.150000")&&
            value.falseSplitRateMax<=BigDecimal("0.150000")&&value.unclusteredRateMax<=BigDecimal("0.150000")){
            "CLUSTER_EVAL_THRESHOLD_CANNOT_BE_LOOSENED"
        }
    }
    private fun ratio(a:Int,b:Int)=if(b==0)BigDecimal.ZERO else BigDecimal(a).divide(BigDecimal(b),6,RoundingMode.HALF_UP)
    private fun percentile(values:List<BigDecimal>,quantile:Double):BigDecimal=values[((values.size-1)*quantile).toInt().coerceIn(0,values.lastIndex)]
}

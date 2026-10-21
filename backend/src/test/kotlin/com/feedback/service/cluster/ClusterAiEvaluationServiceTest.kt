package com.feedback.service.cluster
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.exception.ClientException
import io.mockk.every
import io.mockk.mockk
import com.feedback.repository.cluster.ClusterAiTaskRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClusterAiEvaluationServiceTest{
 @Test fun `one hundred four-channel labels pass production metrics without identity signals`(){
  val tasks=mockk<ClusterAiTaskRepository>(relaxed=true)
  val service=ClusterAiEvaluationService(tasks,jacksonObjectMapper())
  val channels=listOf("SDK","JIRA","QUALITY","REDBLACK")
  val cases=(1..100).map{val cluster="cluster-${(it-1)/10}";ClusterLabelCase("case-$it",channels[(it-1)%4],predictedSame=it%4!=0,expectedSame=it%4!=0,decisionType="AUTO_HIGH",predictedClusterId=cluster,expectedClusterId=cluster,similarity=java.math.BigDecimal("0.92"))}
  val annotations=(0..9).map{ClusterQualityAnnotation("cluster-$it","GOOD","人工抽检成员语义一致")}
  val result=service.evaluate(ClusterEvaluationRequest("dataset-v1","decision-v1","approved-openai-model",cases=cases,clusterAnnotations=annotations),1)
  assertEquals(true,result["passed"]);assertEquals(100,result["sampleCount"]);assertEquals("PASSED",result["gate"])
 }
 @Test fun `identity signals are forbidden as cluster labels`(){
  val service=ClusterAiEvaluationService(mockk(relaxed=true),jacksonObjectMapper())
  val cases=(1..100).map{ClusterLabelCase("c$it",listOf("SDK","JIRA","QUALITY","REDBLACK")[(it-1)%4],true,true,"AUTO_HIGH",containsIdentitySignal=it==1)}
 assertThrows(ClientException::class.java){service.evaluate(ClusterEvaluationRequest("dataset-v1","decision-v1","approved-openai-model",cases=cases),1)}
 }
 @Test fun `approved historical evidence requires hash and independent reviewers`(){
  val service=ClusterAiEvaluationService(mockk(relaxed=true),jacksonObjectMapper())
  val cases=(1..100).map{ClusterLabelCase("c$it",listOf("SDK","JIRA","QUALITY","REDBLACK")[(it-1)%4],true,true,"AUTO_HIGH")}
 assertThrows(ClientException::class.java){service.evaluate(ClusterEvaluationRequest("dataset-v1","decision-v1","approved-openai-model",evidenceKind="APPROVED_HISTORICAL",datasetSha256="a".repeat(64),annotatedBy="same",reviewedBy="same",approvalRef="APPROVAL-1",cases=cases),1)}
 }
 @Test fun `cluster labels produce purity split unclustered and anomaly evidence`(){
  val tasks=mockk<ClusterAiTaskRepository>(relaxed=true)
  val service=ClusterAiEvaluationService(tasks,jacksonObjectMapper());val channels=listOf("SDK","JIRA","QUALITY","REDBLACK")
  val cases=(1..100).map{val expected=if(it<=50)"gold-a" else "gold-b";val predicted=if(it==100)null else expected
   ClusterLabelCase("case-$it",channels[(it-1)%4],true,true,"AUTO_HIGH",predictedClusterId=predicted,expectedClusterId=expected,
    similarity=java.math.BigDecimal("0.91"),evidence="脱敏样本证据")}
  val annotations=listOf(ClusterQualityAnnotation("gold-a","GOOD","人工抽检成员一致"),ClusterQualityAnnotation("gold-b","GOOD","人工抽检成员一致"))
  val result=service.evaluate(ClusterEvaluationRequest("dataset-v2","decision-v2","approved-openai-model",cases=cases,clusterAnnotations=annotations),1)
  assertEquals(true,result["clusterMetricsAvailable"]);assertEquals(java.math.BigDecimal("1.000000"),result["purity"])
  assertEquals(java.math.BigDecimal("0.010000"),result["unclusteredRate"]);assertEquals(2,result["clusterCount"])
  assertEquals("KEEP",result["remediationConclusion"]);assertEquals(java.math.BigDecimal("49.500000"),result["averageClusterSize"])
 }
 @Test fun `pair-only evidence cannot pass formal cluster gate and requires completion`(){
  val tasks=mockk<ClusterAiTaskRepository>(relaxed=true)
  val service=ClusterAiEvaluationService(tasks,jacksonObjectMapper());val channels=listOf("SDK","JIRA","QUALITY","REDBLACK")
  val cases=(1..100).map{ClusterLabelCase("p$it",channels[(it-1)%4],true,true,"AUTO_HIGH")}
  val result=service.evaluate(ClusterEvaluationRequest("dataset-pair","decision-pair","approved-openai-model",cases=cases),1)
  assertEquals(false,result["passed"]);assertEquals("COMPLETE",result["remediationConclusion"]);assertEquals(false,result["annotationsComplete"])
 }
 @Test fun `fragmented gold cluster produces merge conclusion`(){
  val tasks=mockk<ClusterAiTaskRepository>(relaxed=true)
  val service=ClusterAiEvaluationService(tasks,jacksonObjectMapper());val channels=listOf("SDK","JIRA","QUALITY","REDBLACK")
  val cases=(1..100).map{val predicted=if(it<=50)"fragment-a" else "fragment-b";ClusterLabelCase("m$it",channels[(it-1)%4],true,true,"AUTO_HIGH",predictedClusterId=predicted,expectedClusterId="gold-one",similarity=java.math.BigDecimal("0.90"))}
  val labels=listOf(ClusterQualityAnnotation("fragment-a","GOOD","簇内语义一致但边界相同"),ClusterQualityAnnotation("fragment-b","GOOD","簇内语义一致但边界相同"))
  val result=service.evaluate(ClusterEvaluationRequest("dataset-merge","decision-merge","approved-openai-model",cases=cases,clusterAnnotations=labels),1)
  assertEquals(false,result["passed"]);assertEquals("MERGE",result["remediationConclusion"])
 }
 @Test fun `mixed cluster or bad human label produces refactor conclusion`(){
  val tasks=mockk<ClusterAiTaskRepository>(relaxed=true)
  val service=ClusterAiEvaluationService(tasks,jacksonObjectMapper());val channels=listOf("SDK","JIRA","QUALITY","REDBLACK")
  val cases=(1..100).map{val gold=if(it%2==0)"gold-a" else "gold-b";ClusterLabelCase("r$it",channels[(it-1)%4],true,true,"AUTO_HIGH",predictedClusterId="mixed",expectedClusterId=gold,similarity=java.math.BigDecimal("0.60"))}
  val result=service.evaluate(ClusterEvaluationRequest("dataset-refactor","decision-refactor","approved-openai-model",cases=cases,clusterAnnotations=listOf(ClusterQualityAnnotation("mixed","BAD","人工确认混入两类不同诉求"))),1)
  assertEquals(false,result["passed"]);assertEquals("REFACTOR",result["remediationConclusion"])
 }
 @Test fun `invalid acceptance threshold is rejected`(){
  val service=ClusterAiEvaluationService(mockk(relaxed=true),jacksonObjectMapper());val channels=listOf("SDK","JIRA","QUALITY","REDBLACK")
  val cases=(1..100).map{ClusterLabelCase("c$it",channels[(it-1)%4],true,true,"AUTO_HIGH")}
 assertThrows(ClientException::class.java){service.evaluate(ClusterEvaluationRequest("dataset-v1","decision-v1","approved-openai-model",
   thresholds=ClusterAcceptanceThresholds(purityMin=java.math.BigDecimal("1.1")),cases=cases),1)}
 }
 @Test fun `acceptance thresholds cannot be loosened below governed baseline`(){
  val service=ClusterAiEvaluationService(mockk(relaxed=true),jacksonObjectMapper());val channels=listOf("SDK","JIRA","QUALITY","REDBLACK")
  val cases=(1..100).map{ClusterLabelCase("c$it",channels[(it-1)%4],true,true,"AUTO_HIGH")}
  val error=assertThrows(ClientException::class.java){service.evaluate(ClusterEvaluationRequest("dataset-v1","decision-v1","approved-openai-model",
   thresholds=ClusterAcceptanceThresholds(f1Min=java.math.BigDecimal("0.70")),cases=cases),1)}
  assertEquals("CLUSTER_EVAL_THRESHOLD_CANNOT_BE_LOOSENED",error.reasonCode)
 }
}

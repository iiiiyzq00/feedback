package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.service.ai.GovernedSkillExecutionService
import org.springframework.stereotype.Service

data class AlertRouteAdvice(val summary:String,val suggestedActions:List<String>,val degraded:Boolean)

@Service
class AlertRouteSkillService(private val mapper:ObjectMapper,private val governed:GovernedSkillExecutionService){
    fun advise(instanceId:Long,facts:Map<String,Any?>):AlertRouteAdvice{
        return advise("$instanceId",facts)
    }

    fun adviseForDrill(runId:Long,facts:Map<String,Any?>):AlertRouteAdvice{
        return advise("drill-$runId",facts)
    }

    private fun advise(stableKey:String,facts:Map<String,Any?>):AlertRouteAdvice{
        val fallbackActions=listOf("核对规则指标与同窗口原始事实","由责任团队确认影响范围并更新 Incident","需要时发起快速 RCA")
        val fallback=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("summary" to facts["fallbackSummary"],"suggested_actions" to fallbackActions))
        val execution=governed.invokeJson("alert_route",stableKey,facts,fallback);val result=execution.output
        val summary=result.path("summary").asText(facts["fallbackSummary"].toString()).take(200)
        val actions=result.path("suggested_actions").takeIf{it.isArray}?.map{it.asText().trim().take(120)}?.filter{it.isNotBlank()}?.take(5).orEmpty().ifEmpty{fallbackActions}
        return AlertRouteAdvice(summary,actions,execution.degraded)
    }
}

package com.feedback.service.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.feedback.model.po.ai.GovernedSkillInvocationInsertPo
import com.feedback.repository.ai.GovernedSkillInvocationRepository

data class GovernedSkillResult(val output:JsonNode,val degraded:Boolean,val prompt:RenderedPrompt?)

/** 受治理 Skill 共用的执行/审计边界。业务写入仍留在本服务之外。 */
@Service
class GovernedSkillExecutionService(
    private val invocations: GovernedSkillInvocationRepository,private val mapper:ObjectMapper,private val prompts:SkillPromptService,
    private val llm:LlmClient,@Value("\${spring.application.version:dev}") private val applicationVersion:String
){
    fun invokeJson(skill:String,stableKey:String,input:Any,fallback:JsonNode,sourceId:Long?=null):GovernedSkillResult{
        val prompt=runCatching{prompts.selectForInvocation(skill,stableKey)}.getOrNull()
        if(prompt==null){record(skill,stableKey,null,input,fallback,"COMPLETED",true,null,null,sourceId);return GovernedSkillResult(fallback,true,null)}
        return runCatching{
            val inputJson=mapper.writeValueAsString(input)
            val narration=llm.narrateGoverned("执行 $skill 并严格返回约定 JSON",skill,inputJson,prompt)
            val output=parseJson(narration.text)
            record(skill,stableKey,prompt,input,output,"COMPLETED",false,narration,null,sourceId)
            GovernedSkillResult(output,false,prompt)
        }.getOrElse{error->record(skill,stableKey,prompt,input,fallback,"COMPLETED",true,null,error,sourceId);GovernedSkillResult(fallback,true,prompt)}
    }

    fun recordDeterministic(skill:String,stableKey:String,input:Any,output:Any,sourceId:Long?=null){
        val prompt=runCatching{prompts.selectForInvocation(skill,stableKey)}.getOrNull()
        record(skill,stableKey,prompt,input,mapper.valueToTree(output),"COMPLETED",false,null,null,sourceId)
    }

    private fun record(skill:String,key:String,prompt:RenderedPrompt?,input:Any,output:JsonNode,status:String,degraded:Boolean,
                       narration:LlmNarration?,error:Throwable?,sourceId:Long?){runCatching{
        invocations.insert(GovernedSkillInvocationInsertPo(
            skill,"$skill:${key.take(150)}",sourceId,prompt?.promptVersion?:"deterministic-v1",prompt?.promptVersionId,
            prompt?.skillVersion?:"1.0.0",prompt?.contentSha256,
            mapper.writeValueAsString(prompt?.modelConfig?:mapper.createObjectNode()),applicationVersion,status,
            mapper.writeValueAsString(input),mapper.writeValueAsString(mapOf("degraded" to degraded,"result" to output)),
            error?.javaClass?.simpleName?.take(100),narration?.latencyMs,narration?.inputTokens,narration?.outputTokens,
            narration?.estimatedCostUsd,narration?.providerRequestId,narration?.modelName
        ))
    }}
    private fun parseJson(text:String):JsonNode{val clean=text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim();return mapper.readTree(clean).also{ensureAi(it.isObject){"SKILL_OUTPUT_NOT_OBJECT"}}}
}

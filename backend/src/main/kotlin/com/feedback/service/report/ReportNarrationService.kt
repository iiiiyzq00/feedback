package com.feedback.service.report

import com.feedback.service.ai.LlmClient
import com.feedback.service.ai.LlmNarration
import com.feedback.service.ai.RenderedPrompt
import com.feedback.service.ai.SkillPromptService
import com.feedback.repository.report.ReportAiCircuitRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ReportNarrationResult(val narration:LlmNarration?,val prompt:RenderedPrompt?,val degraded:Boolean,val degradationReason:String?)

@Service
class ReportNarrationService(
    private val llm:LlmClient,
    private val prompts:SkillPromptService,
    private val circuit:ReportAiCircuitBreaker,
    private val outboundPrivacy:com.feedback.service.masking.OutboundPrivacyGate?=null
) {
    fun generate(title:String,type:String,factsJson:String):ReportNarrationResult {
        outboundPrivacy?.requireJsonSafe("REPORT_LLM",factsJson)
        val prompt=runCatching{prompts.current("report_generate")}.getOrNull()
            ?:return ReportNarrationResult(null,null,true,"REPORT_PROMPT_UNAVAILABLE")
        if(!circuit.acquireCallPermit())return ReportNarrationResult(null,prompt,true,"CIRCUIT_OPEN")
        return try {
            val narration=llm.narrateGoverned(title,"REPORT_$type",factsJson,prompt)
            circuit.recordSuccess()
            ReportNarrationResult(narration,prompt,false,null)
        } catch(error:Exception) {
            circuit.recordFailure(error)
            ReportNarrationResult(null,prompt,true,com.feedback.common.SafeErrorDetail.summary(error,"REPORT_NARRATION"))
        }
    }

}

@Service
class ReportAiCircuitBreaker(private val repository:ReportAiCircuitRepository,private val mapper:ObjectMapper=ObjectMapper()) {
    private fun ensureState() {
        repository.ensure()
    }
    @Transactional
    fun acquireCallPermit():Boolean {
        ensureState()
        val row=repository.lock()
        if(row.circuitState=="CLOSED")return true
        if(row.circuitState=="HALF_OPEN")return false
        if(!repository.probeDue())return false
        repository.halfOpen()
        return true
    }

    @Transactional
    fun recordSuccess(){ensureState();val previous=repository.lock().circuitState;repository.close();if(previous!="CLOSED")audit("REPORT_AI_RECOVERED",mapOf("previousState" to previous))}

    @Transactional
    fun recordFailure(error:Exception){ensureState();val row=repository.lock();val previous=row.circuitState;val failures=row.consecutiveFailures+1;val open=failures>=10||previous=="HALF_OPEN";val code=com.feedback.common.SafeErrorDetail.code(error);repository.failure(failures,code,if(open)"OPEN" else previous,open);if(open&&previous!="OPEN")audit("REPORT_AI_CIRCUIT_OPEN",mapOf("errorCode" to code,"consecutiveFailures" to failures))}

    private fun audit(operation:String,detail:Map<String,Any?>)=repository.audit(operation,mapper.writeValueAsString(detail))
}

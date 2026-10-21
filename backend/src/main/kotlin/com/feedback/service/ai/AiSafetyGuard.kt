package com.feedback.service.ai

import org.springframework.stereotype.Service

@Service
class AiSafetyGuard {
    fun requireSafeQuestion(question:String){
        val normalized=question.lowercase().replace(Regex("\\s+"),"")
        ensureAi(INJECTION.none{it.containsMatchIn(normalized)}){"AI_PROMPT_INJECTION_REJECTED"}
        ensureAi(EXFILTRATION.none{it.containsMatchIn(normalized)}){"AI_SENSITIVE_RECOVERY_REJECTED"}
    }
    fun requireSafeOutput(vararg values:String?){
        ensureAi(values.filterNotNull().none{value->OUTPUT_ATTACK.any{it.containsMatchIn(value.lowercase())}}){"AI_UNSAFE_OUTPUT_REJECTED"}
    }
    companion object{
        private val INJECTION=listOf(Regex("忽略.{0,12}(之前|系统|上述).{0,8}(指令|提示)"),Regex("ignore.{0,20}(previous|system).{0,10}(instruction|prompt)"),Regex("systemprompt|系统提示词|开发者指令"))
        private val EXFILTRATION=listOf(Regex("(恢复|还原|输出|展示).{0,12}(原文|未脱敏|手机号|身份证|密钥|token)"),Regex("(bypass|reveal|recover).{0,15}(pii|secret|rawdata|masking)"))
        private val OUTPUT_ATTACK=listOf(Regex("ignore.{0,20}instruction"),Regex("忽略.{0,12}指令"),Regex("system prompt|系统提示词"))
    }
}

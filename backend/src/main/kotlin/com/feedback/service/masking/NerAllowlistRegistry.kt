package com.feedback.service.masking

import com.feedback.repository.masking.NerAllowlistRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class NerAllowlistInput(val term:String,val type:String,val reason:String)

@Service
class NerAllowlistRegistry(private val repository: NerAllowlistRepository) {
    fun activeTerms(): List<String> = repository.activeTerms()
    fun list(): List<Map<String, Any?>> = repository.all().map { row -> linkedMapOf(
        "id" to row.id, "term" to row.term, "type" to row.type, "status" to row.status,
        "listVersion" to row.listVersion, "reason" to row.reason, "lifecycleVersion" to row.lifecycleVersion,
        "createdAt" to row.createdAt, "retiredAt" to row.retiredAt
    ) }

    @Transactional
    fun add(input: NerAllowlistInput, actor: Long): Map<String, Any> {
        val term = input.term.trim(); validate(term, input.type)
        val reason=input.reason.trim();if(reason.length !in 2..500) maskingInvalid("NER_ALLOWLIST_REASON_REQUIRED")
        val next = repository.nextVersion()
        return mapOf("id" to repository.add(term, input.type, next, reason, actor), "listVersion" to next)
    }

    @Transactional
    fun retire(id: Long, expectedVersion: Long, reason: String, actor: Long): Map<String, Any> {
        if(reason.trim().length !in 2..500) maskingInvalid("NER_ALLOWLIST_REASON_REQUIRED")
        if(!repository.retire(id,expectedVersion,reason.trim())) maskingInvalid("NER_ALLOWLIST_CONFLICT_OR_NOT_ACTIVE")
        return mapOf("id" to id, "retired" to true)
    }

    private fun validate(term:String,type:String){
        if(type !in setOf("PRODUCT","FUNCTION","DOMAIN_TERM")) maskingInvalid("NER_ALLOWLIST_TYPE_INVALID")
        if(term.length !in 2..100||term.contains(Regex("[\\r\\n\\t]"))) maskingInvalid("NER_ALLOWLIST_TERM_INVALID")
        if(term.contains(Regex("[0-9]{6,}|@|(?i)password|token|secret|api[_-]?key"))) maskingInvalid("NER_ALLOWLIST_SENSITIVE_TERM_FORBIDDEN")
        if(term.any{it in "*+?{}[]()|\\^$"}) maskingInvalid("NER_ALLOWLIST_REGEX_FORBIDDEN")
    }
}

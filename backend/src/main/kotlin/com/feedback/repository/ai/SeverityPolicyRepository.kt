package com.feedback.repository.ai

import com.feedback.mapper.ai.SeverityPolicyMapper
import org.springframework.stereotype.Repository

@Repository
class SeverityPolicyRepository(private val mapper: SeverityPolicyMapper) {
    fun activeJson(): String? = mapper.findActiveJson()
}

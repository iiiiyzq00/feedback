package com.feedback.service.ai

import org.springframework.stereotype.Service
import com.feedback.repository.knowledge.KnowledgeSearchRepository

@Service
class KnowledgeSearchSkillService(private val repository: KnowledgeSearchRepository,private val governed:GovernedSkillExecutionService){
    fun search(stableKey:String,issueType:String?,module:String?,limit:Int=3):List<Map<String,Any?>>{
        val issue=issueType.orEmpty().trim();val mod=module.orEmpty().trim()
        val rows=if(issue.isBlank()&&mod.isBlank()) emptyList() else repository.search(issue, mod, limit.coerceIn(1,5))
        governed.recordDeterministic("knowledge_search",stableKey,mapOf("issueType" to issue,"module" to mod,"publishedOnly" to true),mapOf("matches" to rows))
        return rows
    }
}

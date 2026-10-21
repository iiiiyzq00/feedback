package com.feedback.repository.query

import com.feedback.mapper.query.*
import com.feedback.model.po.query.*
import com.feedback.model.auth.DataAccessScope
import org.springframework.stereotype.Repository

@Repository
class SimilarFeedbackRepository(
    private val sources: SimilarFeedbackSourceMapper,
    private val contents: SimilarFeedbackContentMapper,
    private val ai: SimilarFeedbackAiMapper
) {
    fun visibleSourceIds(scope: DataAccessScope): Set<Long> {
        if (scope.channels.isEmpty() || scope.modules?.isEmpty() == true || scope.regions?.isEmpty() == true) return emptySet()
        val result = linkedSetOf<Long>(); var after = 0L
        while (true) {
            val candidates = sources.findVisibleAfter(after, scope.channels, scope.regions, BATCH)
            if (candidates.isEmpty()) break
            val safe = contents.findSafe(candidates, scope.modules)
            if (safe.isNotEmpty()) result += ai.findVectorSynced(safe)
            after = candidates.last()
            if (candidates.size < BATCH) break
        }
        return result
    }
    companion object { private const val BATCH = 500 }
}

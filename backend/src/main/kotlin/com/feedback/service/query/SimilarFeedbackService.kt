package com.feedback.service.query

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.service.auth.DataPermissionService
import com.feedback.service.sync.EmbeddingProvider
import com.feedback.service.sync.VectorIndexGateway
import com.feedback.service.sync.VectorSearchFilter
import org.springframework.stereotype.Service
import com.feedback.repository.query.SimilarFeedbackRepository

@Service
class SimilarFeedbackService(
    private val repository: SimilarFeedbackRepository,
    private val vectors: VectorIndexGateway,
    private val embeddings: EmbeddingProvider,
    private val permissions: DataPermissionService
) {
    fun search(query: String, topK: Int): Map<String, Any> {
        val normalized = query.trim()
        if (normalized.length < 2) throw ClientException(ErrorCode.BAD_REQUEST, "SIMILAR_QUERY_TOO_SHORT")
        if (topK !in 1..100) throw ClientException(ErrorCode.BAD_REQUEST, "SIMILAR_TOP_K_INVALID")
        val scope = permissions.scope()
        if (scope.channels.isEmpty()) return response(normalized, topK, emptyList())

        val visible = repository.visibleSourceIds(scope)
        if (visible.isEmpty()) return response(normalized, topK, emptyList())

        val candidates = vectors.search(embeddings.embed(normalized), topK, VectorSearchFilter(sourceIds = visible))
        val items = candidates.map { mapOf("sourceId" to it.sourceId, "score" to it.score) }
        return response(normalized, topK, items)
    }

    private fun response(query: String, topK: Int, items: List<Map<String, Any>>) = mapOf(
        "query" to query, "topK" to topK, "count" to items.size, "items" to items,
        "modelVersion" to embeddings.modelVersion
    )
}

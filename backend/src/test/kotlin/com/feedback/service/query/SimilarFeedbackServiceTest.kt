package com.feedback.service.query

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.service.sync.EmbeddingProvider
import com.feedback.service.sync.VectorCandidate
import com.feedback.service.sync.VectorIndexGateway
import com.feedback.service.sync.VectorSearchFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.feedback.repository.query.SimilarFeedbackRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SimilarFeedbackServiceTest {
    private val repository = mockk<SimilarFeedbackRepository>()
    private val vectors = mockk<VectorIndexGateway>()
    private val embeddings = object : EmbeddingProvider {
        override val modelVersion = "test-embedding"
        override val dimension = 2
        override fun embed(text: String) = listOf(1f, 0f)
    }
    private val permissions = mockk<DataPermissionService>()
    private val service = SimilarFeedbackService(repository, vectors, embeddings, permissions)

    @Test
    fun `vector search is restricted to database-visible source ids`() {
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), setOf("CAMERA_MIC"), null)
        every { repository.visibleSourceIds(any()) } returns setOf(11L, 22L)
        val filter = slot<VectorSearchFilter>()
        every { vectors.search(any(), 10, capture(filter)) } returns listOf(VectorCandidate(22, 0.9f))

        val result = service.search("摄像头崩溃", 10)

        assertEquals(setOf(11L, 22L), filter.captured.sourceIds)
        assertEquals(1, result["count"])
        verify(exactly = 1) { vectors.search(any(), 10, any()) }
    }

    @Test
    fun `empty permission scope fails closed without calling Milvus`() {
        every { permissions.scope() } returns DataAccessScope(emptySet(), emptySet(), emptySet())
        assertEquals(0, service.search("摄像头崩溃", 10)["count"])
        verify(exactly = 0) { vectors.search(any(), any(), any()) }
    }

    @Test
    fun `query and topK boundaries are enforced`() {
        val shortQuery = assertThrows(ClientException::class.java) { service.search("x", 10) }
        assertEquals(ErrorCode.BAD_REQUEST, shortQuery.errorCode)
        assertEquals("SIMILAR_QUERY_TOO_SHORT", shortQuery.reasonCode)

        val invalidTopK = assertThrows(ClientException::class.java) { service.search("摄像头", 101) }
        assertEquals(ErrorCode.BAD_REQUEST, invalidTopK.errorCode)
        assertEquals("SIMILAR_TOP_K_INVALID", invalidTopK.reasonCode)
    }
}

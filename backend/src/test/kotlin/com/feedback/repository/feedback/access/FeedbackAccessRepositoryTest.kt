package com.feedback.repository.feedback.access

import com.feedback.mapper.feedback.access.FeedbackAccessMapper
import com.feedback.model.result.feedback.FeedbackAccessResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedbackAccessRepositoryTest {
    private val mapper = mockk<FeedbackAccessMapper>()
    private val repository = FeedbackAccessRepository(mapper)

    @Test
    fun `online access facts use exactly one query`() {
        every { mapper.findBySourceId(11) } returns FeedbackAccessResult(
            11, "JIRA", "PRODUCTION", "COURSE", "GD", "SZ", "PASS", false, tenantKey = "TENANT_B"
        )

        val result = repository.findBySourceId(11)

        // 租户刻意用一个**非默认**值：仓储这一层的职责就是把 SQL 现算出来的租户原样送到守卫，
        // 用 'DEFAULT' 就分不清「真的读到了这一列」和「恰好等于兜底值」。
        assertEquals(
            FeedbackAccessFacts(11, "JIRA", "PRODUCTION", "COURSE", "GD", "SZ", "PASS", false, tenant = "TENANT_B"),
            result
        )
        verify(exactly = 1) { mapper.findBySourceId(11) }
    }
}

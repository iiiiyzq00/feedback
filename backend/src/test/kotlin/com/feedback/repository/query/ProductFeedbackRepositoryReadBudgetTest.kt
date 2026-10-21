package com.feedback.repository.query

import com.feedback.mapper.query.ProductFeedbackAuditMapper
import com.feedback.mapper.query.ProductFeedbackContentMapper
import com.feedback.mapper.query.ProductFeedbackEventMapper
import com.feedback.mapper.query.ProductFeedbackRequestMapper
import com.feedback.mapper.query.ProductFeedbackSourceMapper
import com.feedback.mapper.query.ProductFeedbackUserMapper
import com.feedback.model.po.query.ProductFeedbackRequestPo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ProductFeedbackRepositoryReadBudgetTest {
    private val requests = mockk<ProductFeedbackRequestMapper>()
    private val repository = ProductFeedbackRepository(
        requests,
        mockk<ProductFeedbackEventMapper>(),
        mockk<ProductFeedbackUserMapper>(),
        mockk<ProductFeedbackSourceMapper>(),
        mockk<ProductFeedbackContentMapper>(),
        mockk<ProductFeedbackAuditMapper>()
    )

    @Test
    fun `read detail does not acquire a row lock`() {
        val row = ProductFeedbackRequestPo(id = 7)
        every { requests.findById(7) } returns row

        assertSame(row, repository.find(7))

        verify(exactly = 1) { requests.findById(7) }
        verify(exactly = 0) { requests.lockById(any()) }
    }
}

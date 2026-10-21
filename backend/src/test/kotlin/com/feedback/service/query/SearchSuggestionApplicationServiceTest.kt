package com.feedback.service.query

import com.feedback.service.query.listprojection.FeedbackListQueryUseCase
import com.feedback.service.query.listprojection.FeedbackListResponse
import com.feedback.service.query.listprojection.FeedbackListRowResponse
import com.feedback.service.query.listprojection.FeedbackListStatsResponse
import com.feedback.common.exception.ClientException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SearchSuggestionApplicationServiceTest {
    private val listReads = mockk<FeedbackListQueryUseCase>()
    private val service = SearchSuggestionApplicationService(listReads)

    @Test
    fun `suggestions trim query use one hundred candidates and cap each channel at two`() {
        val captured = slot<FeedbackListQuery>()
        every { listReads.list(capture(captured)) } returns response(
            listOf(
                row(1, "jira", "Jira 一"),
                row(2, "jira", "Jira 二"),
                row(3, "jira", "Jira 三"),
                row(4, "sdk", "SDK 一")
            ),
            total = 37
        )

        val result = service.suggestions("  崩溃  ", 20)

        assertEquals("崩溃", captured.captured.common.keyword)
        assertEquals(1, captured.captured.page.page)
        assertEquals(100, captured.captured.page.size)
        assertEquals(listOf(1L, 2L, 4L), result.items.map { it.id })
        assertEquals(listOf("jira" to 3, "sdk" to 1), result.channelCounts.map { it.channel to it.count })
        assertEquals(37, result.total)
        assertEquals("崩溃", result.query)
    }

    @Test
    fun `invalid query and limit fail before list lookup`() {
        assertEquals(
            "SEARCH_QUERY_TOO_SHORT",
            assertThrows(ClientException::class.java) { service.suggestions(" x ", 10) }.reasonCode
        )
        assertEquals(
            "SEARCH_LIMIT_INVALID",
            assertThrows(ClientException::class.java) { service.suggestions("valid", 21) }.reasonCode
        )
        verify(exactly = 0) { listReads.list(any()) }
    }

    private fun row(id: Long, channel: String, title: String): FeedbackListRowResponse =
        mockk {
            every { this@mockk.id } returns id
            every { sourceChannel } returns channel
            every { this@mockk.title } returns title
            every { module } returns "COURSE"
        }

    private fun response(records: List<FeedbackListRowResponse>, total: Long) =
        FeedbackListResponse(
            records,
            total,
            1,
            100,
            FeedbackListStatsResponse(total, null, null, null, 2, 0, 0, 0, 0, 0, 0, 0)
        )
}

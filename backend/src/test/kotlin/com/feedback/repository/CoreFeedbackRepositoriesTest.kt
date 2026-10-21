package com.feedback.repository

import com.feedback.model.feedback.FeedbackContent
import com.feedback.model.feedback.FeedbackSource
import com.feedback.mapper.core.CoreFeedbackContentMapper
import com.feedback.mapper.core.CoreFeedbackSourceMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreFeedbackRepositoriesTest {
    @Test
    fun `new source uses generated-key insert and returns the same populated fact`() {
        val mapper = mockk<CoreFeedbackSourceMapper>()
        val repository = FeedbackSourceRepository(mapper)
        val source = FeedbackSource(sourceChannel = "SDK", sourceRecordId = "record-1")
        every { mapper.insert(source) } answers {
            source.id = 91L
            1
        }

        val saved = repository.save(source)

        assertEquals(91L, saved.id)
        verify(exactly = 1) { mapper.insert(source) }
        verify(exactly = 0) { mapper.update(any()) }
    }

    @Test
    fun `existing source is updated without replacing its creation facts`() {
        val mapper = mockk<CoreFeedbackSourceMapper>()
        val repository = FeedbackSourceRepository(mapper)
        val source = FeedbackSource(id = 91L, sourceChannel = "JIRA", sourceRecordId = "JIRA-91")
        every { mapper.update(source) } returns 1

        assertEquals(source, repository.saveAndFlush(source))

        verify(exactly = 1) { mapper.update(source) }
        verify(exactly = 0) { mapper.insert(any()) }
    }

    @Test
    fun `empty source and content id batches do not emit invalid IN clauses`() {
        val sourceMapper = mockk<CoreFeedbackSourceMapper>()
        val contentMapper = mockk<CoreFeedbackContentMapper>()

        assertTrue(FeedbackSourceRepository(sourceMapper).findAllById(emptyList()).isEmpty())
        assertTrue(FeedbackContentRepository(contentMapper).findAllById(emptyList()).isEmpty())

        verify(exactly = 0) { sourceMapper.findByIds(any()) }
        verify(exactly = 0) { contentMapper.findByIds(any()) }
    }

    @Test
    fun `content upsert accepts mysql insert update and unchanged row counts`() {
        val mapper = mockk<CoreFeedbackContentMapper>()
        val repository = FeedbackContentRepository(mapper)
        val rows = listOf(0, 1, 2).map { index -> FeedbackContent(sourceId = index + 1L) }
        rows.forEachIndexed { index, row -> every { mapper.upsert(row) } returns index }

        rows.forEach { assertEquals(it, repository.save(it)) }

        rows.forEach { verify(exactly = 1) { mapper.upsert(it) } }
    }
}

package com.feedback.service.jira

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationArguments

class JiraAssigneeOwnerBackfillRunnerTest {
    private val initializer = mockk<JiraAssigneeOwnerInitializer>()
    private val runner = JiraAssigneeOwnerBackfillRunner(initializer)

    @Test
    fun `uses keyset pages and stops after empty page`() {
        every { initializer.findBackfillPage(0) } returns listOf(11, 12)
        every { initializer.initializeBatch(listOf(11, 12)) } returns JiraOwnerInitializationResult(
            requestedCount = 2,
            initializedSourceIds = listOf(11),
            skippedSourceIds = listOf(12)
        )
        every { initializer.findBackfillPage(12) } returns emptyList()

        runner.run(mockk<ApplicationArguments>())

        verify(exactly = 1) { initializer.findBackfillPage(0) }
        verify(exactly = 1) { initializer.findBackfillPage(12) }
    }

    @Test
    fun `batch failure stops without advancing past failed page`() {
        every { initializer.findBackfillPage(0) } returns listOf(11, 12)
        every { initializer.initializeBatch(listOf(11, 12)) } throws IllegalStateException("WRITE_FAILED")

        runner.run(mockk<ApplicationArguments>())

        verify(exactly = 1) { initializer.findBackfillPage(0) }
        verify(exactly = 0) { initializer.findBackfillPage(12) }
    }
}

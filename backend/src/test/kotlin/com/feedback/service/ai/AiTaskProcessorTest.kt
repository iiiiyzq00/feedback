package com.feedback.service.ai

import com.feedback.common.exception.ClientException
import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.model.po.audit.*
import com.feedback.model.audit.*
import com.feedback.model.channel.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import com.feedback.model.po.config.*
import com.feedback.model.config.*
import com.feedback.model.po.feedback.*
import com.feedback.model.feedback.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import com.feedback.model.po.jira.*
import com.feedback.model.jira.*
import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import com.feedback.model.po.sdk.*
import com.feedback.model.sdk.*
import com.feedback.repository.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional

class AiTaskProcessorTest {
    @Test
    fun `rule cascade classifies pass feedback and marks index work pending`() {
        val fixture = Fixture("AI助教点击后闪退")
        val result = fixture.processor.process(1)

        assertEquals("BUG", result.category)
        assertEquals("S1", result.severity)
        verify { fixture.results.save(match {
            it.sourceId == 7L && it.aiCategory == "BUG" && it.reviewStatus == "PENDING" &&
                it.searchIndexStatus == "PENDING" && it.vectorIndexStatus == "PENDING" &&
                it.aiApplication == "SDK_APPLICATION" && it.aiFunctionModule == "UNKNOWN" &&
                it.aiSentiment == "NEUTRAL" && it.aiRiskType == "OPERATIONAL_HIGH_RISK" &&
                it.recommendedTeam == "产品研发团队" && it.dataSources == "[\"SDK\"]"
        }) }
        assertEquals("COMPLETED", fixture.task.taskStatus)
        verify(exactly = 0) { fixture.contents.save(any()) }
        verify(exactly = 0) { fixture.llm.classify(any(), any(), any()) }
    }

    @Test
    fun `not required ai result fills missing canonical category and severity`() {
        val fixture = Fixture("页面加载卡顿")
        val result = fixture.processor.process(1)

        assertEquals("BUG", result.category)
        assertEquals("S2", result.severity)
        verify(exactly = 1) { fixture.contents.save(match {
            it.sourceId == 7L && it.issueCategory == "BUG" && it.severityLevel == "S2"
        }) }
        verify { fixture.results.save(match { it.reviewStatus == "NOT_REQUIRED" }) }
    }

    @Test
    fun `third attempt degrades without calling llm`() {
        val fixture = Fixture("想了解一下这个功能")
        val result = fixture.processor.process(1, forceFallback = true)

        assertTrue(result.degraded)
        assertEquals("rule-fallback-v2", result.modelVersion)
        assertEquals("AI_DEGRADED", fixture.task.errorDetail)
        verify(exactly = 0) { fixture.llm.classify(any(), any(), any()) }
    }

    @Test
    fun `non pass feedback is rejected before ai`() {
        val fixture = Fixture("需要复核", maskingStatus = "REVIEW_NEEDED")
        assertThrows(ClientException::class.java) { fixture.processor.process(1) }
        verify(exactly = 0) { fixture.results.save(any()) }
    }

    private class Fixture(description: String, maskingStatus: String = "PASS") {
        val tasks = mockk<AiTaskRepository>()
        val sources = mockk<FeedbackSourceRepository>()
        val contents = mockk<FeedbackContentRepository>()
        val results = mockk<FeedbackAiRepository>(relaxed = true)
        val llm = mockk<LlmClient>()
        val task = AiTask(id = 1, sourceId = 7, skillCode = "feedback_classify", promptVersion = "classify-v1", taskStatus = "PROCESSING")
        val processor: AiTaskProcessor
        init {
            every { tasks.findById(1) } returns Optional.of(task)
            every { tasks.save(any()) } answers { firstArg() }
            every { sources.findById(7) } returns Optional.of(FeedbackSource(id = 7, sourceChannel = "SDK", sourceRecordId = "x"))
            every { contents.findById(7) } returns Optional.of(FeedbackContent(sourceId = 7, description = description, maskingStatus = maskingStatus))
            every { contents.save(any()) } answers { firstArg() }
            every { results.save(any()) } answers { firstArg() }
            every { llm.classify(any(), any(), any()) } returns AiClassification(
                "LLM summary", "CONSULT", "S3", "NEUTRAL", BigDecimal("0.900000"), "llm", emptyList(), "test-llm"
            )
            processor = AiTaskProcessor(tasks, sources, contents, results, RuleClassifier(), llm, jacksonObjectMapper())
        }
    }
}

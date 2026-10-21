package com.feedback.service.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.feedback.repository.ai.AiQuotaRepository
import java.math.BigDecimal

class OpenAiResponsesLlmClientTest {
    private lateinit var server: MockWebServer

    @BeforeEach fun start() { server = MockWebServer().also { it.start() } }
    @AfterEach fun stop() { server.shutdown() }

    @Test fun `classification uses OpenAI Responses API and structured output`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""
            {"id":"resp_123","output":[{"type":"message","content":[{"type":"output_text","text":"{\"summary\":\"视频无法播放\",\"category\":\"BUG\",\"severity\":\"S2\",\"sentiment\":\"NEGATIVE\",\"confidence\":0.91,\"explanation\":\"播放失败\",\"evidence_codes\":[\"PLAYBACK_FAILURE\"]}"}]}],"usage":{"input_tokens":31,"output_tokens":22}}
        """.trimIndent()))
        val result = client("test-key-abcdefghijklmnopqrstuvwxyz", "approved-openai-model")
            .classify("播放失败", "点击课程后黑屏", "STATION_FEEDBACK")

        assertEquals("视频无法播放", result.summary)
        assertEquals(31, result.inputTokens)
        assertEquals("resp_123", result.providerRequestId)
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer test-key-abcdefghijklmnopqrstuvwxyz", request.getHeader("Authorization"))
        val body = jacksonObjectMapper().readTree(request.body.readUtf8())
        assertEquals("json_schema", body.path("text").path("format").path("type").asText())
        assertTrue(body.path("text").path("format").path("strict").asBoolean())
    }

    @Test fun `missing deployment credentials fail before network call`() {
        assertThrows(AiTransientException::class.java) { client("", "").classify(null, "安全正文", "JIRA") }
        assertEquals(0, server.requestCount)
    }

    @Test fun `cluster decision selects only a recalled candidate`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""
            {"id":"resp_cluster","output_text":"{\"recommended_action\":\"LINK_EXISTING\",\"selected_cluster_ref\":\"cluster:2\",\"canonical_symptom\":\"课程视频播放后黑屏\",\"explanation\":\"现象、对象和触发动作一致\",\"evidence_refs\":[\"source:1\",\"cluster:2\"],\"risk_signals\":[],\"confidence\":0.88}","usage":{"input_tokens":41,"output_tokens":29}}
        """.trimIndent()))
        val advice = client("test-key-abcdefghijklmnopqrstuvwxyz", "approved-openai-model")
            .adviseCluster("{\"allowedEvidenceRefs\":[\"source:1\",\"cluster:2\"],\"allowedClusterRefs\":[\"cluster:2\"]}")
        assertEquals("LINK_EXISTING", advice.recommendedAction)
        assertEquals("cluster:2", advice.selectedClusterRef)
        assertEquals("resp_cluster", advice.providerRequestId)
        val body = jacksonObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertTrue(body.path("text").path("format").path("schema").toString().contains("selected_cluster_ref"))
    }

    @Test fun `cluster advisory rejects evidence references absent from input facts`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""
            {"id":"resp_bad_ref","output_text":"{\"recommended_action\":\"CREATE_NEW\",\"selected_cluster_ref\":null,\"canonical_symptom\":\"课程视频播放后黑屏\",\"explanation\":\"与候选现象不同\",\"evidence_refs\":[\"source:999\"],\"risk_signals\":[],\"confidence\":0.70}"}
        """.trimIndent()))
        assertThrows(AiTransientException::class.java) {
            client("test-key-abcdefghijklmnopqrstuvwxyz", "approved-openai-model")
                .adviseCluster("{\"allowedEvidenceRefs\":[\"source:1\"],\"allowedClusterRefs\":[]}")
        }
    }

    private fun client(key: String, model: String) = OpenAiCompatibleLlmClient(
        jacksonObjectMapper(), server.url("/v1").toString().trimEnd('/'), key, model, 5,
        BigDecimal.ZERO, BigDecimal.ZERO, SimpleMeterRegistry(), LlmQuotaGuard(mockk<AiQuotaRepository>(), 0)
    )
}

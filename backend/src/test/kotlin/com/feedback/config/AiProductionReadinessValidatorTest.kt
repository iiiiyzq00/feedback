package com.feedback.config

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.service.ai.SkillPromptService
import io.mockk.mockk
import io.mockk.every
import com.feedback.service.cluster.ClusterAiEvaluationService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.nio.file.Files
import java.security.MessageDigest

class AiProductionReadinessValidatorTest {
    @Test fun `production rejects mutable application version`() {
        val environment = StandardEnvironment().apply { propertySources.addFirst(MapPropertySource("test", valid() + ("spring.application.version" to "dev"))) }
        val error=assertThrows(ServiceException::class.java) {
            AiProductionReadinessValidator(environment, mockk(relaxed = true), mockk(relaxed = true), jacksonObjectMapper()).run(DefaultApplicationArguments())
        }
        assertEquals(ErrorCode.INTERNAL_ERROR,error.errorCode)
        assertEquals("PRODUCTION_APP_VERSION_IMMUTABLE_REQUIRED",error.reasonCode)
    }

    @Test fun `production accepts complete supplier evidence and checks prompts`() {
        val dir = Files.createTempDirectory("ai-prod-prompts")
        Files.writeString(dir.resolve("manifest.json"), "{}")
        val modelDir=Files.createDirectories(dir.resolve("model/onnx"));val model=Files.writeString(modelDir.resolve("model.onnx"),"model-fixture")
        val modelSha=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(model)).joinToString(""){"%02x".format(it)}
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", valid() + mapOf("app.ai.prompt-assets-path" to dir.toAbsolutePath().toString(),"app.ai.embedding.model-path" to dir.resolve("model").toString(),"app.ai.embedding.model-sha256" to modelSha)))
        }
        val prompts = mockk<SkillPromptService>(relaxed = true)
        val evaluation=mockk<ClusterAiEvaluationService>()
        every{evaluation.latest()} returns mapOf("status" to "COMPLETED","summary" to """{"gate":"PASSED","model":"approved-openai-model","sampleCount":100,"evidenceKind":"APPROVED_HISTORICAL","datasetSha256":"${"a".repeat(64)}","annotatedBy":"business-a","reviewedBy":"reviewer-b","approvalRef":"APPROVAL-001"}""")
        AiProductionReadinessValidator(environment, prompts,evaluation,jacksonObjectMapper()).run(DefaultApplicationArguments())
        verify(exactly = 1) { prompts.requireCurrentPrompts(any()) }
    }

    private fun valid() = mapOf<String, Any>(
        "spring.application.version" to "git:0123456789abcdef", "app.ai.prompt-assets-path" to "/tmp/prompts",
        "app.ai.llm.provider" to "OPENAI", "app.ai.llm.base-url" to "https://api.openai.com/v1",
        "app.ai.llm.allowed-hosts" to "api.openai.com", "app.ai.llm.api-key" to "sk-test-abcdefghijklmnopqrstuvwxyz", "app.ai.llm.model" to "approved-openai-model", "app.ai.llm.deployment-id" to "prod-cn-01",
        "app.ai.llm.daily-token-quota" to "1000000", "app.ai.llm.data-not-used-for-training" to "true",
        "app.ai.llm.data-processing-agreement-id" to "DPA-2026-001", "app.ai.llm.provider-retention-days" to "0",
        "app.ai.llm.processing-region" to "CN", "app.ai.require-evaluation-for-invocation" to "true",
        "app.ai.embedding.model-path" to "/tmp/model", "app.ai.embedding.onnx-file" to "onnx/model.onnx",
        "app.ai.embedding.model-version" to "bge-m3-onnx-v1", "app.ai.embedding.model-sha256" to "0".repeat(64)
    )
}

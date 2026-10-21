package com.feedback.config

import com.feedback.service.ai.SkillPromptService
import com.feedback.service.cluster.ClusterAiEvaluationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Component
@Profile("prod")
@Order(10)
class AiProductionReadinessValidator(
    private val environment: Environment,
    private val prompts: SkillPromptService,
    private val clusterEvaluation: ClusterAiEvaluationService,
    private val mapper: ObjectMapper
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val appVersion = required("spring.application.version")
        ensure(appVersion != "dev" && appVersion.matches(Regex("[0-9A-Za-z][0-9A-Za-z._@:-]{6,99}")),"PRODUCTION_APP_VERSION_IMMUTABLE_REQUIRED")
        val assets = Path.of(required("app.ai.prompt-assets-path"))
        ensure(assets.isAbsolute && Files.isRegularFile(assets.resolve("manifest.json")),"PRODUCTION_PROMPT_ASSETS_ABSOLUTE_PATH_REQUIRED")
        val provider = required("app.ai.llm.provider").uppercase()
        val baseUrl = required("app.ai.llm.base-url")
        val endpoint=java.net.URI(baseUrl);val allowedHosts=required("app.ai.llm.allowed-hosts").split(',').map{it.trim().lowercase()}.toSet()
        ensure(provider == "OPENAI" && endpoint.scheme=="https" && endpoint.host?.lowercase() in allowedHosts,"PRODUCTION_OPENAI_HTTPS_ALLOWLIST_REQUIRED")
        val model = required("app.ai.llm.model")
        ensure(model.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{1,99}$")),"PRODUCTION_OPENAI_MODEL_INVALID")
        ensure(required("app.ai.llm.api-key").length >= 20,"PRODUCTION_OPENAI_API_KEY_REQUIRED")
        ensure(required("app.ai.llm.deployment-id") != "local-dev","PRODUCTION_LLM_DEPLOYMENT_ID_REQUIRED")
        ensure(required("app.ai.llm.daily-token-quota").toLong() > 0,"PRODUCTION_LLM_QUOTA_REQUIRED")
        ensure(required("app.ai.llm.data-not-used-for-training").toBooleanStrict(),"PRODUCTION_LLM_NO_TRAINING_ATTESTATION_REQUIRED")
        ensure(required("app.ai.llm.data-processing-agreement-id").length >= 6,"PRODUCTION_LLM_DPA_REQUIRED")
        ensure(required("app.ai.llm.provider-retention-days").toInt() in 0..30,"PRODUCTION_LLM_RETENTION_INVALID")
        ensure(required("app.ai.llm.processing-region").isNotBlank(),"PRODUCTION_LLM_REGION_REQUIRED")
        ensure(required("app.ai.require-evaluation-for-invocation").toBooleanStrict(),"PRODUCTION_PROMPT_EVALUATION_GATE_REQUIRED")
        val embeddingRoot=Path.of(required("app.ai.embedding.model-path"));ensure(embeddingRoot.isAbsolute,"PRODUCTION_BGE_MODEL_ABSOLUTE_PATH_REQUIRED")
        val onnx=embeddingRoot.resolve(required("app.ai.embedding.onnx-file")).normalize();ensure(onnx.startsWith(embeddingRoot)&&Files.isRegularFile(onnx),"PRODUCTION_BGE_MODEL_FILE_REQUIRED")
        val expectedModelSha=required("app.ai.embedding.model-sha256").lowercase();ensure(expectedModelSha.matches(Regex("^[a-f0-9]{64}$")),"PRODUCTION_BGE_MODEL_SHA256_REQUIRED")
        ensure(sha256(onnx)==expectedModelSha,"PRODUCTION_BGE_MODEL_SHA256_MISMATCH")
        ensure(required("app.ai.embedding.model-version").matches(Regex("[A-Za-z0-9._-]{3,100}")),"PRODUCTION_BGE_MODEL_VERSION_REQUIRED")
        prompts.requireCurrentPrompts(setOf("feedback_classify","assistant_answer_generate","root_cause_deep_agent2",
            "root_cause_deep_agent3","impact_assess"))
        val evaluation=clusterEvaluation.latest();ensure(evaluation["status"]=="COMPLETED","PRODUCTION_CLUSTER_EVALUATION_REQUIRED")
        val summary=mapper.readTree(evaluation["summary"].toString())
        ensure(summary.path("gate").asText()=="PASSED"&&summary.path("model").asText()==model&&summary.path("sampleCount").asInt()>=100,"PRODUCTION_CLUSTER_EVALUATION_GATE_FAILED")
        ensure(summary.path("evidenceKind").asText()=="APPROVED_HISTORICAL"&&summary.path("datasetSha256").asText().matches(Regex("^[a-f0-9]{64}$")),"PRODUCTION_CLUSTER_APPROVED_DATASET_REQUIRED")
        ensure(summary.path("annotatedBy").asText().isNotBlank()&&summary.path("reviewedBy").asText().isNotBlank()&&summary.path("annotatedBy").asText()!=summary.path("reviewedBy").asText(),"PRODUCTION_CLUSTER_INDEPENDENT_REVIEW_REQUIRED")
        ensure(summary.path("approvalRef").asText().length>=6,"PRODUCTION_CLUSTER_APPROVAL_REF_REQUIRED")
    }

    private fun required(key: String) = environment.getProperty(key)?.trim().takeUnless { it.isNullOrEmpty() }
        ?: configFailed("PRODUCTION_AI_PROPERTY_REQUIRED")
    private fun ensure(condition:Boolean,reasonCode:String){if(!condition) configFailed(reasonCode)}
    private fun sha256(path:Path):String=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString(""){"%02x".format(it)}
}

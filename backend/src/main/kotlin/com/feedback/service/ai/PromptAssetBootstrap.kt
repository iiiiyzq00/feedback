package com.feedback.service.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

data class PromptAssetManifest(val schemaVersion: String, val assets: List<PromptAsset>)
data class PromptAsset(val skillCode: String, val version: String, val file: String, val sha256: String, val modelConfig: JsonNode)

@Component
@Order(0)
class PromptAssetBootstrap(
    private val mapper: ObjectMapper,
    private val service: SkillPromptService,
    private val environment: Environment,
    @Value("\${app.ai.prompt-assets-path:prompts}") private val assetsPath: String
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val root = Path.of(assetsPath).toAbsolutePath().normalize()
        val manifestPath = root.resolve("manifest.json")
        if (!Files.isRegularFile(manifestPath)) {
            if (environment.activeProfiles.contains("prod")) aiAbort("AI_PROMPT_MANIFEST_REQUIRED:$manifestPath")
            log.warn("Prompt manifest not found at {}; governed LLM workers remain unavailable", manifestPath)
            return
        }
        val manifest = mapper.readValue(Files.readString(manifestPath), PromptAssetManifest::class.java)
        ensureAi(manifest.schemaVersion == "1.0" && manifest.assets.isNotEmpty()) { "PROMPT_MANIFEST_INVALID" }
        manifest.assets.forEach { asset ->
            ensureAi(asset.file.matches(Regex("[a-z0-9_]+/[0-9A-Za-z.-]+\\.txt"))) { "PROMPT_ASSET_PATH_INVALID" }
            val path = root.resolve(asset.file).normalize()
            ensureAi(path.startsWith(root) && Files.isRegularFile(path)) { "PROMPT_ASSET_NOT_FOUND:${asset.file}" }
            val content = Files.readString(path)
            ensureAi(SkillPromptService.sha256(content) == asset.sha256) { "PROMPT_ASSET_SHA_MISMATCH:${asset.skillCode}:${asset.version}" }
            service.bootstrapAsset(asset, content)
        }
        service.requireCurrentPrompts(manifest.assets.map { it.skillCode }.toSet())
        log.info("Verified and loaded {} Git-controlled prompt assets", manifest.assets.size)
    }
}

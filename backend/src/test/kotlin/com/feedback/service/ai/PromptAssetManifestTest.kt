package com.feedback.service.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PromptAssetManifestTest {
    @Test fun `manifest is the exact content source for every prompt`() {
        val root = Path.of("..", "prompts").normalize()
        val mapper = jacksonObjectMapper()
        val manifest = mapper.readValue(Files.readString(root.resolve("manifest.json")), PromptAssetManifest::class.java)
        assertEquals("1.0", manifest.schemaVersion)
        assertEquals(setOf("feedback_classify","assistant_answer_generate","assistant_intent_parse","alert_route","knowledge_search","root_cause_quick","root_cause_deep_agent2","root_cause_deep_agent3","impact_assess","report_generate"), manifest.assets.map { it.skillCode }.toSet())
        assertEquals(manifest.assets.size, manifest.assets.map { it.skillCode to it.version }.toSet().size)
        manifest.assets.forEach { asset ->
            val file = root.resolve(asset.file).normalize()
            assertTrue(file.startsWith(root) && Files.isRegularFile(file))
            assertEquals(asset.sha256, SkillPromptService.sha256(Files.readString(file)))
        }
    }
}

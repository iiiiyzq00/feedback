package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.security.WebPrincipal
import com.feedback.service.ai.*
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/ai-assets")
class AiAssetAdminController(private val service: SkillPromptService, private val evaluations: PromptEvaluationService) {
    @GetMapping("/skills") fun skills() = ApiResponse.success(service.listSkills())

    @PostMapping("/skills")
    fun createSkill(@RequestBody request: CreateSkillRequest, auth: Authentication) =
        ApiResponse.success(mapOf("id" to service.createSkill(request, actor(auth))))

    @PostMapping("/skills/{id}/test") fun testSkill(@PathVariable id: Long, auth: Authentication) =
        ApiResponse.success(service.testSkill(id, actor(auth)))
    @PostMapping("/assets/{id}/approve") fun approve(@PathVariable id: Long, auth: Authentication) =
        ApiResponse.success(service.approve(id, actor(auth)))
    @PostMapping("/skills/{id}/publish") fun publishSkill(@PathVariable id: Long, auth: Authentication) =
        ApiResponse.success(service.publishSkill(id, actor(auth)))
    @PostMapping("/skills/{skillCode}/rollback-skill/{version}")
    fun rollbackSkill(@PathVariable skillCode: String, @PathVariable version: String, auth: Authentication) =
        ApiResponse.success(service.rollbackSkill(skillCode, version, actor(auth)))
    @PostMapping("/skills/{id}/retire") fun retireSkill(@PathVariable id: Long, auth: Authentication) =
        ApiResponse.success(service.retireSkill(id, actor(auth)))

    @GetMapping("/skills/{skillCode}/prompts")
    fun prompts(@PathVariable skillCode: String) = ApiResponse.success(service.listPrompts(skillCode))

    @GetMapping("/skills/{skillCode}/stats")
    fun stats(@PathVariable skillCode: String, @RequestParam(defaultValue = "7") days: Int) =
        ApiResponse.success(service.stats(skillCode, days))

    @PostMapping("/skills/{skillCode}/prompts")
    fun createPrompt(@PathVariable skillCode: String, @RequestBody request: CreatePromptRequest, auth: Authentication) =
        ApiResponse.success(mapOf("id" to service.createPrompt(skillCode, request, actor(auth))))

    @PostMapping("/prompts/{id}/test")
    fun test(@PathVariable id: Long, @RequestBody cases: List<PromptRegressionCase>, auth: Authentication) =
        ApiResponse.success(service.markTested(id, cases, actor(auth)))

    @PostMapping("/prompts/{id}/evaluate")
    fun evaluate(@PathVariable id: Long, @RequestBody request: PromptEvaluationRequest, auth: Authentication) =
        ApiResponse.success(evaluations.evaluate(id, request, actor(auth)))

    @PostMapping("/prompts/{id}/publish")
    fun publish(@PathVariable id: Long, auth: Authentication) = ApiResponse.success(service.publish(id, actor(auth)))

    data class RolloutRequest(val rolloutPercent: Int, val shadowPercent: Int = 0)
    @PostMapping("/prompts/{id}/rollout")
    fun rollout(@PathVariable id: Long, @RequestBody request: RolloutRequest, auth: Authentication) =
        ApiResponse.success(service.configureRollout(id, request.rolloutPercent, request.shadowPercent, actor(auth)))

    data class EmergencyRequest(val reason: String)
    @PostMapping("/skills/{skillCode}/emergency-rollback")
    fun emergency(@PathVariable skillCode: String, @RequestBody request: EmergencyRequest, auth: Authentication) =
        ApiResponse.success(service.emergencyRollback(skillCode, actor(auth), request.reason))

    @PostMapping("/skills/{skillCode}/rollback/{version}")
    fun rollback(@PathVariable skillCode: String, @PathVariable version: String, auth: Authentication) =
        ApiResponse.success(service.rollback(skillCode, version, actor(auth)))

    private fun actor(auth: Authentication) = (auth.principal as WebPrincipal).userId
}

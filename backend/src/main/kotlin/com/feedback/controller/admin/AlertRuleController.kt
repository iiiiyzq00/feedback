package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.service.alert.AlertEngine
import com.feedback.service.alert.AlertRuleInput
import com.feedback.service.alert.AlertRuleService
import com.feedback.service.alert.AlertLifecycleService
import com.feedback.service.alert.SilenceInput
import com.feedback.service.alert.*
import java.time.LocalDateTime
import org.springframework.web.bind.annotation.*

data class ToggleRuleRequest(val enabled:Boolean)
data class GovernanceReasonRequest(val reason:String)

@RestController
@RequestMapping("/api/v1/admin/alert-rules")
class AlertRuleController(private val rules:AlertRuleService, private val engine:AlertEngine, private val lifecycle: AlertLifecycleService, private val oncall:OncallScheduleService,
    private val production:AlertProductionGovernanceService,private val providerReceipts:NotificationProviderReceiptService,private val isolatedTests:AlertRuleIsolatedTestService,private val idempotencyConflicts:IdempotencyConflictService,private val versionReleases:VersionReleaseFactService,private val qualityFacts:AlertQualityFactService,private val securitySignals:SecuritySignalService,private val acceptance:AlertRuleAcceptanceService,private val loopHealth:AlertLoopHealthService) {
    @GetMapping fun list() = ApiResponse.success(rules.list())
    @GetMapping("/governance-catalog") fun governanceCatalog() = ApiResponse.success(rules.governanceCatalog())
    @GetMapping("/loop-health") fun loopHealth() = ApiResponse.success(loopHealth.health())
    @GetMapping("/{id}/activation-gaps") fun activationGaps(@PathVariable id:Long)=ApiResponse.success(production.activationGaps(id))
    @PostMapping("/{id}/publish") fun publish(@PathVariable id:Long,@RequestBody input:AlertRulePublishInput)=ApiResponse.success(production.publishShadow(id,input))
    @GetMapping("/contracts") fun contracts()=ApiResponse.success(production.contracts())
    @PutMapping("/contracts") fun saveContract(@RequestBody input:AlertContractInput)=ApiResponse.success(production.saveContract(input))
    @PostMapping("/contracts/{code}/validate") fun validateContract(@PathVariable code:String,@RequestBody input:GovernanceValidationInput)=ApiResponse.success(production.validateContract(code,input))
    @PostMapping("/contracts/{code}/approve") fun approveContract(@PathVariable code:String)=ApiResponse.success(production.approveContract(code))
    @PostMapping("/contracts/{code}/suspend") fun suspendContract(@PathVariable code:String,@RequestBody input:GovernanceReasonRequest)=ApiResponse.success(production.suspendContract(code,input.reason))
    @GetMapping("/providers") fun providers()=ApiResponse.success(production.providers())
    @PostMapping("/providers/validate") fun validateProvider(@RequestBody input:ProviderAcceptanceInput)=ApiResponse.success(production.validateProvider(input))
    @PostMapping("/providers/{id}/approve") fun approveProvider(@PathVariable id:Long)=ApiResponse.success(production.approveProvider(id))
    @PostMapping("/providers/{id}/suspend") fun suspendProvider(@PathVariable id:Long,@RequestBody input:GovernanceReasonRequest)=ApiResponse.success(production.suspendProvider(id,input.reason))
    @GetMapping("/providers/receipts") fun providerReceipts(@RequestParam(required=false) provider:String?,@RequestParam(required=false) channel:String?,@RequestParam(defaultValue="100") limit:Int)=ApiResponse.success(providerReceipts.receipts(provider,channel,limit))
    @PostMapping("/governance-catalog/{code}/implemented") fun implemented(@PathVariable code:String,@RequestBody input:RuleImplementationInput)=ApiResponse.success(production.markRuleImplemented(code,input))
    @PostMapping fun create(@RequestBody input:AlertRuleInput) = ApiResponse.success(mapOf("id" to rules.create(input)))
    @PutMapping("/{id}") fun update(@PathVariable id:Long,@RequestBody input:AlertRuleInput) = ApiResponse.success(mapOf("id" to rules.update(id,input)))
    @PutMapping("/{id}/toggle") fun toggle(@PathVariable id:Long,@RequestBody input:ToggleRuleRequest):ApiResponse<Map<String,Boolean>> { rules.toggle(id,input.enabled); return ApiResponse.success(mapOf("enabled" to input.enabled)) }
    @PostMapping("/evaluate") fun evaluate() = ApiResponse.success(mapOf("triggered" to engine.evaluateAll()))
    @PostMapping("/escalate") fun escalate() = ApiResponse.success(mapOf("escalated" to lifecycle.escalate()))
    @GetMapping("/silences") fun silences() = ApiResponse.success(lifecycle.listSilences())
    @PostMapping("/silences") fun createSilence(@RequestBody input: SilenceInput) = ApiResponse.success(lifecycle.createSilence(input))
    @DeleteMapping("/silences/{id}") fun deleteSilence(@PathVariable id:Long):ApiResponse<Map<String,Boolean>> { lifecycle.deleteSilence(id); return ApiResponse.success(mapOf("deleted" to true)) }
    @PutMapping("/reorder") fun reorder(@RequestBody input:ReorderRulesInput)=ApiResponse.success(rules.reorder(input))
    @PostMapping("/test") fun test(@RequestBody input:TestRuleInput)=ApiResponse.success(rules.test(input))
    @GetMapping("/test-runs") fun testRuns(@RequestParam(defaultValue="50") limit:Int)=ApiResponse.success(rules.testRuns(limit))
    @PostMapping("/test-runs/replay") fun replay(@RequestBody input:ReplayRuleInput)=ApiResponse.success(isolatedTests.replay(input))
    @PostMapping("/test-runs/shadow") fun shadow(@RequestBody input:ShadowRuleInput)=ApiResponse.success(isolatedTests.shadow(input))
    @PostMapping("/test-runs/drill") fun drill(@RequestBody input:DrillRuleInput)=ApiResponse.success(isolatedTests.drill(input))
    @GetMapping("/test-runs/drills") fun drills(@RequestParam(defaultValue="50") limit:Int)=ApiResponse.success(isolatedTests.drills(limit))
    @PostMapping("/test-runs/drills/{id}/acknowledge") fun acknowledgeDrill(@PathVariable id:Long,@RequestBody input:DrillDispositionInput)=ApiResponse.success(isolatedTests.acknowledgeDrill(id,input))
    @PostMapping("/test-runs/drills/{id}/resolve") fun resolveDrill(@PathVariable id:Long,@RequestBody input:DrillDispositionInput)=ApiResponse.success(isolatedTests.resolveDrill(id,input))
    @GetMapping("/idempotency-conflicts") fun conflicts(@RequestParam(required=false) status:String?)=ApiResponse.success(idempotencyConflicts.list(status))
    @PostMapping("/idempotency-conflicts/{id}/resolve") fun resolveConflict(@PathVariable id:Long,@RequestBody input:ResolveIdempotencyConflictInput)=ApiResponse.success(idempotencyConflicts.resolve(id,input))
    @GetMapping("/incorrect-dedup") fun incorrectDedup()=ApiResponse.success(qualityFacts.incorrectDedupEvents())
    @PostMapping("/incorrect-dedup") fun confirmIncorrectDedup(@RequestBody input:IncorrectDedupInput)=ApiResponse.success(qualityFacts.confirmIncorrectDedup(input))
    @PostMapping("/incorrect-dedup/{id}/repair") fun repairIncorrectDedup(@PathVariable id:Long,@RequestBody input:RepairIncorrectDedupInput)=ApiResponse.success(qualityFacts.repairIncorrectDedup(id,input))
    @GetMapping("/security-signals") fun securitySignals()=ApiResponse.success(securitySignals.list())
    @PostMapping("/security-signals") fun reportSecuritySignal(@RequestBody input:SecuritySignalInput)=ApiResponse.success(securitySignals.report(input))
    @PostMapping("/security-signals/{id}/conclude") fun concludeSecuritySignal(@PathVariable id:Long,@RequestBody input:SecuritySignalConclusionInput)=ApiResponse.success(securitySignals.conclude(id,input))
    @GetMapping("/acceptance") fun acceptance(@RequestParam(required=false) ruleId:Long?)=ApiResponse.success(acceptance.list(ruleId))
    @PostMapping("/acceptance/validate") fun validateAcceptance(@RequestBody input:AlertAcceptanceInput)=ApiResponse.success(acceptance.validate(input))
    @PostMapping("/acceptance/{id}/approve") fun approveAcceptance(@PathVariable id:Long)=ApiResponse.success(acceptance.approve(id))
    @GetMapping("/version-releases") fun versionReleases()=ApiResponse.success(versionReleases.list())
    @PostMapping("/version-releases") fun saveVersionRelease(@RequestBody input:VersionReleaseFactInput)=ApiResponse.success(versionReleases.save(input))
    @GetMapping("/oncall/teams") fun teams()=ApiResponse.success(oncall.teams())
    @GetMapping("/oncall/members") fun members(@RequestParam(required=false) team:String?)=ApiResponse.success(oncall.members(team))
    @GetMapping("/oncall/eligible-users") fun eligibleUsers()=ApiResponse.success(oncall.eligibleUsers())
    @PostMapping("/oncall/members") fun saveMember(@RequestBody input:TeamMemberInput)=ApiResponse.success(oncall.saveMember(input))
    @DeleteMapping("/oncall/members/{id}") fun removeMember(@PathVariable id:Long)=ApiResponse.success(oncall.removeMember(id))
    @GetMapping("/oncall/calendar") fun calendar(@RequestParam team:String,@RequestParam start:LocalDateTime,@RequestParam end:LocalDateTime)=ApiResponse.success(oncall.calendar(team,start,end))
    @PostMapping("/oncall") fun createOncall(@RequestBody input:OncallInput)=ApiResponse.success(oncall.create(input))
    @DeleteMapping("/oncall/{id}") fun cancelOncall(@PathVariable id:Long,@RequestParam version:Long)=ApiResponse.success(oncall.cancel(id,version))
}

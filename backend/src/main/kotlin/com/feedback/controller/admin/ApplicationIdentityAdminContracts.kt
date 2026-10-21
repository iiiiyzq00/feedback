package com.feedback.controller.admin

import com.feedback.model.applicationidentity.ApplicationAliasRebindCommand
import com.feedback.model.applicationidentity.ApplicationAliasRebindResult
import com.feedback.model.applicationidentity.ApplicationIdentityAliasItem
import com.feedback.model.applicationidentity.ApplicationIdentityDiagnostics
import com.feedback.model.applicationidentity.ApplicationIdentityListItem
import com.feedback.model.applicationidentity.ApplicationIdentityPage
import com.feedback.model.applicationidentity.ApplicationReviewedBindingCommand
import com.feedback.model.applicationidentity.ApplicationReviewedBindingResult
import com.feedback.model.applicationidentity.ApplicationReviewedBindingDecision
import com.feedback.model.applicationidentity.ApplicationIdentityShadowBatchResult
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.AssertTrue

private const val UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"

/** 影子批次 HTTP 请求；游标与批量上限由 Bean Validation 和 Service 双重校验。 */
data class ApplicationIdentityShadowBatchRequest(
    @field:Min(0)
    @field:Schema(description = "稳定 source ID 游标；首次执行传 0", example = "0")
    val afterSourceId: Long,
    @field:Min(1)
    @field:Max(100)
    @field:Schema(description = "本次最多处理的反馈数，最大 100", example = "100")
    val limit: Int,
    @field:NotBlank
    @field:Schema(description = "服务端已发布的应用身份解析规则版本", example = "application-identity-v1")
    val resolutionRuleVersion: String
)

/** 人工重绑 HTTP 请求；目标 identity UUID 位于路径参数。 */
data class ApplicationAliasRebindRequest(
    @field:NotBlank
    @field:Pattern(regexp = UUID_PATTERN)
    @field:Schema(description = "待重绑 alias 的稳定 UUID", example = "f71f22b6-6d3b-4b2c-a77f-b7cedf938319")
    val aliasId: String,
    @field:Min(1)
    @field:Schema(description = "客户端读取到的 alias 版本，用于 CAS", example = "1")
    val expectedAliasVersion: Long,
    @field:Min(1)
    @field:Schema(description = "客户端读取到的目标应用映射版本，用于 CAS", example = "3")
    val expectedTargetVersion: Long,
    @field:NotBlank
    @field:Pattern(regexp = UUID_PATTERN)
    @field:Schema(description = "UUID 幂等请求号；同号异命令返回 409", example = "4c55dbd2-983c-46b6-a197-2cf6eecb6542")
    val requestId: String,
    @field:Size(min = 5, max = 500)
    @field:Schema(
        description = "跨渠道应用映射的业务确认原因，不得包含敏感正文",
        example = "产品确认两个渠道均属于阅读器正式应用"
    )
    val reason: String
) {
    /** 将已校验 HTTP 请求转换为内部重绑命令，目标 ID 来自路径参数。 */
    fun command(targetApplicationId: String) = ApplicationAliasRebindCommand(
        targetApplicationId,
        aliasId,
        expectedAliasVersion,
        expectedTargetVersion,
        requestId,
        reason
    )
}

/** reviewed binding HTTP 请求；decision 保持 v1 字符串编码。 */
data class ApplicationReviewedBindingRequest(
    @field:NotBlank
    @field:Size(max = 200)
    @field:Schema(description = "已由授权审核人确认的候选展示名；仅用于定位来源组，不作为 alias")
    val candidateDisplayName: String,
    @field:Min(1)
    @field:Schema(description = "审核时该候选组的预期来源记录数，用于防止数据漂移")
    val expectedSourceCount: Long,
    @field:NotBlank
    @field:Pattern(regexp = "^(NEW_APPLICATION|ALIAS_OF_EXISTING)$")
    val decision: String,
    @field:Pattern(regexp = UUID_PATTERN)
    val targetApplicationId: String? = null,
    @field:Min(1)
    val expectedTargetVersion: Long? = null,
    @field:NotBlank
    @field:Pattern(regexp = UUID_PATTERN)
    val requestId: String,
    @field:Size(min = 5, max = 500)
    val reason: String
) {
    /** 校验 reviewed binding 决策与可空目标字段的 HTTP 组合契约。 */
    @AssertTrue(message = "APPLICATION_REVIEW_TARGET_CONTRACT_INVALID")
    fun isTargetContractValid(): Boolean = when (decision) {
        "NEW_APPLICATION" -> targetApplicationId == null && expectedTargetVersion == null
        "ALIAS_OF_EXISTING" -> targetApplicationId != null && expectedTargetVersion != null
        else -> true
    }

    /** 在 HTTP 边界将稳定字符串严格转换为内部决策枚举。 */
    fun command() = ApplicationReviewedBindingCommand(
        candidateDisplayName,
        expectedSourceCount,
        ApplicationReviewedBindingDecision.fromCode(decision),
        targetApplicationId,
        expectedTargetVersion,
        requestId,
        reason
    )
}

/** 影子批次 HTTP 响应，不包含 alias 或来源正文。 */
data class ApplicationIdentityShadowBatchResponse(
    @field:Schema(description = "本批读取的反馈数") val processedCount: Int,
    @field:Schema(description = "已解析应用身份数") val resolvedCount: Int,
    @field:Schema(description = "缺少权威应用别名数") val unresolvedCount: Int,
    @field:Schema(description = "别名指向多个身份的歧义数") val ambiguousCount: Int,
    @field:Schema(description = "本批保守新建的应用身份数") val createdIdentityCount: Int,
    @field:Schema(description = "本批新增的 alias 数") val createdAliasCount: Int,
    @field:Schema(description = "下一批稳定 source ID 游标") val nextSourceId: Long,
    @field:Schema(description = "是否可能还有下一批；不代表已自动调度") val mayHaveMore: Boolean,
    @field:Schema(description = "实际使用的解析规则版本") val resolutionRuleVersion: String
)

/** 管理员 identity 列表项响应。 */
data class ApplicationIdentityItemResponse(
    @field:Schema(description = "应用稳定 UUID") val applicationId: String,
    @field:Schema(description = "应用展示名，不作为自动合并依据") val displayName: String,
    @field:Schema(description = "应用生命周期状态", example = "ACTIVE") val lifecycleStatus: String,
    @field:Schema(description = "alias 集合 CAS 版本") val mappingVersion: Long
)

/** identity UUID 游标页响应。 */
data class ApplicationIdentityPageResponse(
    @field:Schema(description = "当前页应用身份") val items: List<ApplicationIdentityItemResponse>,
    @field:Schema(description = "下一页游标；为空表示当前无下一页") val nextCursor: String?
)

/** 管理员 alias 历史响应；规范化 alias 仅在此授权入口暴露。 */
data class ApplicationIdentityAliasResponse(
    @field:Schema(description = "alias 稳定 UUID") val aliasId: String,
    @field:Schema(description = "来源渠道") val sourceChannel: String,
    @field:Schema(description = "alias 类型") val aliasType: String,
    @field:Schema(description = "规范化 alias；仅管理员可见") val normalizedAlias: String,
    @field:Schema(description = "alias CAS 版本") val aliasVersion: Long,
    @field:Schema(description = "绑定版本状态") val bindingStatus: String,
    @field:Schema(description = "本版本绑定的应用 UUID") val applicationId: String,
    @field:Schema(description = "自动同源或人工重绑证据类型") val evidenceType: String,
    @field:Schema(description = "人工重绑请求号；自动版本为空") val requestId: String?
)

/** 活动 alias 类型计数响应。 */
data class ApplicationAliasCountResponse(
    @field:Schema(description = "alias 类型") val aliasType: String,
    @field:Schema(description = "活动 alias 数") val count: Long
)

/** 应用身份诊断响应；不返回敏感文本。 */
data class ApplicationIdentityDiagnosticsResponse(
    @field:Schema(description = "影子主问题事实总数") val totalShadowFacts: Long,
    @field:Schema(description = "已解析数") val resolvedCount: Long,
    @field:Schema(description = "未解析数") val unresolvedCount: Long,
    @field:Schema(description = "歧义数") val ambiguousCount: Long,
    @field:Schema(description = "各类型活动 alias 数") val activeAliasCounts: List<ApplicationAliasCountResponse>,
    @field:Schema(description = "当前应用身份解析规则版本") val resolutionRuleVersion: String
)

/** requestId 幂等人工重绑响应。 */
data class ApplicationAliasRebindResponse(
    @field:Schema(description = "幂等请求号") val requestId: String,
    @field:Schema(description = "alias 稳定 UUID") val aliasId: String,
    @field:Schema(description = "重绑后的 alias 版本") val aliasVersion: Long,
    @field:Schema(description = "目标应用稳定 UUID") val targetApplicationId: String,
    @field:Schema(description = "命令完成时的目标应用映射版本") val targetMappingVersion: Long,
    @field:Schema(description = "是否为历史结果重放") val replayed: Boolean
)

/** reviewed binding HTTP 响应。 */
data class ApplicationReviewedBindingResponse(
    val requestId: String,
    val decision: String,
    val applicationId: String,
    val aliasId: String,
    val aliasVersion: Long,
    val targetMappingVersion: Long,
    val affectedSourceCount: Long,
    val replayed: Boolean
)

/** 将内部影子结果转换为兼容 v1 HTTP 响应。 */
fun ApplicationIdentityShadowBatchResult.response() = ApplicationIdentityShadowBatchResponse(
    processedCount, resolvedCount, unresolvedCount, ambiguousCount, createdIdentityCount, createdAliasCount,
    nextSourceId, mayHaveMore, resolutionRuleVersion
)

/** 将内部 identity 页转换为 HTTP 游标页。 */
fun ApplicationIdentityPage.response() = ApplicationIdentityPageResponse(
    items.map(ApplicationIdentityListItem::response), nextCursor
)

/** 在 HTTP 边界将生命周期枚举转换为稳定 code。 */
fun ApplicationIdentityListItem.response() = ApplicationIdentityItemResponse(
    applicationId, displayName, lifecycleStatus.code, mappingVersion
)

/** 在 HTTP 边界转换 alias 渠道、类型、状态与证据 code。 */
fun ApplicationIdentityAliasItem.response() = ApplicationIdentityAliasResponse(
    aliasId,
    sourceChannel.code,
    aliasType.code,
    normalizedAlias,
    aliasVersion,
    bindingStatus.code,
    applicationId,
    evidenceType.code,
    requestId
)

/** 将强类型 alias 计数转换为兼容诊断响应。 */
fun ApplicationIdentityDiagnostics.response() = ApplicationIdentityDiagnosticsResponse(
    totalShadowFacts,
    resolvedCount,
    unresolvedCount,
    ambiguousCount,
    activeAliasCounts.map { ApplicationAliasCountResponse(it.aliasType.code, it.count) },
    resolutionRuleVersion
)

/** 将幂等重绑结果原样映射为 HTTP 响应。 */
fun ApplicationAliasRebindResult.response() = ApplicationAliasRebindResponse(
    requestId, aliasId, aliasVersion, targetApplicationId, targetMappingVersion, replayed
)

/** 在 HTTP 边界将 reviewed decision 转换为稳定 code。 */
fun ApplicationReviewedBindingResult.response() = ApplicationReviewedBindingResponse(
    requestId,
    decision.code,
    applicationId,
    aliasId,
    aliasVersion,
    targetMappingVersion,
    affectedSourceCount,
    replayed
)

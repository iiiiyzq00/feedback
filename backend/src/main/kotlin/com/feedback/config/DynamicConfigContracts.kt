package com.feedback.config

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDateTime

data class DynamicConfigSingleChangeRequest(
    @field:NotNull
    @field:Schema(description = "新的配置值；类型必须符合动态配置目录", example = "120")
    val value: JsonNode,
    @field:NotBlank
    @field:Size(max = 30)
    @field:Schema(description = "客户端读取到的当前版本；必须精确匹配，避免覆盖并发修改", example = "v3")
    val expectedVersion: String,
    @field:NotBlank
    @field:Size(min = 2, max = 500)
    @field:Schema(description = "变更原因；业务表保存原文，正式审计仅保存哈希与长度", example = "提高 SDK 接入容量")
    val reason: String
)

data class DynamicConfigChangeItemRequest(
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "动态配置键；必须存在于非秘密配置目录", example = "sdk.rate-limit")
    val key: String,
    @field:NotNull
    @field:Schema(description = "新的配置值；不会写入正式审计", example = "120")
    val value: JsonNode,
    @field:NotBlank
    @field:Size(max = 30)
    @field:Schema(description = "客户端读取到的当前版本", example = "v3")
    val expectedVersion: String
)

data class DynamicConfigBatchChangeRequest(
    @field:Valid
    @field:Size(min = 1, max = 20)
    @field:Schema(description = "同一事务提交的配置变更；配置键不得重复，最多 20 项")
    val changes: List<DynamicConfigChangeItemRequest>,
    @field:NotBlank
    @field:Size(min = 2, max = 500)
    @field:Schema(description = "整批变更原因", example = "统一调整 SDK 接入容量")
    val reason: String
)

data class DynamicConfigRollbackRequest(
    @field:NotBlank
    @field:Size(max = 30)
    @field:Schema(description = "要复制为新版本的历史版本；旧记录不会重新激活", example = "v2")
    val targetVersion: String,
    @field:NotBlank
    @field:Size(max = 30)
    @field:Schema(description = "客户端读取到的当前活动版本", example = "v4")
    val expectedVersion: String,
    @field:NotBlank
    @field:Size(min = 2, max = 500)
    @field:Schema(description = "回滚原因", example = "新阈值导致误拒绝，恢复上一稳定值")
    val reason: String
)

data class DynamicConfigHistoryQuery(
    @field:Min(1)
    @field:Max(100000)
    @field:Schema(description = "页码，从 1 开始", example = "1", defaultValue = "1")
    val page: Int = 1,
    @field:Min(1)
    @field:Max(100)
    @field:Schema(description = "每页记录数，最大 100", example = "20", defaultValue = "20")
    val size: Int = 20
)

data class DynamicConfigItemResponse(
    @field:Schema(description = "配置主键", example = "sdk.rate-limit") val configKey: String,
    @field:Schema(description = "当前版本", example = "v3") val version: String,
    @field:Schema(description = "当前非秘密配置值") val value: JsonNode,
    @field:Schema(description = "目录中的中文业务说明") val description: String,
    @field:Schema(description = "最近一次变更原因") val changeReason: String?,
    @field:Schema(description = "最近更新时间，数据库 DATETIME(3)") val updatedAt: LocalDateTime,
    @field:Schema(description = "值类型", allowableValues = ["INTEGER", "DECIMAL", "BOOLEAN", "STRING", "JSON"]) val valueType: String,
    @field:Schema(description = "数值最小值；非数值类型为空") val minValue: BigDecimal?,
    @field:Schema(description = "数值最大值；非数值类型为空") val maxValue: BigDecimal?,
    @field:Schema(description = "是否支持调用方下次读取时直接生效") val hotReload: Boolean,
    @field:Schema(description = "配置所属模块") val ownerModule: String
)

data class DynamicConfigHistoryItemResponse(
    @field:Schema(description = "配置记录 ID") val id: Long,
    @field:Schema(description = "配置键") val configKey: String,
    @field:Schema(description = "历史版本") val version: String,
    @field:Schema(description = "该历史版本的非秘密值") val value: JsonNode,
    @field:Schema(description = "是否为当前活动版本") val active: Boolean,
    @field:Schema(description = "原子变更集 ID") val changeSetId: String?,
    @field:Schema(description = "业务变更原因") val reason: String?,
    @field:Schema(description = "回滚来源记录 ID") val sourceConfigId: Long?,
    @field:Schema(description = "操作者用户 ID") val createdBy: Long?,
    @field:Schema(description = "创建时间，数据库 DATETIME(3)") val createdAt: LocalDateTime
)

data class DynamicConfigHistoryPageResponse(
    @field:Schema(description = "当前页的配置历史记录") val records: List<DynamicConfigHistoryItemResponse>,
    @field:Schema(description = "该配置键的历史记录总数", example = "4") val total: Long,
    @field:Schema(description = "当前页码，从 1 开始", example = "1") val page: Int,
    @field:Schema(description = "当前每页记录数", example = "20") val size: Int
)

data class DynamicConfigChangedItemResponse(
    @field:Schema(description = "已变更的配置键", example = "sdk.rate-limit") val key: String,
    @field:Schema(description = "变更后生成的新版本", example = "v4") val version: String
)

data class DynamicConfigChangeResponse(
    @field:Schema(description = "原子变更集 ID，用于审计追踪") val changeSetId: String,
    @field:Schema(description = "本次成功变更的配置键与新版本") val changes: List<DynamicConfigChangedItemResponse>,
    @field:Schema(description = "是否所有变更均可在调用方下次读取时直接生效") val hotReloaded: Boolean,
    @field:Schema(description = "需要重启相应模块才能生效的配置键") val restartRequiredKeys: List<String>
)

data class DynamicConfigRollbackResponse(
    @field:Schema(description = "回滚变更集 ID，用于审计追踪") val changeSetId: String,
    @field:Schema(description = "已回滚的配置键", example = "sdk.rate-limit") val key: String,
    @field:Schema(description = "作为新值来源的历史版本", example = "v2") val rolledBackFrom: String,
    @field:Schema(description = "回滚操作生成的新版本；历史记录不会重新激活", example = "v5") val newVersion: String,
    @field:Schema(description = "回滚值是否可在调用方下次读取时直接生效") val hotReloaded: Boolean,
    @field:Schema(description = "需要重启相应模块才能生效的配置键") val restartRequiredKeys: List<String>
)

data class DynamicConfigChangeItemCommand(val key: String, val value: JsonNode, val expectedVersion: String)
data class DynamicConfigChangeCommand(val changes: List<DynamicConfigChangeItemCommand>, val reason: String)
data class DynamicConfigRollbackCommand(
    val key: String,
    val targetVersion: String,
    val expectedVersion: String,
    val reason: String
)

/**
 * 运行时字符串字典的领域边界。
 *
 * 调用方只能按键读取或枚举显式键，不再跨层传递可任意修改的通用 Map。
 */
class RuntimeStringDictionary internal constructor(private val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]
    fun keys(): Set<String> = values.keys
    fun enabledKeys(): Set<String> = values.filterValues { it.equals("true", true) }.keys

    companion object {
        val EMPTY = RuntimeStringDictionary(emptyMap())
        fun of(values: Map<String, String>) = RuntimeStringDictionary(values.toMap())
    }
}

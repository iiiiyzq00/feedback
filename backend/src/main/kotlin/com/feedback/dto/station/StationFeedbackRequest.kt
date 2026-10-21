package com.feedback.dto.station

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** StationFeedbackRequest 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
@Schema(description = "Station 在线反馈接入请求；V1/V2 共用字段，身份与产品权限由网关认证上下文提供")
data class StationFeedbackRequest(
    @field:NotBlank @field:Size(max = 100)
    @field:Schema(description = "来源产品标识；会与网关产品上下文分别留存，不作为权限依据", example = "study-app")
    val productId: String,
    @field:Size(max = 50) @field:Schema(description = "客户端应用版本", example = "6.2.1")
    val appVersion: String = "",
    @field:NotBlank @field:Size(max = 500) @field:Schema(description = "反馈标题；入库前执行公共脱敏", example = "课程页面无法打开")
    val title: String,
    @field:NotBlank @field:Size(max = 100_000) @field:Schema(description = "反馈正文；入库前执行公共脱敏", example = "点击课程后一直显示加载中")
    val content: String,
    @field:Size(max = 1024) @field:Schema(description = "V2 兼容联系方式密文或适配器已解析号码；不在普通响应返回")
    val phone: String = "",
    @field:NotBlank @field:Size(max = 50) @field:Schema(description = "来源平台", example = "android")
    val platform: String,
    @field:Size(max = 50) @field:Schema(description = "来源反馈类型", example = "BUG")
    val type: String = "",
    @field:Size(max = 50) @field:Schema(description = "来源子渠道", example = "in_app")
    val channel: String = "",
    @field:Size(max = 9) @field:Schema(description = "待下载的 HTTPS 图片地址；最多 9 个，每个最多 2048 字符")
    val photoUrlList: List<@Size(max = 2048) String>? = null,
    @field:Schema(description = "历史动态属性 JSON；当前仅按兼容输入接收，不进入普通字段出口")
    val feedbackAttributeRecords: JsonNode? = null,
    @field:Size(max = 100) @field:Schema(description = "来源声明用户标识；不替代网关认证身份", example = "user-123")
    val userId: String = "",
    @field:Size(max = 1000) @field:Schema(description = "来源补充描述；入库前执行公共脱敏")
    val description: String = "",
    @field:Size(max = 100) @field:Schema(description = "来源应用标识", example = "course")
    val appId: String = "",
    @field:Size(max = 200) @field:Schema(description = "来源应用名称", example = "课程学习")
    val appName: String = ""
)

/** Station 对外 API 版本封闭集合；枚举名即保持兼容的稳定协议 code。 */
enum class StationApiVersion { V1, V2 }

/** StationAcceptResult 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
data class StationAcceptResult(val receiptId: String, val sourceId: Long)

/** StationFeedbackAcceptedResponse 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
@Schema(description = "Station HTTP 兼容受理响应；回执位于 X-Feedback-Receipt-Id 响应头")
data class StationFeedbackAcceptedResponse(
    @field:Schema(description = "兼容业务码；0 表示已受理", example = "0") val code: Int,
    @field:Schema(description = "兼容结果消息", example = "ok") val msg: String
)

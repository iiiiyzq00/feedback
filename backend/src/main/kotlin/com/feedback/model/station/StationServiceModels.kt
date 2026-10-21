package com.feedback.model.station

import com.feedback.dto.station.StationApiVersion
import com.feedback.dto.station.StationFeedbackRequest
import com.feedback.security.station.StationGatewayPrincipal
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.nio.file.Path

/** 双写命令载荷；协议、幂等键和认证主体共同进入指纹。 */
data class StationDualWriteEnvelope(val request: StationFeedbackRequest, val version: StationApiVersion, val idempotencyKey: String, val principal: StationGatewayPrincipal)

/** 双写受理结果；回执保持既有 URL-safe Base64 编码。 */
data class StationDualWriteAcceptResult(val receiptId: String, val taskId: Long)

/** 已认领并解密的双写任务，不携带持久化 PO。 */
data class StationDualWriteTask(val id: Long, val envelope: StationDualWriteEnvelope, val oldFbpSucceeded: Boolean, val newPlatformSucceeded: Boolean, val retryCount: Int, val maxRetryCount: Int)

/** 在线受理事务外准备完成的命令；不含明文手机号或未脱敏正文。 */
data class PreparedStationIngest(
    val receiptId: ByteArray, val sourceRecordId: String, val appVersion: String?, val occurredAt: LocalDateTime?,
    val faultTimeRaw: String?, val faultTimePrecision: String?, val faultTimeSource: String?, val faultTimeResolution: String,
    val now: LocalDateTime, val safeTitle: String, val safeContent: String, val safeDescription: String?, val maskingStatus: String,
    val sourceApiVersion: String, val productId: String, val sourceAppId: String?, val sourceAppName: String?, val sourceUserId: String?,
    val authenticatedUserId: String?, val authenticatedProductCode: String, val sourcePlatform: String, val sourceFeedbackType: String,
    val sourceSubChannel: String, val pendingPhotoUrls: String?, val sourcePhoneCiphertext: String?, val contactStatus: String,
    val requestFingerprint: ByteArray, val rawPayload: PreparedStationRawPayload, val identity: PreparedStationIdentity?
)

/** Station 加密原始载荷的稳定内部结构；仅安全恢复链可解密读取。 */
data class StationOnlineRawPayload(val schemaVersion: String = "station-raw-v1", val sourceMode: String = "REALTIME", val apiVersion: String, val title: String, val content: String, val description: String, val productId: String, val appVersion: String, val phone: String, val platform: String, val type: String, val channel: String, val userId: String, val appId: String, val appName: String, val photoUrlList: List<String>)

/** Legacy 受控导入的稳定汇总结果，不暴露持久化模型。 */
data class StationLegacyImportResult(val fileRows: Int, val inserted: Int, val enriched: Int, val evidenceQueued: Int, val recovered: Int, val invalidContacts: Int, val skipped: Int, val busySkipped: Int, val successfulBatches: Int, val fileSha256: String)

/** Legacy 运维恢复的稳定汇总结果。 */
data class StationLegacyRecoveryResult(val targetRows: Int, val matchedRows: Int, val recovered: Int, val skipped: Int, val busySkipped: Int, val successfulBatches: Int, val fileSha256: String)

/** Legacy 单行处理结果的封闭集合，名称同时作为稳定审计 code。 */
enum class StationLegacyRowDecision { INSERTED, INSERTED_INVALID_CONTACT, ENRICHED, EVIDENCE_QUEUED, RECOVERED, INVALID_CONTACT, SKIPPED, BUSY }

/** 一个独立事务批次的计数与游标结果。 */
data class StationLegacyBatchResult(val inserted: Int = 0, val enriched: Int = 0, val evidenceQueued: Int = 0, val recovered: Int = 0, val invalidContacts: Int = 0, val skipped: Int = 0, val busySkipped: Int = 0, val candidateCount: Int = 0, val matchedCount: Int = 0, val nextSourceId: Long = 0) {
    /** 合并相邻批次且保证恢复游标不回退。 */
    operator fun plus(other: StationLegacyBatchResult) = StationLegacyBatchResult(inserted + other.inserted, enriched + other.enriched, evidenceQueued + other.evidenceQueued, recovered + other.recovered, invalidContacts + other.invalidContacts, skipped + other.skipped, busySkipped + other.busySkipped, candidateCount + other.candidateCount, matchedCount + other.matchedCount, other.nextSourceId.coerceAtLeast(nextSourceId))
}

/** Legacy 原始证据的稳定序列化模型，字段编码与既有密文载荷兼容。 */
data class StationLegacyRawPayload(val schemaVersion: String = "station-raw-v1", val sourceMode: String = "LEGACY_EXPORT", val apiVersion: String = "V2", val sourceRecordId: String, val title: String, val content: String, val description: String?, val productId: String, val productName: String?, val appId: String?, val appName: String?, val appVersion: String?, val sourceUserId: String?, val phone: String?, val photoUrlList: List<String>)

/** Station 身份类型的稳定数据库编码。 */
enum class StationIdentityType(val code: String) { PHONE("PHONE"), USER_ID("USER_ID") }

/** 已在事务外完成规范化、指纹和加密的身份写模型。 */
data class PreparedStationIdentity(val identityType: StationIdentityType, val fingerprint: ByteArray, val fingerprintKeyId: String, val contactCiphertext: ByteArray, val contactNonce: ByteArray, val contactTag: ByteArray, val contactKeyId: String, val consentedAt: LocalDateTime, val expiresAt: LocalDateTime)

/** Station 在线失败任务重试请求；单批最多 100 条。 */
@Schema(description = "Station 在线失败任务重试请求；仅管理员或运营角色可调用")
data class StationRetryRequest(@field:Size(min = 1, max = 100) @field:Schema(description = "Station 受理回执列表；单次最多 100 条，重复回执按一条处理") val receiptIds: List<String>)

/** Station 在线失败任务重试结果；queued 与 skipped 之和等于去重输入数。 */
@Schema(description = "Station 在线失败任务重试结果")
data class StationRetryResponse(val queued: Int, val skipped: Int)

/** 已在事务外完成加密的 Station 原始载荷写模型。 */
data class PreparedStationRawPayload(val payloadCiphertext: ByteArray, val payloadNonce: ByteArray, val payloadTag: ByteArray, val keyId: String, val retentionUntil: LocalDateTime)

/** 汇总单行历史导入实际新增的身份结果，用于决定渠道状态和投影脏标记。 */
internal data class IdentityInsertResult(
    val anyInserted: Boolean,
    val phoneAvailable: Boolean
)

/** Station 历史导出文件及其完整 SHA-256 摘要。 */
data class StationLegacyExportFile<T>(val rows: List<T>, val sha256: String)

/** 已通过列数、长度和时间格式校验的 Station 历史导出行。 */
data class StationLegacyExportRow(val rowNumber: Int, val recordId: String, val productId: String, val title: String, val content: String, val createdAt: LocalDateTime, val updatedAt: LocalDateTime, val productName: String?, val appId: String?, val appName: String?, val appVersion: String?, val feedbackType: String?, val platform: String?, val subChannel: String?, val sourceDescription: String?, val sourceUserId: String?, val phone: String?, val photoUrls: List<String>, val processingStatus: String)

/** 已通过列数和长度校验的 Station 历史恢复行。 */
data class StationLegacyRecoveryRow(val rowNumber: Int, val recordId: String, val phone: String?, val photoUrls: List<String>)

/** Station 联系方式解析结果；授权依据必须与值一起传播。 */
data class ResolvedStationPhone(val value: String, val authorizationBasis: String)

/** Station 历史手机号解析的封闭结果，区分缺失、合法和受控失败。 */
sealed interface StationLegacyPhoneDecision {
    /** 表示历史记录未提供手机号，不生成 PHONE 身份。 */
    data object Missing : StationLegacyPhoneDecision
    /** 表示已规范化且通过格式校验的历史手机号。 */
    data class Valid(val normalized: String) : StationLegacyPhoneDecision
    /** 表示手机号不可用，并携带允许审计的稳定错误码。 */
    data class Invalid(val errorCode: String) : StationLegacyPhoneDecision
}

/** 已在事务外完成脱敏和手机号判定的 Station 历史写入行。 */
data class StationLegacyPreparedRow(val source: StationLegacyExportRow, val safeTitle: String, val safeContent: String, val safeSourceDescription: String?, val phone: StationLegacyPhoneDecision)

/** 已完成 URL 校验的 Station 附件候选；保存序号与摘要身份供下载前去重。 */
internal data class StationAttachmentCandidate(val url: String, val ordinal: Int, val sourceIdentity: String)

package com.feedback.application.port.feedback.diagnostics

/** 诊断读取合同只暴露具名事实，不把 MyBatis PO、Mapper 或 JSON 树泄漏到应用服务。 */
interface FeedbackDiagnosticsReadPort {
    fun identities(sourceId: Long): List<FeedbackDiagnosticIdentityFact>
    fun attachments(sourceId: Long): List<FeedbackDiagnosticAttachmentFact>
    fun station(sourceId: Long): FeedbackDiagnosticStationFact?
    fun sdk(sourceId: Long): FeedbackDiagnosticSdkFact?
    fun identitiesBySourceIds(sourceIds: List<Long>): Map<Long, List<FeedbackDiagnosticIdentityFact>>
    fun stationsBySourceIds(sourceIds: List<Long>): Map<Long, FeedbackDiagnosticStationFact>
    fun sdksBySourceIds(sourceIds: List<Long>): Map<Long, FeedbackDiagnosticSdkFact>
}

/** 审计写合同接收具名事件；JSON 与持久化行格式止于 Repository 适配层。 */
interface FeedbackDiagnosticsAuditPort {
    fun append(event: FeedbackDiagnosticsAuditEvent)
    fun appendAll(events: List<FeedbackDiagnosticsAuditEvent>)
}

data class FeedbackDiagnosticIdentityFact(
    val sourceId: Long,
    val identityType: String,
    val ciphertext: ByteArray?,
    val nonce: ByteArray?,
    val tag: ByteArray?,
    val keyId: String?,
    val algorithm: String?
)

data class FeedbackDiagnosticStationFact(
    val sourceId: Long,
    val sourceUserId: String?,
    val authenticatedUserId: String?
)

data class FeedbackDiagnosticSdkFact(
    val sourceId: Long,
    val ciphertext: ByteArray?,
    val nonce: ByteArray?,
    val tag: ByteArray?,
    val keyId: String?,
    val algorithm: String?
)

data class FeedbackDiagnosticAttachmentFact(
    val id: Long,
    val sourceAttachmentId: String?,
    val mimeType: String?,
    val fileSizeBytes: Long?,
    val securityStatus: String?,
    val integrityStatus: String?,
    val malwareScanStatus: String?,
    val contentReviewStatus: String?
)

data class FeedbackDiagnosticsAuditEvent(
    val actorId: Long,
    val sourceId: Long,
    val purposeCode: String,
    val purposeSha256: String,
    val purposeLength: Int,
    val types: List<String>,
    val visibility: String,
    val ipAddress: String?
)

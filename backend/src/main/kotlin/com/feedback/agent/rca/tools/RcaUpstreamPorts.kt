package com.feedback.agent.rca.tools

import java.time.LocalDateTime

/**
 * 七类只读工具的上游端口。
 *
 * 分层的意义在于：Agent 适配层（参数归一、超时、预算、错误语义翻译）与上游查询接口
 * 是两份不同的责任。上游团队的接口会变，但 Agent 侧的错误语义必须稳定，
 * 否则「超时」和「没有」会在某次上游改版后悄悄对调，而这类问题只能靠事后审计发现。
 *
 * 所有端口都是**只读**的，且**不抛出上游异常**：失败一律通过响应体里的状态字段表达，
 * 由适配层翻译成 [com.feedback.agent.rca.domain.ToolResultStatus]。
 * 异常穿透到编排层会丢失失败语义，也会让预算记账漏掉这次调用。
 *
 * 端口响应统一带 [UpstreamEnvelope.watermark]：数据水位。水位落后于请求窗口上界时，
 * 该证据必须被标为 STALE，因为迟到数据可能推翻基于它得出的结论。
 */

/** 上游响应外壳。所有端口响应用它携带水位与上游请求 ID。 */
data class UpstreamEnvelope<T>(
    val payload: T?,
    /** 上游请求 ID；本地未写回时按它对账上游最终状态。 */
    val providerRequestId: String?,
    /** 数据水位；null 表示上游未提供，此时该证据不得用于时效敏感结论。 */
    val watermark: LocalDateTime?,
    /** 上游给出的失败码；非 null 表示本次调用失败，[payload] 应为 null。 */
    val upstreamErrorCode: String? = null
) {
    val succeeded: Boolean get() = upstreamErrorCode == null && payload != null

    companion object {
        fun <T> ok(
            payload: T,
            providerRequestId: String? = null,
            watermark: LocalDateTime? = null
        ): UpstreamEnvelope<T> =
            UpstreamEnvelope(payload, providerRequestId, watermark)

        fun <T> failed(code: String, providerRequestId: String? = null): UpstreamEnvelope<T> =
            UpstreamEnvelope(
                payload = null,
                providerRequestId = providerRequestId,
                watermark = null,
                upstreamErrorCode = code
            )
    }
}

// ---------------------------------------------------------------------------
// 1. 日志 Trace
// ---------------------------------------------------------------------------

/** 按合法日志 ID 查询 Trace。 */
data class LogTraceQuery(
    val logId: String,
    /** 时间窗，闭开区间 [from, to)；为空表示由上游按日志 ID 自行定位。 */
    val windowFrom: LocalDateTime? = null,
    val windowTo: LocalDateTime? = null
)

data class LogTraceRecord(
    val traceId: String,
    val logId: String,
    val spans: List<LogSpan>,
    /** 该 Trace 是否因采样或分区缺失而不完整。 */
    val partial: Boolean = false
)

/** Trace 中的一个 span。只保留判断首错点所需的字段。 */
data class LogSpan(
    val serviceName: String,
    val operationName: String,
    val endpoint: String? = null,
    val statusCode: Int? = null,
    val startedAt: LocalDateTime,
    val durationMs: Long? = null,
    /** 已脱敏的异常类名或业务错误码。 */
    val errorCode: String? = null,
    val failed: Boolean = false
)

interface LogTracePort {
    fun fetch(query: LogTraceQuery): UpstreamEnvelope<LogTraceRecord>
}

// ---------------------------------------------------------------------------
// 2. 发布变更
// ---------------------------------------------------------------------------

data class ReleaseChangeQuery(
    val appId: String,
    /** 组件名；为空表示查询该应用全部组件。 */
    val component: String? = null,
    val windowFrom: LocalDateTime,
    val windowTo: LocalDateTime
)

data class ReleaseChangeRecord(
    val releaseId: String,
    val appId: String,
    val component: String,
    val version: String,
    val releasedAt: LocalDateTime,
    /** 变更类型，例如 CONFIG、SCHEMA、API_CONTRACT、DEPENDENCY。 */
    val changeTypes: List<String>,
    val changeSummary: String,
    /** 结构化变更明细；键为受影响的契约点，例如参数名。 */
    val changeDetail: Map<String, String> = emptyMap(),
    /** 是否可回滚；止损建议据此给出。 */
    val rollbackAvailable: Boolean = true
)

interface ReleaseChangePort {
    fun fetch(query: ReleaseChangeQuery): UpstreamEnvelope<List<ReleaseChangeRecord>>
}

// ---------------------------------------------------------------------------
// 3. 版本对照
// ---------------------------------------------------------------------------

/**
 * 版本会话量与错误量查询。
 *
 * [controlVersion] 是必需的：没有对照组的版本集中度结论不成立，
 * 这一点通过端口签名强制，而不是靠调用方自觉。
 */
data class VersionCohortQuery(
    val appId: String,
    /** 指标键，例如 video_play_failure_rate。 */
    val metricKey: String,
    val windowFrom: LocalDateTime,
    val windowTo: LocalDateTime,
    /** 对照组版本，例如当前线上稳定版。 */
    val controlVersion: String
)

data class VersionCohortRow(
    val version: String,
    /** 分母：该版本在窗口内的会话量。 */
    val sessions: Long,
    /** 分子：命中该指标的会话量。 */
    val errors: Long
) {
    /** 失败率；分母为 0 时返回 null，由适配层翻译成 INSUFFICIENT_SAMPLE。 */
    val failureRate: java.math.BigDecimal?
        get() = if (sessions <= 0) null else java.math.BigDecimal.valueOf(errors).divide(
            java.math.BigDecimal.valueOf(sessions), 6, java.math.RoundingMode.HALF_UP
        )
}

data class VersionCohortRecord(
    val metricKey: String,
    val rows: List<VersionCohortRow>,
    val controlVersion: String,
    /** 上游标记的样本量是否足够；不足时不得输出集中度结论。 */
    val sampleSufficient: Boolean
)

interface VersionCohortPort {
    fun fetch(query: VersionCohortQuery): UpstreamEnvelope<VersionCohortRecord>
}

// ---------------------------------------------------------------------------
// 4. 服务健康
// ---------------------------------------------------------------------------

data class ServiceHealthQuery(
    val services: List<String>,
    val windowFrom: LocalDateTime,
    val windowTo: LocalDateTime
)

data class ServiceHealthRow(
    val serviceName: String,
    val totalRequests: Long,
    val errorRequests: Long,
    /** 仅在该服务确实存在异常窗口时非空；整体正常时为 null 而不是全零窗口。 */
    val anomalyWindowFrom: LocalDateTime? = null,
    val anomalyWindowTo: LocalDateTime? = null,
    /** 共同依赖摘要，用于判断两个服务是否共享同一个上游依赖。 */
    val sharedDependencies: List<String> = emptyList()
) {
    val errorRate: java.math.BigDecimal?
        get() = if (totalRequests <= 0) null else java.math.BigDecimal.valueOf(errorRequests).divide(
            java.math.BigDecimal.valueOf(totalRequests), 6, java.math.RoundingMode.HALF_UP
        )

    val overallHealthy: Boolean get() = errorRequests == 0L || anomalyWindowFrom == null
}

data class ServiceHealthRecord(
    val rows: List<ServiceHealthRow>,
    /** 上游只覆盖了部分服务或部分时间窗，必须显式回传。 */
    val partial: Boolean = false,
    val missingServices: List<String> = emptyList()
)

interface ServiceHealthPort {
    fun fetch(query: ServiceHealthQuery): UpstreamEnvelope<ServiceHealthRecord>
}

// ---------------------------------------------------------------------------
// 5. 崩溃 / ANR
// ---------------------------------------------------------------------------

data class CrashAnrQuery(
    val appId: String,
    val version: String? = null,
    val deviceModel: String? = null,
    val windowFrom: LocalDateTime,
    val windowTo: LocalDateTime
)

data class CrashStackRow(
    /** 堆栈指纹：归一后的稳定指纹，同一缺陷不同机型应归到同一指纹。 */
    val fingerprint: String,
    val exceptionType: String,
    val topFrame: String,
    val occurrences: Long,
    val versions: Map<String, Long> = emptyMap(),
    val deviceModels: Map<String, Long> = emptyMap(),
    /** 符号表是否可用。不可用时只能给出指纹，无法定位到代码行。 */
    val symbolAvailable: Boolean
)

data class CrashAnrRecord(val rows: List<CrashStackRow>)

interface CrashAnrPort {
    fun fetch(query: CrashAnrQuery): UpstreamEnvelope<CrashAnrRecord>
}

// ---------------------------------------------------------------------------
// 6. 内容状态
// ---------------------------------------------------------------------------

data class ContentStatusQuery(
    /** 资源标识，例如课程视频的 resource token。 */
    val resourceToken: String,
    val appId: String,
    val windowFrom: LocalDateTime? = null,
    val windowTo: LocalDateTime? = null
)

data class ContentStatusRecord(
    val resourceToken: String,
    /** 发布状态：PUBLISHED、UNPUBLISHED、SCHEDULED。 */
    val publishStatus: String,
    /** 可见性范围：例如 ALL_TENANTS、SPECIFIC_SCHOOL。 */
    val visibility: String,
    /** CDN 分发状态：READY、PENDING、FAILED。 */
    val cdnStatus: String,
    /** 当前生效的鉴权策略标识；与发布变更里的策略版本可比对。 */
    val authPolicyId: String? = null,
    val updatedAt: LocalDateTime? = null
)

interface ContentStatusPort {
    fun fetch(query: ContentStatusQuery): UpstreamEnvelope<ContentStatusRecord>
}

// ---------------------------------------------------------------------------
// 7. 历史案例
// ---------------------------------------------------------------------------

data class HistoricalCaseQuery(
    /** 用于召回的签名提示，例如组件 + 故障类型。 */
    val signatureHint: String,
    val component: String? = null,
    val topK: Int = 5
)

data class HistoricalCaseRow(
    val incidentId: Long,
    val title: String,
    /** 已人工确认的根因；未确认的案例不得进入结果集。 */
    val confirmedRootCause: String,
    val fixVersion: String? = null,
    /** 修复是否经过发布后验证有效。只有验证有效的案例才算「已确认」。 */
    val verificationPassed: Boolean,
    val confirmedAt: LocalDateTime? = null
)

data class HistoricalCaseRecord(val rows: List<HistoricalCaseRow>) {
    /** 只有全部行都通过发布后验证，才认为存在可用先验。 */
    val hasVerifiedCase: Boolean get() = rows.isNotEmpty() && rows.all(HistoricalCaseRow::verificationPassed)
}

interface HistoricalCasePort {
    fun fetch(query: HistoricalCaseQuery): UpstreamEnvelope<HistoricalCaseRecord>
}

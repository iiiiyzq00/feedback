package com.feedback.agent.rca.domain

/**
 * 工具返回状态。
 *
 * 这一组枚举是「证据缺口」和「没有问题」的分界线，也是最容易被做错的地方：
 * 只有唯一一次合法且完整的查询返回为空（[SUCCESS_EMPTY] / [NOT_FOUND]）才能被理解为
 * 「该窗口内确实没有日志」；超时（[UPSTREAM_TIMEOUT]）和无权限（[FORBIDDEN]）属于
 * 证据缺口，必须记入 gaps，不得用来反驳任何假设。
 *
 * 编码落库到 `t_rca_tool_call.result_status`，属于稳定协议字段。
 */
enum class ToolResultStatus(
    val storageCode: String,
    /** 是否拿到了可用数据。 */
    val hasData: Boolean,
    /** 是否构成证据缺口（查询没做成，而非查询结果是空）。 */
    val evidenceGap: Boolean
) {
    /** 拿到数据。 */
    SUCCESS_WITH_DATA("SUCCESS_WITH_DATA", hasData = true, evidenceGap = false),

    /** 合法且完整的查询，结果确实为空。这是可用的结论依据。 */
    SUCCESS_EMPTY("SUCCESS_EMPTY", hasData = false, evidenceGap = false),

    /** 按 ID 查不到该记录；与「窗口内没有」语义不同，表示上游没有这条事实。 */
    NOT_FOUND("NOT_FOUND", hasData = false, evidenceGap = false),

    /** 日志 ID 本身非法或已过期。需要回到问题画像重新确认 ID。 */
    INVALID_LOG_ID("INVALID_LOG_ID", hasData = false, evidenceGap = true),

    /** 无权限。这是缺口，不是「没有异常」。 */
    FORBIDDEN("FORBIDDEN", hasData = false, evidenceGap = true),

    /** 上游超时。这是缺口，重试或降级都必须保留该语义。 */
    UPSTREAM_TIMEOUT("UPSTREAM_TIMEOUT", hasData = false, evidenceGap = true),

    /** 上游返回结构不符合约定 Schema。不能当成空结果。 */
    SCHEMA_INVALID("SCHEMA_INVALID", hasData = false, evidenceGap = true),

    /** 只拿到部分分区或部分时间窗；必须标注覆盖不全。 */
    PARTIAL("PARTIAL", hasData = true, evidenceGap = true),

    /** 上游数据水位落后于请求时间窗，结论可能被迟到数据推翻。 */
    STALE("STALE", hasData = true, evidenceGap = true),

    /** 样本量不足，不足以计算集中度。 */
    INSUFFICIENT_SAMPLE("INSUFFICIENT_SAMPLE", hasData = false, evidenceGap = true),

    /** 没有经过验证的历史案例。历史案例缺失不影响现场取证，属弱缺口。 */
    NO_VERIFIED_CASE("NO_VERIFIED_CASE", hasData = false, evidenceGap = false),

    /** 内容或资源不存在。 */
    RESOURCE_MISSING("RESOURCE_MISSING", hasData = false, evidenceGap = false),

    /** 堆栈符号缺失，无法定位到代码行。 */
    SYMBOL_MISSING("SYMBOL_MISSING", hasData = false, evidenceGap = true),

    /** 未归类失败。调用方必须显式处理，不得静默当成空结果。 */
    UNKNOWN("UNKNOWN", hasData = false, evidenceGap = true);

    companion object {
        private val BY_CODE = entries.associateBy(ToolResultStatus::storageCode)

        fun fromStorage(code: String?): ToolResultStatus? = code?.let(BY_CODE::get)

        /** 这次调用是否算作「已完成的取证」——只有拿到了数据或确认过合法的空。 */
        fun isConclusive(status: ToolResultStatus): Boolean =
            status.hasData || status == SUCCESS_EMPTY || status == NOT_FOUND
    }
}

/**
 * 七类只读工具。
 *
 * 全部只读，且受步数、超时和费用限制。刻意不把数据库查询、通知发送或自由 shell 包装成
 * Agent 工具：一旦工具能写，回放与恢复的确定性就不成立了。
 *
 * 主线 MVP 只强依赖前三个（日志 Trace / 发布变更 / 版本对照），其余四个按需启用。
 */
enum class RcaToolName(
    val storageCode: String,
    /** 该工具产出的证据覆盖粒度，决定它能支撑什么类型的结论。 */
    val evidenceCoverage: EvidenceCoverage,
    /** 该工具产出的证据类型。由工具自己声明，不接受由模型指定。 */
    val evidenceType: EvidenceType,
    /** 是否属于 MVP 主线；非主线工具在预算紧张时优先让路。 */
    val mvpMainline: Boolean
) {
    /** 日志 Trace：按合法 log ID 查询，产出时间窗、首错点、错误签名与回源 ID。 */
    LOG_TRACE("LOG_TRACE", EvidenceCoverage.EPISODE, EvidenceType.LOG_TRACE, mvpMainline = true),

    /** 发布变更：应用与组件发布记录，产出变更窗口、影响组件与版本关联。 */
    RELEASE_CHANGE("RELEASE_CHANGE", EvidenceCoverage.EPISODE, EvidenceType.RELEASE_CHANGE, mvpMainline = true),

    /** 版本对照：版本会话量与错误量，产出分母、对照版本与集中度。 */
    VERSION_COHORT("VERSION_COHORT", EvidenceCoverage.COHORT, EvidenceType.VERSION_COHORT, mvpMainline = true),

    /** 服务健康：服务与依赖指标，产出时间对齐后的异常窗口与共同依赖摘要。 */
    SERVICE_HEALTH("SERVICE_HEALTH", EvidenceCoverage.COHORT, EvidenceType.SERVICE_HEALTH, mvpMainline = false),

    /** Crash/ANR：堆栈与聚合指纹，产出堆栈去重、版本与机型分布。 */
    CRASH_ANR("CRASH_ANR", EvidenceCoverage.EPISODE, EvidenceType.CRASH_STACK, mvpMainline = false),

    /** 内容状态：内容、鉴权与 CDN 状态，产出资源范围、发布状态与可见性。 */
    CONTENT_STATUS("CONTENT_STATUS", EvidenceCoverage.EPISODE, EvidenceType.CONTENT_STATUS, mvpMainline = false),

    /** 历史案例：已确认 Incident 与修复记录，只提供先验，不能单独提升置信等级。 */
    HISTORICAL_CASE("HISTORICAL_CASE", EvidenceCoverage.EPISODE, EvidenceType.HISTORICAL_CASE, mvpMainline = false);

    companion object {
        private val BY_CODE = entries.associateBy(RcaToolName::storageCode)

        fun fromStorage(code: String?): RcaToolName? = code?.let(BY_CODE::get)

        val MAINLINE: List<RcaToolName> = entries.filter(RcaToolName::mvpMainline)
    }
}

/**
 * 工具调用请求。[paramsDigest] 由 [RcaToolInvoker] 统一计算，调用方不自行拼接，
 * 否则「同工具同参数可复用 Observation」的去重判断会因序列化差异而失效。
 */
data class ToolRequest(
    val tool: RcaToolName,
    /** 业务参数，键值均为已脱敏的标量或标量列表。 */
    val params: Map<String, Any?>,
    /** 第几轮决策发起；同一轮内的多个调用互不依赖。 */
    val decisionRound: Int,
    /** 调用方给出的选择理由，用于可观测性回溯而不是参与判定。 */
    val selectionReason: String,
    /** 归一化参数摘要，SHA-256 十六进制小写。 */
    val paramsDigest: String = ""
) {
    /** 去重复用键：同工具同参数即视为同一次取证。 */
    val reuseKey: String get() = "${tool.storageCode}@$paramsDigest"

    companion object {
        /**
         * 调用方声明的被检验假设编码。
         *
         * 这一项会随参数一起进参数摘要，因此同一个工具为不同假设发起的调用不会被去重合并——
         * 它们在语义上不是同一次取证。工具侧回填支持/反驳、循环侧构造请求都引用这一个常量，
         * 避免两处各写一个字符串而悄悄错开。
         */
        const val PARAM_TESTS_HYPOTHESIS: String = "testsHypothesis"
    }
}

/**
 * 工具调用结果。
 *
 * [observation] 是结构化摘要，不是原文；原文放在 [sourceRef] 指向的账本里按需回源。
 * 文档口径是「日志不是上下文，结论才是」，这里是该口径在类型层面的强制。
 */
data class ToolResult(
    val tool: RcaToolName,
    val toolVersion: String,
    val status: ToolResultStatus,
    val paramsDigest: String,
    val decisionRound: Int,
    /** 结构化观察摘要。失败时为 null，失败原因放 [failureDetail]。 */
    val observation: ToolObservation? = null,
    /** 失败或缺口时的可读说明；成功时为 null。 */
    val failureDetail: String? = null,
    /** 上游请求 ID，用于本地未写回时的三态对账。 */
    val providerRequestId: String? = null,
    /** 上游原始响应摘要哈希，用于离线核对上游是否变过。 */
    val observationDigest: String? = null,
    val latencyMs: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val costUsd: java.math.BigDecimal? = null
) {
    /** 是否构成证据缺口：调用方据此决定是否把缺口写进 gaps 而不是当成反证。 */
    val isEvidenceGap: Boolean get() = status.evidenceGap

    /** 这次调用是否已经给出确定结论（有数据，或确认过合法的空）。 */
    val conclusive: Boolean get() = ToolResultStatus.isConclusive(status)
}

/**
 * 结构化 Observation。七类工具共用一个外壳，避免每种工具各造一套字段导致模型难以比较。
 */
data class ToolObservation(
    /** 一句话结论，直接进上下文。 */
    val summary: String,
    /** 时间窗，闭开区间 [from, to)。 */
    val windowFrom: java.time.LocalDateTime? = null,
    val windowTo: java.time.LocalDateTime? = null,
    /** 数据水位；落后于窗口上界时该证据应标 STALE。 */
    val timeWatermark: java.time.LocalDateTime? = null,
    /** 受影响或命中的对象标识，例如版本号、服务名、资源 token。 */
    val subjectRefs: List<String> = emptyList(),
    /** 首错点：日志类工具专有，指向最早观察到的失败位置。 */
    val firstFaultPoint: FirstFaultPoint? = null,
    /** 错误签名分布：多成员簇专用，键为签名，值为计数。 */
    val errorSignatureCounts: Map<String, Long> = emptyMap(),
    /** 分母。仅当该 Observation 用于范围性结论时必填。 */
    val denominator: Long? = null,
    /** 对照组标识，例如对照版本 4.1.3。 */
    val controlGroup: String? = null,
    /** 版本集中度：命中版本占总量的比例，0 到 1。 */
    val concentrationRatio: java.math.BigDecimal? = null,
    /** 该观察反驳了哪些假设编码；模型读 Observation 时据此修正假设。 */
    val refutesHypotheses: List<String> = emptyList(),
    /** 该观察支持了哪些假设编码。 */
    val supportsHypotheses: List<String> = emptyList(),
    /** 该观察新引入的证据缺口，必须显式回传。 */
    val newGaps: List<String> = emptyList(),
    /** 回源指针，供人工复核时取原文。 */
    val sourceRef: String
)

/**
 * 日志首错点：这条链路上最早观察到的失败位置。
 * 它优先于「客户端现象」被采信，因为客户端报错往往是下游失败的上游表现。
 */
data class FirstFaultPoint(
    val serviceName: String,
    val operationName: String,
    /** 接口或调用标识，例如资源鉴权接口路径。 */
    val endpoint: String? = null,
    /** HTTP 或业务状态码，例如 400。 */
    val statusCode: Int? = null,
    val occurredAt: java.time.LocalDateTime,
    /** 该失败点是否与现象同因的证据强度：直接/间接。 */
    val relation: FaultPointRelation = FaultPointRelation.DIRECT,
    /** 异常类名或错误码；已脱敏。 */
    val errorCode: String? = null
)

enum class FaultPointRelation(val storageCode: String) {
    /** 观测到的失败点与现象同因。 */
    DIRECT("DIRECT"),

    /** 观测到的失败点在上游，现象是其下游表现。 */
    UPSTREAM("UPSTREAM"),

    /** 观测到的失败点与现象共存但因果关系未确认。 */
    COINCIDENT("COINCIDENT");

    companion object {
        private val BY_CODE = entries.associateBy(FaultPointRelation::storageCode)

        fun fromStorage(code: String?): FaultPointRelation? = code?.let(BY_CODE::get)
    }
}

/**
 * 工具调用的统一出口。所有对外部系统的只读访问都必须经由它，以便统一施加
 * 超时、预算计量、参数摘要、结果去重和错误语义归一。
 */
interface RcaToolInvoker {
    /** 工具编码，与 [RcaToolName] 对应。 */
    val tool: RcaToolName

    /** 该工具支持的上游版本；随上游接口变更递增，落库供事后对账。 */
    val toolVersion: String

    /**
     * 执行一次只读调用。
     *
     * 实现方必须：不修改任何业务数据；把上游异常翻译成 [ToolResultStatus]；
     * 在无法判定时返回 [ToolResultStatus.UNKNOWN] 而不是抛异常穿透到编排层。
     */
    fun invoke(request: ToolRequest): ToolResult
}

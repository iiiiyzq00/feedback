package com.feedback.agent.rca.domain

import java.time.LocalDateTime

/**
 * RCA 共享状态：六块核心内容，外加一块「已在内存中算出但尚未落库」的中间产出。
 * 这是节点之间唯一的状态传递方式，也是崩溃恢复的最小快照单位。
 *
 * 六块分别解决一个具体问题，缺一块就会退化成「把对话历史丢给模型」：
 * - [problemProfile]：锚定事实。告诉模型「你在查哪个问题对象」，避免跨 Episode 混证。
 * - [mandatoryPlan]：门禁。模型不能自行决定跳过日志或堆栈。
 * - [hypotheses]：可证伪的候选集合，带支持证据和反证，而不是一段自由文本推断。
 * - [toolHistory]：防止重复取证的依据，参数摘要相同且 Observation 完整时可直接复用。
 * - [evidenceLedger]：模型只引用 evidence ID，正文留在账本里按需回源，控制上下文体积。
 * - [gapsAndBudget]：预算与缺口显式化，停止判断据此进行。
 * - [arbitration]：仲裁产出。它是「待落库」的中间结果，因此必须随快照一起走。
 *
 * 状态对象整体不可变：每次迁移用 `copy` 产生新版本并递增 [stateVersion]。这样做的好处是
 * 写回时可以拿 `stateVersion` 做乐观并发控制，旧 Worker 的过期快照会被影响行数为 0 拒绝。
 */
data class RcaSharedState(
    val taskId: Long,
    val episodeId: Long,
    /** 每次成功迁移递增；落库时作为写回条件之一。 */
    val stateVersion: Long = 0,
    val problemProfile: ProblemProfile,
    val mandatoryPlan: MandatoryEvidencePlan,
    val hypotheses: List<Hypothesis> = emptyList(),
    val toolHistory: List<ToolCallRecord> = emptyList(),
    val evidenceLedger: List<EvidenceEntry> = emptyList(),
    val gapsAndBudget: GapsAndBudget,
    val stopProposal: StopProposal? = null,
    /**
     * 仲裁产出。随快照一起落库，因此「仲裁完成但尚未持久化」这个窗口崩溃后可以直接恢复，
     * 不需要任何进程外的旁路约定。
     */
    val arbitration: RcaArbitrationOutcome? = null
) {
    /** 当前置信等级最高的活跃假设；没有活跃假设时返回 null，调用方必须处理而不是硬造一个。 */
    val leadingHypothesis: Hypothesis?
        get() = hypotheses
            .filter { it.status == HypothesisStatus.ACTIVE || it.status == HypothesisStatus.SUPPORTED }
            .maxByOrNull { it.confidence.level }

    /** 尚未排除的强反证数量。大于 0 时禁止进入正常停止。 */
    val unresolvedRefutationCount: Int
        get() = hypotheses.sumOf(Hypothesis::unresolvedRefutationCount)

    fun evidenceById(evidenceId: String): EvidenceEntry? = evidenceLedger.firstOrNull { it.evidenceId == evidenceId }

    companion object {
        /**
         * 任务创建时的初始状态：问题画像与强制证据计划**尚未产生**，由
         * [RcaNode.CONTEXT_BUILD] 确定性生成后覆盖。
         *
         * 这里不给一个「看起来像样」的默认画像，而是给显式的未构建占位（空应用、零时间窗、
         * 无日志 ID），并把预算置零。三个后果都是想要的：
         * 1. 任何节点若在上下文构建之前被误调用，行为是「什么都查不到」，
         *    而不是拿着一个编造的应用去上游取证；
         * 2. 预算置零让工具调用在覆盖之前一律被拒绝，误调用不会被记成「查了没有」；
         * 3. 占位值在人工复核与落库约束上都一眼可见，不会被误当成真实事实。
         *
         * 用可空字段表达「尚未构建」也考虑过，但它会让每个节点的每处引用都多一次判空，
         * 而那些判空分支没有有意义的处理方式——节点本来就不该在这之前运行。
         */
        fun seeded(taskId: Long, episodeId: Long, now: LocalDateTime): RcaSharedState = RcaSharedState(
            taskId = taskId,
            episodeId = episodeId,
            problemProfile = UNSET_PROFILE,
            mandatoryPlan = MandatoryEvidencePlan(emptyList()),
            gapsAndBudget = GapsAndBudget(
                stepBudget = 0,
                stepsUsed = 0,
                tokenBudget = 0,
                tokensUsed = 0,
                costBudgetUsd = java.math.BigDecimal.ZERO,
                costUsedUsd = java.math.BigDecimal.ZERO,
                deadlineAt = null,
                now = now
            )
        )

        /** 未构建占位画像。只在上下文构建之前短暂存在，见 [seeded]。 */
        private val UNSET_PROFILE: ProblemProfile = ProblemProfile(
            tenantKey = "",
            appId = "",
            problemKey = "",
            episodeGeneration = 0,
            errorSignature = null,
            occurredFrom = LocalDateTime.MIN,
            occurredTo = LocalDateTime.MIN,
            distinctDeviceCount = 0,
            distinctUserCount = 0,
            repeatCount = 0,
            crashOrAnr = false,
            hasValidLogId = false,
            symptomSummary = ""
        )
    }
}

/**
 * 问题画像：锚定事实块。由 [RcaNode.CONTEXT_BUILD] 从 Episode 成员确定性生成，不含模型推断。
 */
data class ProblemProfile(
    val tenantKey: String,
    val appId: String,
    /** 标准化问题键，与 Episode 分区键一致。 */
    val problemKey: String,
    /** Episode 当前 generation；跨 generation 的证据不得混用。 */
    val episodeGeneration: Int,
    /** 归一后的稳定错误签名；与问题键变化一样可以开启新 generation。 */
    val errorSignature: String?,
    val occurredFrom: LocalDateTime,
    val occurredTo: LocalDateTime,
    /** 影响面：去重后的设备数，不是反馈条数。 */
    val distinctDeviceCount: Int,
    /** 影响面：去重后的可信账号/联系人数，与设备数分列，禁止相加。 */
    val distinctUserCount: Int,
    /** 重复上报次数，只用于观察重复度，不计入影响面。 */
    val repeatCount: Int,
    /** 崩溃/ANR 类现象必须触发堆栈取证。 */
    val crashOrAnr: Boolean,
    /** 上游是否提供了合法日志 ID；这是「必须查日志」的开关。 */
    val hasValidLogId: Boolean,
    /**
     * 合法的日志 ID 本身。
     *
     * 与 [hasValidLogId] 分开保留：前者是「要不要查」的门禁判据，
     * 后者是「拿什么查」的入参。把它们压成一个可空字段会让
     * 「有 ID 但上游说它已过期」这种中间状态无处表达。
     */
    val logId: String? = null,
    /**
     * 所在联合调查候选的成员数。
     *
     * 多成员判定要看**簇**而不是本 Episode 的影响面：414 回放里「课程视频黑屏」
     * 「资源加载失败」「鉴权异常」是不同表述、不同设备上报的，它们进的是同一个联合调查候选，
     * 因此必须先看错误签名分布。只按本 Episode 的去重设备数判断，这一条永远触发不了。
     */
    val clusterMemberCount: Int = 0,
    val clusterId: Long? = null,
    val affectedVersions: List<String> = emptyList(),
    /**
     * 版本对照的基准版本（当前线上稳定版）。
     *
     * 由事实层给出而不是由程序「自动挑一个」：自动选对照组会让结论在不同时间不可比，
     * 也会让同一份数据跑出不同结论。为空表示事实不足，此时不能做版本集中度结论。
     */
    val controlVersion: String? = null,
    val symptomSummary: String
) {
    /** 多成员簇才要求先看错误签名分布；单条证据看分布没有意义。 */
    val multiMember: Boolean get() = clusterMemberCount > 1 || distinctDeviceCount + distinctUserCount > 1
}

/**
 * 强制证据计划：门禁块。由确定性规则生成，Agent 只能逐项完成或由仲裁节点豁免。
 *
 * 三项触发规则与文档一致：有合法日志 ID 必查日志；Crash/ANR 必取堆栈；多成员簇先看签名分布。
 * 每一项都必须区分「查询成功且确实为空」与「证据缺口」——前者可以结案，后者不能。
 */
data class MandatoryEvidencePlan(val requirements: List<MandatoryRequirement>) {
    val pending: List<MandatoryRequirement> get() = requirements.filter { it.completion == MandatoryCompletion.PENDING }

    /** 未完成且未豁免的项。非空即禁止正常停止。 */
    val blocking: List<MandatoryRequirement>
        get() = requirements.filter {
            it.completion == MandatoryCompletion.PENDING || it.completion == MandatoryCompletion.FAILED_GAP
        }

    val allSatisfiedOrWaived: Boolean get() = blocking.isEmpty()

    fun byCode(code: String): MandatoryRequirement? = requirements.firstOrNull { it.code == code }

    companion object {
        /**
         * 依据问题画像生成强制证据计划。规则必须确定性且可审计，禁止让模型参与生成。
         */
        fun from(profile: ProblemProfile): MandatoryEvidencePlan {
            val requirements = buildList {
                if (profile.hasValidLogId) {
                    add(
                        MandatoryRequirement(
                            code = "LOG_TRACE_BY_ID",
                            type = MandatoryRequirementType.LOG_TRACE,
                            triggerRule = "上游提供合法 log ID",
                            requiredReason = "有合法日志 ID 时必须先取到首错点，否则后续假设缺少现场依据"
                        )
                    )
                }
                if (profile.crashOrAnr) {
                    add(
                        MandatoryRequirement(
                            code = "CRASH_STACK",
                            type = MandatoryRequirementType.CRASH_STACK,
                            triggerRule = "现象包含 Crash 或 ANR",
                            requiredReason = "崩溃类现象必须取到堆栈指纹，否则无法区分客户端缺陷与服务端依赖故障"
                        )
                    )
                }
                if (profile.multiMember) {
                    add(
                        MandatoryRequirement(
                            code = "ERROR_SIGNATURE_DISTRIBUTION",
                            type = MandatoryRequirementType.ERROR_SIGNATURE_DISTRIBUTION,
                            triggerRule = "影响面去重后成员数大于 1",
                            requiredReason = "多成员簇必须先看错误签名分布，确认成员是否真的同因，避免代表日志以偏概全"
                        )
                    )
                }
                if (profile.affectedVersions.size > 1) {
                    add(
                        MandatoryRequirement(
                            code = "COHORT_COMPARISON",
                            type = MandatoryRequirementType.COHORT_COMPARISON,
                            triggerRule = "影响面覆盖多个版本",
                            requiredReason = "跨版本现象必须给出分母与对照组，否则版本集中度结论不可用"
                        )
                    )
                }
            }
            return MandatoryEvidencePlan(requirements)
        }
    }
}

data class MandatoryRequirement(
    val code: String,
    val type: MandatoryRequirementType,
    /** 确定性触发依据，落库供事后复核「这条为什么是必要的」。 */
    val triggerRule: String,
    val requiredReason: String,
    val completion: MandatoryCompletion = MandatoryCompletion.PENDING,
    /** 满足时指向证据账本里的 evidence ID。 */
    val satisfiedByEvidenceId: String? = null,
    /** 失败时保留失败语义，例如 NOT_FOUND、UPSTREAM_TIMEOUT；不能改写成「查过没问题」。 */
    val failureSemantic: ToolResultStatus? = null,
    /** 豁免只允许仲裁节点写，且必须留原因。 */
    val waiverReason: String? = null
)

enum class MandatoryRequirementType(val storageCode: String) {
    LOG_TRACE("LOG_TRACE"),
    CRASH_STACK("CRASH_STACK"),
    ERROR_SIGNATURE_DISTRIBUTION("ERROR_SIGNATURE_DISTRIBUTION"),
    COHORT_COMPARISON("COHORT_COMPARISON")
}

enum class MandatoryCompletion(val storageCode: String) {
    PENDING("PENDING"),

    /** 成功取到可用证据。 */
    SATISFIED("SATISFIED"),

    /** 查询确实执行了但没拿到（超时、无权限、上游缺失）：这是证据缺口，不是「没问题」。 */
    FAILED_GAP("FAILED_GAP"),

    /** 仲裁节点判定该项对本问题不必要并留了原因。 */
    WAIVED("WAIVED")
}

/**
 * 候选假设。[code] 在同一任务内稳定，[revision] 随修正递增，历史修订不覆盖。
 */
data class Hypothesis(
    val code: String,
    val statement: String,
    /** 机制链描述：从证据到结论中间靠什么机制串起来，仲裁要检查它是否成立。 */
    val mechanismNote: String? = null,
    val confidence: HypothesisConfidence = HypothesisConfidence.CANDIDATE,
    val status: HypothesisStatus = HypothesisStatus.ACTIVE,
    val supportingEvidenceIds: List<String> = emptyList(),
    val refutingEvidenceIds: List<String> = emptyList(),
    /** 已识别但尚未排除的强反证数量；大于 0 时禁止正常停止。 */
    val unresolvedRefutationCount: Int = 0,
    val revision: Long = 0,
    /** 假设合并：同一批证据且机制链不可区分时合并，被合并方保留原记录。 */
    val mergedIntoCode: String? = null
)

/**
 * 置信等级。刻意不叫「概率」：这里表达的是证据支持强度，不是统计概率。
 *
 * 关键约束：[KnowledgeAnchor] 类证据（历史案例）只能把等级推到 [MODERATE]，
 * 必要现场证据缺失时不得靠历史相似度提升等级——这是失败复盘二的直接产物。
 */
enum class HypothesisConfidence(val level: Int, val storageCode: String) {
    CANDIDATE(0, "CANDIDATE"),
    WEAK(1, "WEAK"),
    MODERATE(2, "MODERATE"),
    STRONG(3, "STRONG"),
    CONFIRMED(4, "CONFIRMED"),
    REFUTED(-1, "REFUTED");

    companion object {
        private val BY_CODE = entries.associateBy(HypothesisConfidence::storageCode)

        fun fromStorage(code: String?): HypothesisConfidence? = code?.let(BY_CODE::get)

        /** 仅凭历史案例可达到的最高等级。 */
        val HISTORY_ONLY_CEILING: HypothesisConfidence = MODERATE
    }
}

enum class HypothesisStatus(val storageCode: String) {
    ACTIVE("ACTIVE"),
    SUPPORTED("SUPPORTED"),
    REFUTED("REFUTED"),
    MERGED("MERGED"),
    DROPPED("DROPPED")
}

/**
 * 一次工具调用的记录。恢复时按 [toolName] + [paramsDigest] 查找是否已有完整 Observation，
 * 命中则复用并标记 [replayedFromId]，避免重复取证与重复计费。
 */
data class ToolCallRecord(
    /** 落库主键；恢复时用它回指被复用或重放的那一条。 */
    val id: Long = 0,
    val toolName: String,
    val toolVersion: String,
    /** 归一化参数摘要，SHA-256 十六进制小写。 */
    val paramsDigest: String,
    val decisionRound: Int,
    val status: ToolResultStatus,
    /** 成功但本地未写回时用上游 request ID 对账；未知不能当作空结果。 */
    val providerRequestId: String? = null,
    val localCommitState: LocalCommitState = LocalCommitState.COMMITTED,
    val observationDigest: String? = null,
    /** 结构化 Observation 摘要，随记录落库；正文放证据账本按需回源。 */
    val observationSummary: String? = null,
    val evidenceIds: List<String> = emptyList(),
    val replayedFromId: Long? = null,
    val latencyMs: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val costUsd: java.math.BigDecimal? = null
)

enum class LocalCommitState(val storageCode: String) {
    PENDING("PENDING"),
    COMMITTED("COMMITTED"),

    /** 上游结果未知：必须对账，禁止当成空结果或失败。 */
    UNKNOWN("UNKNOWN");

    companion object {
        private val BY_CODE = entries.associateBy(LocalCommitState::storageCode)

        /**
         * 从库里的编码还原。认不出来时**返回 null 而不是兜底成 PENDING**：
         * 这一列的取值是恢复判定的输入，认错一个编码就等于把一条无法判定的记录
         * 悄悄降级成「未落定，可以重放」，而这两者的处置正好相反。
         * 调用方必须显式决定认不出来时怎么办。
         */
        fun fromStorage(code: String?): LocalCommitState? = code?.let(BY_CODE::get)
    }
}

/**
 * 证据账本条目。[coverageType] 是范围性结论能否成立的关键：
 * 单点日志只能证明被查询的那一个 Episode，要讲「故障集中在新版本」必须引用
 * [EvidenceCoverage.COHORT] 级证据（带分母与对照组）。
 */
data class EvidenceEntry(
    val evidenceId: String,
    val type: EvidenceType,
    val sourceTool: String,
    /** 回源指针；正文不进模型上下文。 */
    val sourceRef: String,
    val coverageType: EvidenceCoverage,
    /** 证据覆盖对象范围，仲裁按它比对目标 Episode。 */
    val coverageScope: String,
    /**
     * 证据指向的主体标识：日志类工具给首错点所在服务，发布变更给「组件、版本」，
     * 版本对照给版本号，崩溃聚合给堆栈指纹，等等。
     *
     * 保留它是因为**稳定签名的组件字段只能从这里推**：账本里没有一级的「组件」字段，
     * 而组件是 Incident 关联与知识检索的键之一，拿错会让同一条经验分裂成多条。
     * 各工具的标识含义不同，因此「哪些能当组件用」由工具层声明
     * （见 `EvidenceSourceRef.componentCandidates`），而不是在这里猜。
     */
    val subjectRefs: List<String> = emptyList(),
    val numerator: Long? = null,
    val denominator: Long? = null,
    val controlGroup: String? = null,
    val observedFrom: LocalDateTime? = null,
    val observedTo: LocalDateTime? = null,
    /** 数据水位：迟到数据产生 correction 事件，不覆盖历史结论。 */
    val timeWatermark: LocalDateTime? = null,
    val permissionScope: PermissionScope = PermissionScope.EPISODE,
    val contentDigest: String,
    val summary: String,
    val revision: Long = 0
) {
    /** 范围性证据是否够格：必须同时有分母和对照组。 */
    val cohortComplete: Boolean
        get() = coverageType != EvidenceCoverage.COHORT ||
            (denominator != null && denominator > 0 && controlGroup != null)

    /** 这条证据是否足以支撑范围性（RANGE）结论。 */
    val usableForRangeClaim: Boolean get() = coverageType == EvidenceCoverage.COHORT && cohortComplete
}

enum class EvidenceType(val storageCode: String) {
    LOG_TRACE("LOG_TRACE"),
    FIRST_FAULT_POINT("FIRST_FAULT_POINT"),
    ERROR_SIGNATURE_DISTRIBUTION("ERROR_SIGNATURE_DISTRIBUTION"),
    RELEASE_CHANGE("RELEASE_CHANGE"),
    VERSION_COHORT("VERSION_COHORT"),
    SERVICE_HEALTH("SERVICE_HEALTH"),
    CRASH_STACK("CRASH_STACK"),
    CONTENT_STATUS("CONTENT_STATUS"),
    HISTORICAL_CASE("HISTORICAL_CASE")
}

enum class EvidenceCoverage(val storageCode: String) {
    /** 单点证据：只覆盖被直接观测的一个 Episode。 */
    EPISODE("EPISODE"),

    /** 队列证据：带分母与对照组，可支撑范围性结论。 */
    COHORT("COHORT"),

    /** 簇级证据：覆盖一个联合调查候选内的分布。 */
    CLUSTER("CLUSTER")
}

enum class PermissionScope(val storageCode: String) {
    /** 聚合指标，不含个体可识别信息。 */
    AGGREGATE("AGGREGATE"),
    EPISODE("EPISODE"),
    RAW_LOG("RAW_LOG"),
    RAW_ATTACHMENT("RAW_ATTACHMENT")
}

/**
 * 缺口与预算块。缺口必须显式记录，因为「没查」和「查了没有」在结论强度上完全不同。
 */
data class GapsAndBudget(
    val stepBudget: Int,
    val stepsUsed: Int,
    val tokenBudget: Int,
    val tokensUsed: Int,
    val costBudgetUsd: java.math.BigDecimal,
    val costUsedUsd: java.math.BigDecimal,
    val deadlineAt: LocalDateTime?,
    val now: LocalDateTime,
    /** 已确认的证据缺口描述，例如「兼容开关状态尚未确认」。 */
    val knownGaps: List<String> = emptyList()
) {
    val stepsRemaining: Int get() = (stepBudget - stepsUsed).coerceAtLeast(0)
    val tokensRemaining: Int get() = (tokenBudget - tokensUsed).coerceAtLeast(0)
    val costRemainingUsd: java.math.BigDecimal get() = costBudgetUsd.subtract(costUsedUsd)

    val deadlineExceeded: Boolean get() = deadlineAt != null && now.isAfter(deadlineAt)
    val stepsExhausted: Boolean get() = stepsRemaining <= 0
    val tokensExhausted: Boolean get() = tokensRemaining <= 0
    val costExhausted: Boolean get() = costRemainingUsd.signum() <= 0

    /** 任一预算耗尽即不可再发起新的工具调用。 */
    val exhausted: Boolean get() = stepsExhausted || tokensExhausted || costExhausted || deadlineExceeded

    /** 依据预算状态推导停止原因；预算未耗尽返回 null，由其他检查决定。 */
    fun exhaustionStopReason(): RcaStopReason? = when {
        deadlineExceeded -> RcaStopReason.DEADLINE_EXCEEDED
        stepsExhausted -> RcaStopReason.MAX_STEPS
        else -> if (tokensExhausted || costExhausted) RcaStopReason.BUDGET_EXHAUSTED else null
    }
}

/**
 * 模型提交的结构化停止提案。模型只能「建议」，最终由编排器四项检查落定，
 * 这一分层是「AI 提假设、后端做门禁」的具体落点。
 */
data class StopProposal(
    val reason: RcaStopReason,
    /** 模型自述依据，仅用于审计展示，不参与判定。 */
    val rationale: String,
    val proposedConfidence: HypothesisConfidence,
    val leadingHypothesisCode: String?,
    val knownGaps: List<String> = emptyList()
)

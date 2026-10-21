package com.feedback.agent.rca.domain

import java.time.LocalDateTime

/**
 * Episode 归因分级。五档不是「置信度百分比」，而是**证据覆盖关系的分类**，
 * 这是整套设计里最容易被讲错的一处：
 *
 * - [DIRECT_CONFIRMED]：代表证据本身就是这个 Episode 的现场证据（例如查的就是它的日志）。
 * - [SUPPORTED_INHERITED]：同簇内其他成员的现场证据 + 机制链成立，可继承。
 * - [AMBIGUOUS]：证据指向两个及以上无法区分的机制，必须人工裁决。
 * - [UNATTRIBUTED]：证据不足以归因，保留为未归因，不强行挂 Incident。
 * - [REJECTED]：证据明确反驳了该归因。
 *
 * 只有前两档允许自动关联 Incident；其余档一律不自动关联，人工处理。
 * 「代表日志只直接证明被查询的那一个 Episode」——这条约束写在
 * [autoLinkPermitted] 里，而不是散落在调用点。
 */
enum class EpisodeAttributionLevel(val storageCode: String, val rank: Int) {
    DIRECT_CONFIRMED("DIRECT_CONFIRMED", 4),
    SUPPORTED_INHERITED("SUPPORTED_INHERITED", 3),
    AMBIGUOUS("AMBIGUOUS", 2),
    UNATTRIBUTED("UNATTRIBUTED", 1),
    REJECTED("REJECTED", 0);

    /** 是否允许自动关联到 Incident。仅前两档为 true。 */
    val autoLinkPermitted: Boolean
        get() = this == DIRECT_CONFIRMED || this == SUPPORTED_INHERITED

    /** 是否算作「已给出可执行归因」。AMBIGUOUS 及以下需要人工介入。 */
    val actionable: Boolean get() = autoLinkPermitted

    companion object {
        private val BY_CODE = entries.associateBy(EpisodeAttributionLevel::storageCode)

        fun fromStorage(code: String?): EpisodeAttributionLevel? = code?.let(BY_CODE::get)
    }
}

/**
 * 稳定根因签名。
 *
 * 只包含「换了故障实例也不会变」的部分：租户或依赖范围、组件、故障类型、错误签名、接口类型。
 *
 * 影响面、时间重叠度、版本分布**不能进签名**——它们是动态关联特征，会随时间变化。
 * 一旦写进签名，新反馈会因为时间不同而匹配不上已有 Incident，直接影响面统计就会不断分裂。
 * 这个区分是「新反馈并入同一 Incident」能成立的前提。
 */
data class RootCauseSignature(
    /** 租户或依赖范围；当前单租户部署下为 DEFAULT，但维度保留。 */
    val scope: String,
    /** 组件，例如 resource-auth-service。 */
    val component: String,
    /** 故障类型，例如 PARAMETER_INCOMPATIBILITY。 */
    val faultType: String,
    /** 归一后的稳定错误签名。 */
    val errorSignature: String,
    /** 接口类型，例如 HTTP_400。 */
    val endpointType: String
) {
    /** 用于落库与比对的规范字符串；顺序固定，避免同义签名产生两行。 */
    val canonical: String get() = listOf(scope, component, faultType, errorSignature, endpointType).joinToString("|")

    companion object {
        /** 规范字符串的分隔符，落库与解析必须共用同一常量。 */
        const val SEPARATOR: String = "|"

        fun parse(canonical: String): RootCauseSignature? {
            val parts = canonical.split(SEPARATOR)
            if (parts.size != 5) return null
            return RootCauseSignature(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }
}

/**
 * 一条归因记录。追加式：升级或降级都新增一行并递增 [revision]，历史行标记为被取代。
 * 不允许原地更新，原因是归因会被告警卡片和 Incident 影响面引用，改写历史会让已发出的
 * 通知与当前数据自相矛盾。
 */
data class EpisodeAttribution(
    val episodeId: Long,
    val rcaTaskId: Long,
    val signature: RootCauseSignature,
    val level: EpisodeAttributionLevel,
    val revision: Long,
    /** 支撑证据 ID 列表，必须可回源。 */
    val basisEvidenceIds: List<String>,
    /** 说明该证据为何覆盖不到其他 Episode；AMBIGUOUS 及以上必填。 */
    val coverageNote: String?,
    val incidentId: Long? = null,
    val decidedAt: LocalDateTime,
    val decidedBy: Long? = null
) {
    init {
        require(basisEvidenceIds.isNotEmpty()) { "归因必须至少引用一条证据" }
        require(level.autoLinkPermitted || incidentId == null) {
            "仅 DIRECT_CONFIRMED 与 SUPPORTED_INHERITED 可以关联 Incident"
        }
        require(level == EpisodeAttributionLevel.DIRECT_CONFIRMED || !coverageNote.isNullOrBlank()) {
            "非直接确认的归因必须说明证据覆盖范围"
        }
    }

    /** 落库时是否置自动关联标记。 */
    val autoLinked: Boolean get() = level.autoLinkPermitted
}

/**
 * 引用支持校验用的 claim 类型。
 *
 * [RANGE] 是关键：它表达「故障集中在新版本」这类跨样本结论，必须引用带分母与对照组的
 * cohort 证据。引用存在但粒度不足，仍然判为不支持——这正是失败复盘一。
 */
enum class ClaimType(val storageCode: String, val requiredCoverage: EvidenceCoverage) {
    /** 单点事实陈述，例如「Trace 中最早失败在资源鉴权服务」。 */
    EPISODIC("EPISODIC", EvidenceCoverage.EPISODE),

    /** 范围性结论，例如「失败率显著集中在新版本」。必须 cohort 级证据。 */
    RANGE("RANGE", EvidenceCoverage.COHORT),

    /** 因果链陈述，例如「参数改名导致鉴权失败」。需要有机制链，最低要求簇级证据。 */
    CAUSAL("CAUSAL", EvidenceCoverage.CLUSTER),

    /** 机制说明，用于解释为什么该因果链成立。 */
    MECHANISM("MECHANISM", EvidenceCoverage.EPISODE)
}

enum class ClaimSupportVerdict(val storageCode: String) {
    PENDING("PENDING"),

    /** 证据类型与 claim 类型匹配，且证据确实覆盖目标对象。 */
    SUPPORTED("SUPPORTED"),

    /** 证据与 claim 无关，或引用的 evidence ID 不存在。 */
    UNSUPPORTED("UNSUPPORTED"),

    /** 证据相关但覆盖范围超出它实际能证明的范围。 */
    PARTIAL_OVERSCOPED("PARTIAL_OVERSCOPED")
}

/**
 * claim 与证据的配对记录。仲裁节点逐对校验并把判定结果落库，
 * 这样「引用支持正确率」才能由程序复算，而不是靠人工数。
 */
data class ClaimEvidencePair(
    val claimCode: String,
    val claimType: ClaimType,
    val claimText: String,
    val evidenceId: String,
    /** 校验时快照证据的覆盖类型，便于事后复核判定是否用错了粒度。 */
    val evidenceCoverage: EvidenceCoverage,
    val verdict: ClaimSupportVerdict = ClaimSupportVerdict.PENDING,
    val verdictReason: String? = null,
    /** 双人复核：任一方不同意即不得置为 AGREED。 */
    val reviewerA: Long? = null,
    val reviewerB: Long? = null
) {
    /** 依据 claim 类型与证据粒度推导出的判定；[ClaimSupportVerdict.PENDING] 表示尚缺证据实体。 */
    fun evaluate(evidenceExists: Boolean, evidenceCoversTarget: Boolean): ClaimEvidencePair {
        val reason: String
        val verdict = when {
            !evidenceExists -> {
                reason = "引用的证据不在账本中，无法回源"
                ClaimSupportVerdict.UNSUPPORTED
            }

            claimType.requiredCoverage == EvidenceCoverage.COHORT && evidenceCoverage != EvidenceCoverage.COHORT -> {
                reason = "范围性结论引用了单点级证据；需要带分母与对照组的 cohort 证据"
                ClaimSupportVerdict.PARTIAL_OVERSCOPED
            }

            evidenceCoverage.rank < claimType.requiredCoverage.rank -> {
                reason = "证据粒度低于该 claim 类型的最低要求"
                ClaimSupportVerdict.PARTIAL_OVERSCOPED
            }

            !evidenceCoversTarget -> {
                reason = "证据存在但未覆盖目标 Episode"
                ClaimSupportVerdict.UNSUPPORTED
            }

            else -> {
                reason = "证据类型匹配且覆盖目标对象"
                ClaimSupportVerdict.SUPPORTED
            }
        }
        return copy(verdict = verdict, verdictReason = reason)
    }
}

/**
 * 证据覆盖粒度的高低。数值用于「粒度是否够」的比较，不表示质量优劣。
 *
 * 刻意公开：这条排序同时被 claim 逐对判定（[ClaimEvidencePair.evaluate]）与仲裁节点的
 * claim 级粒度门禁使用。两处各写一份排序，迟早会出现「逐对判够、门禁判不够」
 * 这种自相矛盾的落库结果。
 */
val EvidenceCoverage.rank: Int
    get() = when (this) {
        EvidenceCoverage.EPISODE -> 1
        EvidenceCoverage.CLUSTER -> 2
        EvidenceCoverage.COHORT -> 3
    }

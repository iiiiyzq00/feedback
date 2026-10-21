package com.feedback.agent.rca.episode

import com.feedback.agent.rca.domain.EpisodeSessionState
import com.feedback.agent.rca.domain.ImpactCounts
import com.feedback.agent.rca.domain.TrustedIdentityLevel
import java.time.LocalDateTime
import java.util.Base64

/**
 * 身份指纹在会话化层的统一编码：Base64 URL 安全、无填充。
 *
 * 指纹是二进制，而「已见过的身份集合」要按值比较，所以必须有**唯一一种**编码。
 * 库里存的是 BINARY(32)、SQL 侧的 join 会把它读成字节，而领域层拿它当字符串键用；
 * 三处各自实现一遍编码，只要有一处换了写法，同一个账号就会在「已见过集合」里
 * 变成两个条目，`distinct_user_count` 随之偏大——而且不会有任何地方报错。
 *
 * 因此编码定义在这里一次，[EpisodeSessionizer] 与 [EpisodeSessionStore] 的实现都调它。
 */
internal fun ByteArray.toIdentityKey(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

/**
 * 端点键的构造口径：来源渠道 + 分隔符 + 来源原始 ID。
 *
 * 同样必须只有一种写法。真实实现里它由 SQL 的 `CONCAT` 拼出（见
 * `EpisodeSessionMapper.xml` 的 `findMemberEndpointKeys`），而接入层构造
 * [RawFeedbackSignal.endpointKey] 时走这里——两边不一致，「重复端点」的判定
 * 就会在库与内存替身之间分叉，表现为同一个端点在一处算重复、在另一处算新端点。
 *
 * 分隔符取 ASCII 31（US，单元分隔符）而不是 `/` 或 `:`：后者可能出现在来源原始 ID 里，
 * 于是一对 `("a/b", "c")` 与 `("a", "b/c")` 会拼出同一个键，
 * 把两条无关信号判成重复端点。ASCII 31 是不可打印控制字符，不会出现在任何渠道名或业务 ID 中。
 */
object EpisodeEndpointKey {

    /** 与 `EpisodeSessionMapper.xml` 里 `CHAR(31)` 一致。 */
    const val SEPARATOR: Char = '\u001F'

    fun of(sourceChannel: String, sourceRecordId: String): String =
        "$sourceChannel$SEPARATOR$sourceRecordId"
}

/**
 * Episode 分区键。
 *
 * 与 `uk_episode_partition (tenant_key, app_id, problem_key, identity_fingerprint, generation)`
 * 一一对应，**不含设备**：按设备分区会把「同一设备的不同问题」错误合并成一个会话，
 * 而不同设备上的同一种故障会被拆成多个。分区靠问题键与身份，不靠设备。
 *
 * 身份指纹用二进制承载（与库里的 BINARY(32) 一致），比较与哈希按十六进制做：
 * ByteArray 的默认 equals 是引用比较，直接放进 data class 会让「同样的指纹」被当成两个分区。
 */
class EpisodePartition(
    val tenantKey: String,
    val appId: String,
    val problemKey: String,
    val identityFingerprint: ByteArray
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpisodePartition) return false
        return tenantKey == other.tenantKey &&
            appId == other.appId &&
            problemKey == other.problemKey &&
            identityFingerprint.contentEquals(other.identityFingerprint)
    }

    override fun hashCode(): Int =
        listOf(tenantKey, appId, problemKey, identityFingerprint.toHex()).hashCode()

    /** 日志与测试断言里的分区标签；十六进制而不是原文，避免不可见字符进日志。 */
    val label: String get() = "$tenantKey/$appId/$problemKey/${identityFingerprint.toHex()}"

    override fun toString(): String = "EpisodePartition($label)"
}

/**
 * 已有问题对象的快照。
 *
 * 只包含会话化判定与计数需要的字段，不把整个 Episode 拖进内存：判定是纯函数，
 * 它需要的是**事实**（计数、时间、身份等级、已见集合），而不是一个可变的实体。
 * 三个已见集合在这里出现而不是让判定器自己去查：它们是「已经发生过什么」的唯一来源，
 * 查一次、传进去，判定过程就不会再产生任何 I/O。
 */
data class EpisodeSnapshot(
    val episodeId: Long,
    val generation: Int,
    val identityLevel: TrustedIdentityLevel,
    /** 身份指纹的 Base64 URL 安全表示，与 [com.feedback.agent.rca.domain.EpisodeSignal] 同一编码。 */
    val identityFingerprintKey: String,
    val errorSignature: String?,
    /** 当前会话状态。参与会话状态推导：它必须能读到，否则状态无法做到只升不降。 */
    val sessionState: EpisodeSessionState,
    val sessionOpenedAt: LocalDateTime,
    val sessionLastEventAt: LocalDateTime,
    val memberRevision: Long,
    val impact: ImpactCounts,
    /** 该会话内已出现过的端点键，用于重复端点判定。 */
    val endpointKeys: Set<String> = emptySet(),
    /** 已出现过的设备指纹，用于设备去重。 */
    val deviceFingerprints: Set<String> = emptySet(),
    /** 已出现过的可信身份指纹，用于用户去重。 */
    val identityFingerprints: Set<String> = emptySet()
)

/** 新建 Episode 的内容。计数从零开始，由第一条信号的 [ImpactCounts] 直接给出。 */
data class NewEpisodeSpec(
    val partition: EpisodePartition,
    val generation: Int,
    val identityLevel: TrustedIdentityLevel,
    val errorSignature: String?,
    val sessionState: EpisodeSessionState,
    val sessionOpenedAt: LocalDateTime,
    val sessionLastEventAt: LocalDateTime,
    val impact: ImpactCounts,
    val memberRevision: Long,
    val policyVersion: String
)

/**
 * 追加一条成员。
 *
 * [membershipRevision] 由调用方给出（来自 [com.feedback.agent.rca.domain.EpisodeJoinDecision]）：
 * 追加式修订号是**会话化判定的一部分**，不是持久化层自己算的序号。
 */
data class EpisodeMemberAppend(
    val episodeId: Long,
    val sourceId: Long,
    val membershipRevision: Long,
    val identityLevel: TrustedIdentityLevel,
    val joinReason: String,
    val eventTime: LocalDateTime,
    val duplicateEndpoint: Boolean
)

/**
 * 成员追加结果。
 *
 * [ALREADY_PRESENT] 不是错误，是**幂等命中**：接入链路是至少一次投递，
 * 同一条 source 被重投时必须什么都不做。它必须由持久化层判定而不是调用方先查一次——
 * 「先查后插」之间没有东西保证候选仍然是候选，而唯一键是唯一能封住这个窗口的东西。
 */
enum class MemberAppendOutcome { INSERTED, ALREADY_PRESENT }

/** 一条原始信号在身份表里登记过的身份指纹。 */
data class FingerprintFact(val type: String, val fingerprint: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FingerprintFact) return false
        return type == other.type && fingerprint.contentEquals(other.fingerprint)
    }

    override fun hashCode(): Int = listOf(type, fingerprint.toHex()).hashCode()
}

/**
 * 成员背后那条原始信号的事实：身份指纹与端点键。
 *
 * 这三样东西**不在成员行上**，成员行只存判定结果（`identity_level`、`is_duplicate_endpoint`）：
 * 库里它们分别来自 `t_feedback_identity`（按 source 登记的身份指纹）与 `t_feedback_source`
 * （来源渠道 + 来源原始 ID 组成的端点键）。去重集合是从事实表回查出来的，
 * 而不是在 Episode 行上冗余一份副本——副本一旦与事实表不一致，
 * 「影响面按去重后计」就再也无法从任何一张表上验证。
 */
data class MemberSourceFacts(
    val fingerprints: List<FingerprintFact> = emptyList(),
    val endpointKey: String? = null
)

/**
 * 按 source 取事实。**只有内存替身需要它**：真实实现的这一步就是 SQL 里的那次 join。
 *
 * 之所以把它定义成端口而不是让替身自己去猜，是因为它决定了两个去重集合的内容，
 * 而这两个集合直接产出 `distinct_user_count` 与 `distinct_device_count`。
 * 让替身与 SQL 各自解释一遍「哪些类型算人、哪些算设备」，两边迟早会不一样，
 * 而套件验证的就不再是生产会走的那条路。
 */
fun interface MemberSourceFactsPort {

    fun factsOf(sourceId: Long): MemberSourceFacts

    companion object {
        /** 无任何事实的默认实现：拿不到事实时不计数，而不是当成新身份计数。 */
        val EMPTY: MemberSourceFactsPort = MemberSourceFactsPort { MemberSourceFacts() }
    }
}

/**
 * 成员事实里参与去重集合的身份类型，取值与 `t_feedback_identity.identity_type` 一致。
 *
 * 这条规则实际有三处实现：[MemberSourceFactsPort] 的替身、SQL 侧 join 的类型过滤、
 * 以及适配器把行翻译成集合时。改动只落到其中一处，影响面就会在库与替身之间分叉，
 * 而分叉的表现是「计数不同」，不是报错。因此在这里定义一次，SQL 侧以注释指回本对象。
 */
object MemberFactTypes {

    /** 设备类型。设备影响面只按它去重，账号指纹不能拿来当设备指纹凑一个数。 */
    const val DEVICE: String = "DEVICE_SN"

    /**
     * 用户类型。三种类型各自成键，**不跨类型归并**：
     * 同一个人的账号与邮箱是两个指纹，把它们的集合合并会凭空减少用户数，
     * 而那需要先证明「这两个指纹属于同一个人」——那是身份归并问题，不是会话化问题。
     */
    val USER: Set<String> = setOf("ACCOUNT", "PHONE", "EMAIL")
}

/** Episode 行上的增量更新。 */
data class EpisodeUpdate(
    val impact: ImpactCounts,
    val sessionState: EpisodeSessionState,
    val sessionLastEventAt: LocalDateTime,
    /** 追加式成员修订号；[com.feedback.agent.rca.domain.EpisodeMembershipAction.JOIN] 时保持不变。 */
    val memberRevision: Long,
    /**
     * 补写错误签名。只在该 Episode 尚未归一、而这条信号已经归一时使用。
     * 为 null 表示不动这一列——**不能**用 null 去覆盖已有签名。
     */
    val fillErrorSignature: String? = null
)

/**
 * Episode 与成员表的持久化端口。
 *
 * 只暴露会话化必需的四个动作，没有 CRUD 全集：口子开大之后，
 * 会话化之外的地方也会顺手用它改 Episode 行，而计数与修订号一旦被别处改过，
 * 「影响面按去重后计」这个口径就再也无法从代码上看出来了。
 */
interface EpisodeSessionStore {

    /** 按分区找当前活跃的 Episode（该分区最大的 generation）；找不到返回 null。 */
    fun findActive(partition: EpisodePartition): EpisodeSnapshot?

    /** 新建 Episode，返回落库主键。 */
    fun createEpisode(spec: NewEpisodeSpec): Long

    /**
     * 追加成员。
     *
     * 实现必须在**同一次写**里让唯一键 `uk_episode_member_revision (episode_id, source_id, membership_revision)`
     * 生效，并把冲突翻译成 [MemberAppendOutcome.ALREADY_PRESENT]。
     */
    fun appendMember(append: EpisodeMemberAppend): MemberAppendOutcome

    /** 更新 Episode 行上的计数、会话状态、末次事件时间与修订号。 */
    fun updateEpisode(episodeId: Long, update: EpisodeUpdate)
}

/** 工作项建立结果。[created] 为 false 表示该 Episode 已有工作项，本次只是复用。 */
data class WorkItemOutcome(val workItemId: Long, val created: Boolean)

/** RCA 任务投递结果。[created] 为 false 表示幂等键已存在，本次未重复投递。 */
data class TaskEnqueueOutcome(val taskKey: String, val created: Boolean)

/**
 * 工作项与 RCA 任务的投递端口。
 *
 * 与 [EpisodeSessionStore] 分开，是因为它们的**失败代价不同**：
 * Episode 与成员写失败会让影响面失真，必须整条信号回滚；
 * 工作项或任务投递失败只是「这个 Episode 暂时没人查」，理论上可以由补偿任务重投。
 *
 * 端口在这里分开，是为了让这两种代价**有被分别处置的余地**。不过本次实现是
 * 整条接入一个事务（见 [EpisodeIntakeService.accept]）：投递失败会连带回滚已经算好的影响面，
 * 而不是留下一个「Episode 已存在、工作项待补」的中间态。
 * 那是因为后者需要真的存在一个「扫出没有工作项的 Episode」的补偿任务才有意义——
 * 没有它的话，所谓「由补偿任务重投」只是一句口号，中间态会一直躺在库里没人管。
 */
interface WorkItemDispatchPort {

    /**
     * 为 Episode 建立工作项。**不依赖是否告警**：没有告警的反馈也要能先行 RCA，
     * 所以这张表与告警实例之间是软关联、可空。
     */
    fun ensureWorkItem(episodeId: Long, firstSeenAt: LocalDateTime, lastSeenAt: LocalDateTime): WorkItemOutcome

    /** 触达工作项：更新末次出现时间，会话被重开时递增重开次数。 */
    fun touchWorkItem(episodeId: Long, lastSeenAt: LocalDateTime, reopened: Boolean)

    /**
     * 投递 RCA 任务。
     *
     * [taskKey] 是业务幂等键，由调用方按「Episode + 策略版本」拼出；
     * 实现必须让它唯一（`uk_rca_agent_task_key`），把重复投递翻译成 [TaskEnqueueOutcome.created] = false。
     */
    fun enqueueRcaTask(episodeId: Long, taskKey: String, clusterId: Long?): TaskEnqueueOutcome
}

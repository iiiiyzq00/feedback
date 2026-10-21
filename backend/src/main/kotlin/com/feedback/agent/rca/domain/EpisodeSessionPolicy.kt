package com.feedback.agent.rca.domain

import java.time.Duration
import java.time.LocalDateTime

/**
 * 会话化策略。策略是版本化的对象而不是散落的常量：同一批原始信号在不同策略版本下
 * 可能落到不同 generation，事后复核必须能知道当时用的是哪一版规则。
 *
 * 因此 [policyVersion] 会随 Episode 一起落库，任何阈值调整都要发新版本号，
 * 不允许直接改旧版本的数值。
 */
data class EpisodeSessionPolicy(
    /** 策略版本，落库到 `t_feedback_episode.policy_version`。 */
    val policyVersion: String,
    /**
     * 不活跃间隔阈值：两个相邻事件的时间差超过它即认为会话已经断开。
     * 默认 30 分钟，与文档口径一致。
     */
    val inactivityGap: Duration = Duration.ofMinutes(30),
    /**
     * 会话关闭后的重开窗口：迟到事件在该窗口内允许追加为新的 membership revision，
     * 超过窗口只能开新 generation。窗口必须显式存在，否则迟到数据会静默改写历史结论。
     */
    val reopenWindow: Duration = Duration.ofHours(24),
    /**
     * 同一身份在 [inactivityGap] 内允许的最大事件数，用于识别异常刷量。
     * 超限不丢数据，只标记为重复上报并单独计数。
     *
     * **当前未生效。** 判定需要「这一条身份在本窗口内已经有多少条事件」，
     * 而 [EpisodeSnapshot][com.feedback.agent.rca.episode.EpisodeSnapshot]
     * 只带去重**后**的身份集合、不带逐身份的事件计数，`EpisodeSessionizer` 因此无从判断超限。
     * 落地前需要先让快照带上它（或在成员表上按身份做一次窗口内计数）。
     * 在此之前这个字段只是策略声明的一部分，**不参与任何判定**——
     * 写在这里是为了让「阈值已配置」与「阈值已生效」在代码上不会被混为一谈：
     * 一个看起来被设过值的阈值，比一个明显缺失的阈值更容易让人误以为它已经在起作用。
     */
    val maxEventsPerSession: Int = 200
) {
    companion object {
        /**
         * 当前生效的策略版本。改动任何阈值都必须同时发新版本号。
         *
         * **长度上限是 20，不是风格选择**：本值落进 `t_feedback_episode.policy_version
         * VARCHAR(20)`（V242:36）。原值是 `episode-session/1.0.0`（21 字符），
         * 真库上第一次接入就以 `Data too long for column 'policy_version'` 失败。
         * 离线会话化套件用的是内存替身，列宽不在它的检查面内，所以这个错配一直没被发现。
         *
         * 迁移已应用且不可回改，因此由本常量让位；版本号本身没有历史数据依赖
         * （写入前 `t_feedback_episode` 为 0 行）。
         * 同类约束还有 `t_rca_agent_task.prompt_version VARCHAR(20)`（V242:138），
         * 生产侧给它赋值时必须同样不超过 20 字符。
         */
        const val CURRENT_VERSION: String = "episode-session/1.0"

        val DEFAULT: EpisodeSessionPolicy = EpisodeSessionPolicy(policyVersion = CURRENT_VERSION)
    }
}

/**
 * 可信身份等级。等级高的身份可以代表更多信号，但**不同等级之间不能互相冒充**：
 * 一个匿名上报不能因为设备指纹碰巧相同就并进账号级会话，否则影响面统计会被污染。
 *
 * 优先级：账号 > 授权设备 > 渠道联系人 > 匿名。
 * [rank] 数值越大越可信；只有 [rank] 相同的信号才允许在同一等级内聚合。
 */
enum class TrustedIdentityLevel(val storageCode: String, val rank: Int) {
    /** 已登录可信账号。 */
    ACCOUNT("ACCOUNT", 3),

    /** 经过授权的设备（设备指纹 + 授权凭证）。 */
    AUTHORIZED_DEVICE("AUTHORIZED_DEVICE", 2),

    /** 渠道联系人，例如工单登记的手机号或邮箱。 */
    CHANNEL_CONTACT("CHANNEL_CONTACT", 1),

    /** 匿名：只能做弱会话化，影响面统计中单独成列。 */
    ANONYMOUS("ANONYMOUS", 0);

    companion object {
        private val BY_CODE = entries.associateBy(TrustedIdentityLevel::storageCode)

        fun fromStorage(code: String?): TrustedIdentityLevel? = code?.let(BY_CODE::get)

        /** 多条信号的身份等级不一致时取最高的那一档作为会话身份等级。 */
        fun highestOf(levels: Collection<TrustedIdentityLevel>): TrustedIdentityLevel =
            levels.maxByOrNull(TrustedIdentityLevel::rank) ?: ANONYMOUS
    }
}

/**
 * 一条等待归并的原始信号。这是会话化的输入，来自接入层已经落库的事实，不再回查上游。
 */
data class EpisodeSignal(
    val sourceId: Long,
    val tenantKey: String,
    val appId: String,
    /** 标准化问题键，由归一化环节产出。 */
    val problemKey: String,
    /** 归一后的稳定错误签名；为空表示尚未归一。 */
    val errorSignature: String?,
    val identityLevel: TrustedIdentityLevel,
    /** HMAC-SHA256 身份指纹，与 t_feedback_identity 同口径。 */
    val identityFingerprint: ByteArray,
    /** 事件时间，窗口重算与不活跃判定的基准；不用入库时间代替。 */
    val eventTime: LocalDateTime,
    /**
     * 设备指纹，仅在能确认设备身份时提供（[TrustedIdentityLevel.AUTHORIZED_DEVICE]）。
     * 设备影响面只按它去重；账号级信号若拿不到设备指纹，就不增加设备数，
     * 而不是把账号指纹当成设备指纹凑一个数出来。
     */
    val deviceFingerprint: String? = null,
    /** 同一端点重复上报的判定键，例如 app_key + client_feedback_id。 */
    val endpointKey: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpisodeSignal) return false
        return sourceId == other.sourceId
    }

    override fun hashCode(): Int = sourceId.hashCode()
}

/**
 * 归并判定结果。[joinReason] 说明「为什么并进来」，这句话在事后复核时是必需的：
 * 被错误合并的信号只能通过原因码定位到是哪条规则放行了它。
 */
data class EpisodeJoinDecision(
    val sourceId: Long,
    val action: EpisodeMembershipAction,
    val joinReason: EpisodeJoinReason?,
    /** 追加式修订号；[EpisodeMembershipAction.APPEND_REVISION] 时递增。 */
    val membershipRevision: Long,
    /** 该信号是否被判定为重复端点。重复信号仍然保留，只是不重复计入影响面。 */
    val duplicateEndpoint: Boolean,
    val explanation: String
)

enum class EpisodeMembershipAction(val storageCode: String) {
    /** 作为新成员加入当前 generation。 */
    JOIN("JOIN"),

    /** 在当前 generation 内追加一次修订（迟到数据或修正）。 */
    APPEND_REVISION("APPEND_REVISION"),

    /** 开启新 generation：问题键或错误签名发生实质变化。 */
    OPEN_NEW_GENERATION("OPEN_NEW_GENERATION"),

    /** 完全新建问题对象。 */
    CREATE_NEW_EPISODE("CREATE_NEW_EPISODE")
}

/**
 * 会话状态。与 V242 迁移的 `chk_episode_session_state` 约束一致。
 *
 * [CLOSED] 之后仍可能被迟到数据重新打开，因此它不是终态；真正的终态由 reopen 窗口超时
 * 加 generation 递增共同表达。
 */
enum class EpisodeSessionState(val storageCode: String) {
    OPEN("OPEN"),
    CLOSED("CLOSED"),
    REOPENED("REOPENED");

    companion object {
        private val BY_CODE = entries.associateBy(EpisodeSessionState::storageCode)

        fun fromStorage(code: String?): EpisodeSessionState? = code?.let(BY_CODE::get)
    }
}

enum class EpisodeJoinReason(val storageCode: String) {
    /** 与上一事件的时间差在不活跃阈值内。 */
    SESSION_GAP("SESSION_GAP"),

    /** 问题键命中同一分区。 */
    PROBLEM_KEY_HIT("PROBLEM_KEY_HIT"),

    /** 人工归并。 */
    MANUAL_MERGE("MANUAL_MERGE"),

    /** 迟到事件在重开窗口内追加。 */
    LATE_APPEND("LATE_APPEND"),

    /** 问题键或错误签名变化，开启新 generation。 */
    SIGNATURE_CHANGED("SIGNATURE_CHANGED")
}

/**
 * 影响面计数。三个数字必须分列，禁止相加或互相替代。
 *
 * 414 回放案例里，同一设备连提四次：`repeatCount` 为 4，`distinctDeviceCount` 为 1，
 * 这就是「设备影响数只计一」在数据结构上的落地方式。
 */
data class ImpactCounts(
    val repeatCount: Int,
    val distinctUserCount: Int,
    val distinctDeviceCount: Int
) {
    companion object {
        val ZERO: ImpactCounts = ImpactCounts(repeatCount = 0, distinctUserCount = 0, distinctDeviceCount = 0)
    }
}

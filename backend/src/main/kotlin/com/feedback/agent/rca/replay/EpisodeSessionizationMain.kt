package com.feedback.agent.rca.replay

import com.feedback.service.masking.IdentityFingerprintService
import com.feedback.agent.rca.domain.EpisodeJoinReason
import com.feedback.agent.rca.domain.EpisodeMembershipAction
import com.feedback.agent.rca.domain.EpisodeSessionPolicy
import com.feedback.agent.rca.domain.EpisodeSessionState
import com.feedback.agent.rca.domain.ImpactCounts
import com.feedback.agent.rca.domain.TrustedIdentityLevel
import com.feedback.agent.rca.episode.ChannelContact
import com.feedback.agent.rca.episode.ChannelContactType
import com.feedback.agent.rca.episode.ContactFingerprintPort
import com.feedback.agent.rca.episode.ContactHints
import com.feedback.agent.rca.episode.EpisodeEndpointKey
import com.feedback.agent.rca.episode.EpisodeIdentityResolver
import com.feedback.agent.rca.episode.EpisodeIntakeOutcome
import com.feedback.agent.rca.episode.EpisodeIntakeService
import com.feedback.agent.rca.episode.EpisodeSessionizer
import com.feedback.agent.rca.episode.FingerprintFact
import com.feedback.agent.rca.episode.InMemoryEpisodeSessionStore
import com.feedback.agent.rca.episode.InMemoryWorkItemDispatch
import com.feedback.agent.rca.episode.MemberSourceFacts
import com.feedback.agent.rca.episode.MemberSourceFactsPort
import com.feedback.agent.rca.episode.RawFeedbackSignal
import java.time.LocalDateTime
import kotlin.system.exitProcess

/**
 * 问题对象会话化的校验入口。
 *
 * 它把「原始信号 → 身份解析 → 会话化判定 → 落问题对象 → 建工作项 → 投递 RCA 任务」
 * 按生产的装配方式接起来跑，然后断言落到哪个问题对象、影响面记成多少、成员以什么原因并入。
 *
 * ## 被替换掉的只有两张表
 *
 * 身份指纹用**生产实现**（[IdentityFingerprintService]），会话化判定、身份定级、
 * 接入编排全部是真实代码。被替换的是 `t_feedback_episode*` / `t_feedback_work_item` /
 * `t_rca_agent_task` 的读写（[EpisodeIntakeFixture] 里的内存替身），
 * 因为把 MySQL 拉进这个套件会让它只能在装了数据库的机器上跑。
 *
 * 代价必须说清楚：**SQL 语句本身没有被这个套件覆盖**。
 * 替身对齐的是唯一键语义（重复成员、重复工作项、重复任务幂等），不是 SQL 语法与索引行为。
 * 真实语句的验证需要另接一个连库的切片，本套件不宣称覆盖它。
 *
 * ## 414 夹具：问题对象口径与联合调查候选口径是**两个数**
 *
 * 414 夹具里的影响面是 `4 条反馈 / 3 个账号 / 4 台设备`，但分区键含身份指纹
 * （`租户 + 应用 + 问题键 + 身份指纹`），所以 3 个账号**必然**产出 3 个问题对象，
 * 任何一个问题对象的用户数都不会到 3。这两个口径必须分开断言，混起来会得到
 * 一个永远无法复算的数字：
 *
 * - **问题对象口径**（本层）：账号 A 2 条 → 2/1/2，账号 B 1 条 → 1/1/1，账号 C 1 条 → 1/1/1。
 * - **联合调查候选口径**（3 个成员对象上卷）：重复数相加 = 4，用户指纹取并集 = 3，
 *   设备指纹取并集 = 4，与 `ReplayScenario.episodeFacts().impact` 逐字段相等。
 *
 * 逐字稿里「同一设备连续提交了四次 → 映射到同一个连续问题对象、设备影响数只计一」
 * 说的是**同一身份**的重复上报（见 [verifySameDeviceRepeats]），与跨账号聚合成候选不是同一层。
 *
 * 运行：`./gradlew :backend:rcaEpisodeSessionization`。
 */
object EpisodeSessionization {

    fun run(): EpisodeSessionizationOutcome {
        val fixture = EpisodeIntakeFixture()
        val checks = ReplayChecks()
        val journal = mutableListOf<String>()

        verifyResolverRules(fixture, checks)

        val firstAccount = verify414Cluster(fixture, checks, journal)
        verifySameDeviceRepeats(fixture, checks, journal)
        verifyDuplicateDelivery(fixture, checks, journal, firstAccount)
        verifyGenerationSplit(fixture, checks, journal, firstAccount)
        verifyLateArrivalAndReopen(fixture, checks, journal)
        verifyIdentityIsolation(fixture, checks, journal, firstAccount)

        val episodeIds = fixture.store.episodeIds()
        return EpisodeSessionizationOutcome(
            checks = checks,
            journal = journal,
            episodeCount = episodeIds.size,
            episodeIds = episodeIds,
            memberCount = episodeIds.sumOf { fixture.store.members(it).size },
            taskCount = fixture.dispatch.taskCount()
        )
    }

    // -----------------------------------------------------------------------
    // 断言零：身份定级规则
    // -----------------------------------------------------------------------

    /**
     * 等级与设备指纹是两个维度，这几条规则决定了影响面能不能算出来。
     *
     * 放在最前面断言，是因为后面每个场景的数字都建立在它们之上：
     * 规则错了的话，影响面会以一种「看起来只是数字偏小」的方式错，
     * 而那种错误很难从场景断言里看出根因。
     */
    private fun verifyResolverRules(fixture: EpisodeIntakeFixture, checks: ReplayChecks) {
        val resolver = fixture.resolver

        val accountOnAuthorizedDevice = resolver.resolve(
            sourceId = 9001,
            hints = ContactHints(
                verifiedAccountId = "user-1001",
                deviceSerial = "SN-A",
                deviceAuthorizationProof = "proof-a"
            )
        )
        checks.equal("校验过的账号 → 账号级", TrustedIdentityLevel.ACCOUNT, accountOnAuthorizedDevice.level)
        checks.equal("账号级的指纹类型", "ACCOUNT", accountOnAuthorizedDevice.fingerprintType)
        checks.equal(
            "账号级信号仍带授权设备指纹",
            true,
            accountOnAuthorizedDevice.deviceFingerprint != null
        )

        val accountWithUnprovenDevice = resolver.resolve(
            sourceId = 9002,
            hints = ContactHints(verifiedAccountId = "user-1001", deviceSerial = "SN-A")
        )
        checks.equal(
            "只有序列号、没有授权凭证 → 不认设备",
            null,
            accountWithUnprovenDevice.deviceFingerprint
        )
        checks.equal("缺少授权凭证不影响等级", TrustedIdentityLevel.ACCOUNT, accountWithUnprovenDevice.level)

        val deviceOnly = resolver.resolve(
            sourceId = 9003,
            hints = ContactHints(deviceSerial = "SN-B", deviceAuthorizationProof = "proof-b")
        )
        checks.equal(
            "无账号、序列号带凭证 → 授权设备级",
            TrustedIdentityLevel.AUTHORIZED_DEVICE,
            deviceOnly.level
        )
        // 设备指纹是 BINARY(32) 的 HMAC，十六进制呈现恒为 64 字符；格式若变，去重键也会跟着变。
        checks.equal(
            "设备指纹按十六进制呈现",
            true,
            deviceOnly.deviceFingerprint?.length == DEVICE_FINGERPRINT_HEX_LENGTH
        )

        val contactOnly = resolver.resolve(
            sourceId = 9004,
            hints = ContactHints(channelContact = ChannelContact("13800000002", ChannelContactType.PHONE))
        )
        checks.equal(
            "只有渠道联系方式 → 渠道联系人级",
            TrustedIdentityLevel.CHANNEL_CONTACT,
            contactOnly.level
        )

        val blankHints = resolver.resolve(sourceId = 9005, hints = ContactHints())
        checks.equal("无任何线索 → 匿名", TrustedIdentityLevel.ANONYMOUS, blankHints.level)
        checks.equal("无任何线索的指纹类型", "ANONYMOUS", blankHints.fingerprintType)
        // 两条都没有线索的信号必须得到**不同**指纹：合并它们会造出一个假的「影响 N 人」对象。
        val anotherBlank = resolver.resolve(sourceId = 9006, hints = ContactHints())
        checks.equal(
            "两条无线索信号不共享指纹（按来源各自成会话）",
            false,
            blankHints.fingerprintHex == anotherBlank.fingerprintHex
        )
    }

    // -----------------------------------------------------------------------
    // 断言一：414 四条反馈的三个问题对象，以及候选口径的上卷
    // -----------------------------------------------------------------------

    /**
     * 414 的四条反馈按账号分区后落到 3 个问题对象，本函数断言**两个口径**：
     *
     * 1. 每个问题对象自己的计数（同账号能并进来，换账号不能）；
     * 2. 3 个成员对象上卷成联合调查候选后的影响面，必须等于 RCA 回放夹具里那个被当作输入的影响面。
     *
     * 第 2 条把两个套件钉在一起：会话化侧算出的影响面与 RCA 侧假定的事实一旦漂移，
     * 两边同时失败，而不是各自「看起来都对」。
     *
     * @return 第一个账号对应的问题对象，供后续场景断言「这个已存在的对象没被改动」。
     */
    private fun verify414Cluster(
        fixture: EpisodeIntakeFixture,
        checks: ReplayChecks,
        journal: MutableList<String>
    ): AccountEpisode {
        val base = ReplayScenario.FAULT_AT
        // 账号 user-1001 两次上报（两台设备），另外两个账号各一次。
        val outcomeA1 = fixture.accept(
            fixture.signal(
                sourceId = 1, eventTime = base.minusMinutes(6),
                account = "user-1001", device = "SN-A", endpoint = "cfid-1"
            )
        )
        val outcomeA2 = fixture.accept(
            fixture.signal(
                sourceId = 2, eventTime = base.minusMinutes(4),
                account = "user-1001", device = "SN-B", endpoint = "cfid-2"
            )
        )
        val outcomeB = fixture.accept(
            fixture.signal(
                sourceId = 3, eventTime = base.minusMinutes(2),
                account = "user-2002", device = "SN-C", endpoint = "cfid-3"
            )
        )
        val outcomeC = fixture.accept(
            fixture.signal(
                sourceId = 4, eventTime = base.plusMinutes(1),
                account = "user-3003", device = "SN-D", endpoint = "cfid-4"
            )
        )
        val outcomes = listOf(outcomeA1, outcomeA2, outcomeB, outcomeC)

        checks.equal("同账号的两条并进同一个问题对象", outcomeA1.episodeId, outcomeA2.episodeId)
        checks.equal(
            "换账号另起问题对象（分区键含身份指纹）",
            true,
            outcomeB.episodeId != outcomeA1.episodeId
        )
        checks.equal("第三个账号另起问题对象", true, outcomeC.episodeId != outcomeB.episodeId)
        checks.equal(
            "414 各条的归并动作（新分区建对象，同账号并入）",
            "CREATE_NEW_EPISODE,JOIN,CREATE_NEW_EPISODE,CREATE_NEW_EPISODE",
            outcomes.joinToString(",") { it.action.storageCode }
        )
        checks.equal("414 全部落在第 1 代", "1,1,1,1", outcomes.joinToString(",") { it.generation.toString() })
        checks.equal(
            "同账号并入的原因（事件时间在不活跃阈值内）",
            EpisodeJoinReason.SESSION_GAP.storageCode,
            fixture.store.members(outcomeA2.episodeId).last().joinReason
        )

        // 问题对象口径：三个数分列，且没有任何一个对象跨账号。
        checks.equal(
            "A 账号问题对象的影响面（重复 2 / 用户 1 / 设备 2）",
            "2/1/2",
            countLabel(outcomeA2.impact)
        )
        checks.equal(
            "B 账号问题对象的影响面（重复 1 / 用户 1 / 设备 1）",
            "1/1/1",
            countLabel(outcomeB.impact)
        )
        checks.equal(
            "C 账号问题对象的影响面（重复 1 / 用户 1 / 设备 1）",
            "1/1/1",
            countLabel(outcomeC.impact)
        )
        checks.equal(
            "单个问题对象的用户数恒为 1（跨账号不合并）",
            1,
            listOf(outcomeA2, outcomeB, outcomeC).maxOf { it.impact.distinctUserCount }
        )

        // 候选口径：3 个成员对象上卷。
        val memberIds = listOf(outcomeA2.episodeId, outcomeB.episodeId, outcomeC.episodeId)
        val rollUp = rollUpCluster(fixture.store, memberIds)
        checks.equal("候选成员对象数（与夹具 clusterMemberCount 一致）", 3, memberIds.size)
        checks.equal("候选口径的重复数（成员相加）", 4, rollUp.repeatCount)
        checks.equal("候选口径的用户数（身份指纹取并集）", 3, rollUp.distinctUserCount)
        checks.equal("候选口径的设备数（设备指纹取并集）", 4, rollUp.distinctDeviceCount)
        checks.equal("候选口径影响面与 RCA 夹具逐字段相等", ReplayScenario.episodeFacts().impact, rollUp)

        checks.equal(
            "每个问题对象各建一次工作项",
            "true,true,true",
            listOf(outcomeA1, outcomeB, outcomeC).joinToString(",") { it.workItemCreated.toString() }
        )
        checks.equal("同账号并入时不新建工作项", false, outcomeA2.workItemCreated)
        checks.equal(
            "任务幂等键按问题对象分别生成",
            3,
            listOf(outcomeA1.taskKey, outcomeB.taskKey, outcomeC.taskKey).distinct().size
        )

        journal += "414：4 条反馈 → 3 个问题对象（账号 A 两条、B 一条、C 一条）；" +
            "对象口径 2/1/2、1/1/1、1/1/1；候选口径上卷 4/3/4（= RCA 夹具）"
        return AccountEpisode(
            episodeId = outcomeA1.episodeId,
            taskKey = outcomeA1.taskKey,
            impact = outcomeA2.impact,
            workItemLastSeenAt = requireNotNull(fixture.dispatch.workItem(outcomeA1.episodeId)).lastSeenAt
        )
    }

    // -----------------------------------------------------------------------
    // 断言二：同一设备连提四次
    // -----------------------------------------------------------------------

    /**
     * 同一账号、同一设备、同一端点连提四次——逐字稿里「同一设备连续提交了四次，
     * 设备影响数只计一」的那个场景。
     *
     * 三个数字各说一件事：`repeatCount` 4（每条都算一次上报）、
     * `distinctUserCount` 1、`distinctDeviceCount` 1。相加得到的「影响 6 个」没有任何含义，
     * 所以这里断言的是三个数**分开**的结果，而不是一个总数。
     *
     * 这条断言同时是「设备事实与信号指纹同口径」的检验：替身里登记的 DEVICE_SN 事实
     * 若与 `signal.deviceFingerprint` 不是同一个值，设备数会变成 4 而不是 1。
     */
    private fun verifySameDeviceRepeats(
        fixture: EpisodeIntakeFixture,
        checks: ReplayChecks,
        journal: MutableList<String>
    ) {
        val base = ReplayScenario.FAULT_AT
        val problemKey = "player-init-timeout"
        val outcomes = (0 until 4).map { index ->
            fixture.accept(
                fixture.signal(
                    sourceId = 20L + index,
                    eventTime = base.minusMinutes(5).plusMinutes(index.toLong()),
                    account = "user-4004",
                    device = "SN-E",
                    // 同一端点重复上报：判定键相同，但四次原始信号全部保留。
                    endpoint = "cfid-retry-9",
                    problemKey = problemKey
                )
            )
        }

        val episodeId = outcomes.first().episodeId
        checks.equal(
            "同设备连提四次落入同一问题对象",
            1,
            outcomes.map(EpisodeIntakeOutcome::episodeId).distinct().size
        )
        checks.equal(
            "同设备连提四次的影响面（重复 4 / 用户 1 / 设备 1，禁止相加）",
            "4/1/1",
            countLabel(outcomes.last().impact)
        )
        val members = fixture.store.members(episodeId)
        checks.equal("同设备连提四次的成员行数（原始信号不删）", 4, members.size)
        checks.equal(
            "重复端点仅被标记、未被丢弃（首条之外都是重复端点）",
            "false,true,true,true",
            members.joinToString(",") { it.duplicateEndpoint.toString() }
        )
        checks.equal(
            "同设备连提四次的并入原因（全在不活跃阈值内）",
            "SESSION_GAP,SESSION_GAP,SESSION_GAP",
            members.drop(1).joinToString(",") { it.joinReason }
        )

        journal +=
            "同设备连提四次：问题对象 $episodeId 影响面 4/1/1" +
                "（三条重复端点被标记但未丢弃）"
    }

    // -----------------------------------------------------------------------
    // 断言三：重复投递（同一条 source 再来一次）
    // -----------------------------------------------------------------------

    /**
     * 至少一次投递下，同一条 source 会被再次送达。
     *
     * 正确的处置是**什么都不改**：不多一条成员、不多一次计数、不动末次出现时间、不重开、
     * 不重复投递任务。这里逐项断言「没变」，因为「没变」是这个场景唯一正确的产出。
     */
    private fun verifyDuplicateDelivery(
        fixture: EpisodeIntakeFixture,
        checks: ReplayChecks,
        journal: MutableList<String>,
        account: AccountEpisode
    ) {
        val tasksBefore = fixture.dispatch.taskCount()

        // 同一条 source（sourceId=1）原样重投：事件时间、线索、端点键都不变。
        val replay = fixture.accept(
            fixture.signal(
                sourceId = 1,
                eventTime = ReplayScenario.FAULT_AT.minusMinutes(6),
                account = "user-1001",
                device = "SN-A",
                endpoint = "cfid-1"
            )
        )

        checks.equal("重投落入同一问题对象", account.episodeId, replay.episodeId)
        checks.equal("重投被识别为重复投递", true, replay.duplicateDelivery)
        checks.equal("重投未重复计数", account.impact, replay.impact)
        checks.equal("重投未新增成员", 2, fixture.store.members(account.episodeId).size)
        checks.equal("重投不新建工作项", false, replay.workItemCreated)
        checks.equal("重投不报告任务已创建", false, replay.taskCreated)
        checks.equal(
            "重投不移动末次出现时间",
            account.workItemLastSeenAt,
            requireNotNull(fixture.dispatch.workItem(account.episodeId)).lastSeenAt
        )
        checks.equal(
            "重投不增加重开次数",
            0,
            requireNotNull(fixture.dispatch.workItem(account.episodeId)).reopenCount
        )
        checks.equal("重投不重复投递任务", tasksBefore, fixture.dispatch.taskCount())

        journal +=
            "重复投递：source 1 重投 → 幂等命中，计数/成员/末次出现/重开/任务全部未动"
    }

    // -----------------------------------------------------------------------
    // 断言四：签名变化开启新 generation
    // -----------------------------------------------------------------------

    /**
     * 同一分区上错误签名发生实质变化（HTTP 400 → 播放器初始化超时）时，必须开新 generation。
     *
     * 静默并入旧会话会让两个不同故障共用一份影响面，而这个错误在事后完全看不出来：
     * 旧会话的结论会被后来者继承，且新故障的成员数被算进旧故障的规模里。
     */
    private fun verifyGenerationSplit(
        fixture: EpisodeIntakeFixture,
        checks: ReplayChecks,
        journal: MutableList<String>,
        account: AccountEpisode
    ) {
        val outcome = fixture.accept(
            fixture.signal(
                sourceId = 5,
                eventTime = ReplayScenario.FAULT_AT.plusMinutes(2),
                account = "user-1001",
                device = "SN-A",
                endpoint = "cfid-5",
                errorSignature = "PLAYER_INIT_TIMEOUT"
            )
        )

        checks.equal("签名变化开启新代", EpisodeMembershipAction.OPEN_NEW_GENERATION, outcome.action)
        checks.equal("新代的问题对象是新行", true, outcome.episodeId != account.episodeId)
        checks.equal("新代的代数", 2, outcome.generation)
        checks.equal(
            "新代影响面只含自己那条（重复 1 / 用户 1 / 设备 1）",
            "1/1/1",
            countLabel(outcome.impact)
        )
        // 旧代的影响面必须原封不动：新代不是「旧代 + 新数据」，是另一条异常流。
        checks.equal(
            "旧代影响面不受新代影响",
            account.impact,
            requireNotNull(fixture.store.snapshot(account.episodeId)).impact
        )
        checks.equal("新代建自己的工作项", true, outcome.workItemCreated)
        checks.equal("新代投递自己的任务（幂等键不同）", true, outcome.taskCreated)
        checks.equal("新代任务幂等键与旧代不同", true, outcome.taskKey != account.taskKey)
        checks.equal(
            "新代成员的并入原因（新会话首条按问题键命中）",
            EpisodeJoinReason.PROBLEM_KEY_HIT.storageCode,
            fixture.store.members(outcome.episodeId).single().joinReason
        )

        journal +=
            "签名变化：问题对象 ${account.episodeId} 开新代 → ${outcome.episodeId}（第 2 代），" +
                "旧代影响面未动"
    }

    // -----------------------------------------------------------------------
    // 断言五：迟到数据与重开
    // -----------------------------------------------------------------------

    /**
     * 两类迟到，处置不同：
     *
     * - **回填到会话起点之前**：追加 revision，会话边界**不重定义**（否则影响面窗口会被倒着扩大）。
     * - **超出不活跃阈值但在重开窗口内**：追加 revision，会话状态置 REOPENED，
     *   工作项累计重开次数——窗口重算随后就知道本窗口里存在断点，不会把断点前后的量直接加总。
     *
     * 两类都走 APPEND_REVISION，所以必须分开断言「边界有没有动、状态有没有变」，
     * 只看 action 会把两者当成同一件事。
     */
    private fun verifyLateArrivalAndReopen(
        fixture: EpisodeIntakeFixture,
        checks: ReplayChecks,
        journal: MutableList<String>
    ) {
        val base = ReplayScenario.FAULT_AT
        val problemKey = "resource-load-failure"
        val opened = fixture.accept(
            fixture.signal(
                sourceId = 30, eventTime = base.minusMinutes(6),
                account = "user-5005", device = "SN-F", endpoint = "cfid-30", problemKey = problemKey
            )
        )
        val episodeId = opened.episodeId

        val backfilled = fixture.accept(
            fixture.signal(
                sourceId = 31, eventTime = base.minusMinutes(20),
                account = "user-5005", device = "SN-G", endpoint = "cfid-31", problemKey = problemKey
            )
        )
        val afterBackfill = requireNotNull(fixture.store.snapshot(episodeId))
        checks.equal(
            "迟到（早于会话起点）走追加修订",
            EpisodeMembershipAction.APPEND_REVISION,
            backfilled.action
        )
        checks.equal("迟到（早于会话起点）额外开一条成员", 2, fixture.store.members(episodeId).size)
        checks.equal(
            "迟到（早于会话起点）的成员修订号递增",
            1L,
            fixture.store.members(episodeId).last().membershipRevision
        )
        checks.equal(
            "迟到（早于会话起点）的并入原因",
            EpisodeJoinReason.LATE_APPEND.storageCode,
            fixture.store.members(episodeId).last().joinReason
        )
        checks.equal(
            "迟到（早于会话起点）的会话起点不被重定义",
            base.minusMinutes(6),
            afterBackfill.sessionOpenedAt
        )
        checks.equal(
            "迟到（早于会话起点）的末次事件时间不被回拨",
            base.minusMinutes(6),
            afterBackfill.sessionLastEventAt
        )
        checks.equal(
            "迟到（早于会话起点）的影响面（新设备计入设备数）",
            "2/1/2",
            countLabel(afterBackfill.impact)
        )
        checks.equal(
            "迟到（早于会话起点）不改变会话状态",
            EpisodeSessionState.OPEN,
            afterBackfill.sessionState
        )

        // 事件时间越过不活跃阈值（30 分钟），到达时间仍在重开窗口（24 小时）内。
        val reopened = fixture.accept(
            fixture.signal(
                sourceId = 32, eventTime = base.plusMinutes(25),
                account = "user-5005", device = "SN-F", endpoint = "cfid-32", problemKey = problemKey
            )
        )
        val afterReopen = requireNotNull(fixture.store.snapshot(episodeId))
        val workItem = requireNotNull(fixture.dispatch.workItem(episodeId))
        checks.equal("重开走追加修订", EpisodeMembershipAction.APPEND_REVISION, reopened.action)
        checks.equal("重开后会话状态", EpisodeSessionState.REOPENED, afterReopen.sessionState)
        checks.equal("重开累计重开次数", 1, workItem.reopenCount)
        checks.equal("重开后工作项状态", "REOPENED", workItem.status)
        checks.equal("重开后末次出现时间前进", base.plusMinutes(25), workItem.lastSeenAt)
        checks.equal("重开不再重复计设备（同一台设备回来）", "3/1/2", countLabel(afterReopen.impact))

        journal += "迟到：问题对象 $episodeId 追加修订 1 次（边界未动，2/1/2）、" +
            "重开 1 次（状态 REOPENED，重开次数 1，3/1/2）"
    }

    // -----------------------------------------------------------------------
    // 断言六：身份等级之间不互相冒充
    // -----------------------------------------------------------------------

    /**
     * 同一问题键、不同身份的反馈**不得**并进同一个问题对象。
     *
     * 尤其是「没有任何身份线索」的两条信号：它们各自的指纹是 `source:<id>`，
     * 因此各自成一个问题对象。合并它们等价于凭空造出一个「影响 N 人」的故障，
     * 而 N 越大越像真问题——这种错误会主动伪装成有价值的信号。
     */
    private fun verifyIdentityIsolation(
        fixture: EpisodeIntakeFixture,
        checks: ReplayChecks,
        journal: MutableList<String>,
        account: AccountEpisode
    ) {
        val base = ReplayScenario.FAULT_AT
        // 与 414 完全相同的问题键、应用与租户：只有身份不同。
        val anonymous = fixture.accept(fixture.signal(sourceId = 40, eventTime = base, anonymousId = "install-40"))
        val noHintsA = fixture.accept(fixture.signal(sourceId = 41, eventTime = base))
        val noHintsB = fixture.accept(fixture.signal(sourceId = 42, eventTime = base))
        val contact = fixture.accept(
            fixture.signal(
                sourceId = 43, eventTime = base,
                contact = ChannelContact("13800000001", ChannelContactType.PHONE)
            )
        )

        checks.equal("匿名信号自成问题对象", EpisodeMembershipAction.CREATE_NEW_EPISODE, anonymous.action)
        checks.equal(
            "匿名信号不计入用户数（设备指纹不能当账号用）",
            "1/0/0",
            countLabel(anonymous.impact)
        )
        checks.equal("匿名信号不并进账号级问题对象", true, anonymous.episodeId != account.episodeId)
        checks.equal(
            "两条无线索信号各自成对象，不互相合并",
            true,
            noHintsA.episodeId != noHintsB.episodeId
        )
        checks.equal("渠道联系方式计入用户数", "1/1/0", countLabel(contact.impact))
        checks.equal("渠道联系人级不并进账号级问题对象", true, contact.episodeId != account.episodeId)
        checks.equal(
            "四个不同身份 → 四个问题对象",
            4,
            listOf(anonymous, noHintsA, noHintsB, contact).map(EpisodeIntakeOutcome::episodeId).distinct().size
        )
        // 隔离的最终检验：414 那个问题对象的计数一条都没被这些信号动过。
        checks.equal(
            "414 问题对象的影响面未被他身份信号污染",
            account.impact,
            requireNotNull(fixture.store.snapshot(account.episodeId)).impact
        )

        journal +=
            "身份隔离：匿名 + 无线索×2 + 渠道联系人共 4 条 → 4 个独立问题对象，" +
                "414 对象计数未动"
    }

    /** 计数三个数分列的呈现形式；相加得到的数没有任何含义，所以处处按这个格式断言。 */
    private fun countLabel(impact: ImpactCounts): String =
        "${impact.repeatCount}/${impact.distinctUserCount}/${impact.distinctDeviceCount}"

    /** HMAC-SHA256 的十六进制长度（32 字节 × 2）；定义一次，避免断言里散落魔法数。 */
    private const val DEVICE_FINGERPRINT_HEX_LENGTH = 64
}

/**
 * 联合调查候选口径的影响面上卷：重复数相加，用户/设备指纹取并集。
 *
 * **这不属于会话化层。** 问题对象按身份分区，`distinctUserCount` 恒等于该分区内的身份数；
 * 「3 个账号、4 台设备」这种跨身份的影响面只存在于候选层（成员问题对象的上卷）。
 * 本 MVP 没有实现候选层——RCA 图的输入 `EpisodeFacts` 目前由调用方直接给出——
 * 所以套件在这里复算一遍它的定义，用途只有一个：让会话化侧算出的成员对象
 * 与 RCA 侧假定的事实之间的口径可以逐字段核对。
 *
 * 上卷时**不能**把三个数相加：重复数相加是成员内的信号计数，
 * 而用户数与设备数必须按指纹去重，否则同一个账号出现在两个成员里会被算成两个人。
 */
private fun rollUpCluster(store: InMemoryEpisodeSessionStore, episodeIds: List<Long>): ImpactCounts {
    val snapshots = episodeIds.map { id ->
        requireNotNull(store.snapshot(id)) { "问题对象 $id 的快照读不到，套件的场景顺序已被破坏" }
    }
    return ImpactCounts(
        repeatCount = snapshots.sumOf { it.impact.repeatCount },
        distinctUserCount = snapshots.flatMap { it.identityFingerprints }.toSet().size,
        distinctDeviceCount = snapshots.flatMap { it.deviceFingerprints }.toSet().size
    )
}

/**
 * 414 第一个账号对应的问题对象。
 *
 * 后续场景**不**重新推导它，而是接着用它：重复投递、签名变化、身份隔离都要断言
 * 「这个已存在的对象有没有被改」，重新推导出来的 id 无法证明这一点。
 */
private data class AccountEpisode(
    val episodeId: Long,
    val taskKey: String,
    val impact: ImpactCounts,
    val workItemLastSeenAt: LocalDateTime
)

/**
 * 接入套件的固定装配。
 *
 * 端口与判定的组装方式与 `RcaEpisodeConfiguration` 一致，且指纹用**生产实现**：
 * 套件要验的正是「分区键与去重集合按同一口径算」，若这里换一个自己写的 HMAC，
 * 验的就只是套件自己的算术。
 */
private class EpisodeIntakeFixture {

    private val fingerprints = IdentityFingerprintService(FINGERPRINT_SECRET, FINGERPRINT_KEY_ID)

    /** 与 `RcaEpisodeConfiguration.contactFingerprintPort` 相同的委托：口径由既有服务保证。 */
    val port = ContactFingerprintPort { value, type -> fingerprints.fingerprint(value, type) }
    val resolver = EpisodeIdentityResolver(port)
    val policy = EpisodeSessionPolicy.DEFAULT

    private val facts = RecordingFacts(port)
    val store = InMemoryEpisodeSessionStore(facts)
    val dispatch = InMemoryWorkItemDispatch()

    private val intake = EpisodeIntakeService(
        identities = resolver,
        sessionizer = EpisodeSessionizer(policy),
        store = store,
        dispatch = dispatch,
        policy = policy
    )

    /**
     * 构造一条原始信号。线索字段全部可空——「缺哪个」本身就是信息。
     *
     * [deviceProof] 默认给一个非空值，表示「设备已授权」；要验「只有序列号不认设备」时必须
     * 显式传 null，否则那个场景根本到不了那条分支。
     */
    fun signal(
        sourceId: Long,
        eventTime: LocalDateTime,
        account: String? = null,
        device: String? = null,
        deviceProof: String? = "authorized",
        contact: ChannelContact? = null,
        anonymousId: String? = null,
        errorSignature: String? = ReplayScenario.ERROR_SIGNATURE,
        endpoint: String? = null,
        problemKey: String = ReplayScenario.PROBLEM_KEY
    ): RawFeedbackSignal = RawFeedbackSignal(
        sourceId = sourceId,
        tenantKey = TENANT_KEY,
        appId = ReplayScenario.APP_ID,
        problemKey = problemKey,
        errorSignature = errorSignature,
        eventTime = eventTime,
        hints = ContactHints(
            verifiedAccountId = account,
            deviceSerial = device,
            deviceAuthorizationProof = device?.let { deviceProof },
            channelContact = contact,
            anonymousId = anonymousId
        ),
        // 端点键由同一个构造器拼出，与 SQL 侧 `CONCAT(source_channel, CHAR(31), source_record_id)` 同口径。
        endpointKey = endpoint?.let { EpisodeEndpointKey.of(SDK_CHANNEL, it) }
    )

    /**
     * 投递一条信号。
     *
     * 先登记事实再接入，顺序与生产一致：库里 `t_feedback_identity` / `t_feedback_source`
     * 的行在接入之前就由脱敏与归一化链路写好了，接入期只读它。
     */
    fun accept(signal: RawFeedbackSignal, arrivalTime: LocalDateTime = signal.eventTime): EpisodeIntakeOutcome {
        facts.register(signal)
        return intake.accept(signal, arrivalTime)
    }

    private companion object {
        /** 与生产的 `app.masking.fingerprint-secret` 同一约束：长度至少 32。 */
        const val FINGERPRINT_SECRET = "episode-sessionization-suite-secret-0123456789"

        /** 与生产的 `app.masking.fingerprint-key-id` 默认值一致。 */
        const val FINGERPRINT_KEY_ID = "identity-hmac-v1"

        const val TENANT_KEY = "DEFAULT"

        /** 与 `t_feedback_source.source_channel` 的取值域一致。 */
        const val SDK_CHANNEL = "SDK"
    }
}

/**
 * `t_feedback_identity` / `t_feedback_source` 的内存替身。
 *
 * 它按 source 登记**事实**（身份指纹与端点键），因为库里就是这样按 source 存的：
 * 去重集合是从成员 join 回事实表推出来的，不在问题对象行上留副本。
 *
 * 这里是「哪些类型算人、哪些算设备」的第三处实现（另两处见 `MemberFactTypes` 的说明），
 * 所以类型常量与 `MemberFactTypes` 保持一致，并由 [InMemoryEpisodeSessionStore]
 * 用同一组常量解释。替身与生产实现分叉的表现是影响面计数不同，不会报错。
 */
private class RecordingFacts(private val port: ContactFingerprintPort) : MemberSourceFactsPort {

    private val bySource = mutableMapOf<Long, MemberSourceFacts>()

    override fun factsOf(sourceId: Long): MemberSourceFacts = bySource[sourceId] ?: MemberSourceFacts()

    /**
     * 登记一条信号的**全部**事实：身份指纹与端点键。
     *
     * 端点是来源表上的列（`t_feedback_source.source_channel` + `source_record_id`），
     * 与身份指纹不在同一张表，但两者都按 source 取，所以一起登记。
     * 少登记端点键的表现不是报错，而是 `is_duplicate_endpoint` 永远为 false——
     * 一个「看起来只是没标重复」的错误，重复端点判定就此静默失效。
     */
    fun register(signal: RawFeedbackSignal) {
        val hints = signal.hints
        val facts = mutableListOf<FingerprintFact>()
        hints.verifiedAccountId?.takeIf(String::isNotBlank)?.let {
            facts += FingerprintFact(ACCOUNT_TYPE, port.fingerprint(it, ACCOUNT_TYPE))
        }
        val serial = hints.deviceSerial?.takeIf(String::isNotBlank)
        val proof = hints.deviceAuthorizationProof?.takeIf(String::isNotBlank)
        // 与 EpisodeIdentityResolver 同一条规则：序列号与凭证成对才算授权设备。
        // 两者若只有一处放宽，设备数会在这条链路上凭空多出来。
        if (serial != null && proof != null) {
            facts += FingerprintFact(DEVICE_TYPE, port.fingerprint(serial, DEVICE_TYPE))
        }
        hints.channelContact?.let {
            facts += FingerprintFact(it.type.storageType, port.fingerprint(it.value, it.type.storageType))
        }
        hints.anonymousId?.takeIf(String::isNotBlank)?.let {
            facts += FingerprintFact(ANONYMOUS_TYPE, port.fingerprint(it, ANONYMOUS_TYPE))
        }
        bySource[signal.sourceId] = MemberSourceFacts(fingerprints = facts, endpointKey = signal.endpointKey)
    }

    private companion object {
        const val ACCOUNT_TYPE = "ACCOUNT"
        const val DEVICE_TYPE = "DEVICE_SN"
        const val ANONYMOUS_TYPE = "ANONYMOUS"
    }
}

/** 一次校验的产物：断言结论、场景留痕与行数汇总。 */
data class EpisodeSessionizationOutcome(
    val checks: ReplayChecks,
    private val journal: List<String>,
    private val episodeCount: Int,
    private val episodeIds: List<Long>,
    private val memberCount: Int,
    private val taskCount: Int
) {
    val passed: Boolean get() = checks.passed

    /** 人读报告。场景留痕在前，便于对照逐字稿讲口径。 */
    fun report(): String = buildString {
        appendLine("=== 问题对象会话化：固定夹具校验 ===")
        appendLine()
        appendLine("[场景]")
        journal.forEach { appendLine("  $it") }
        appendLine()
        appendLine("[汇总]")
        appendLine(
            "  问题对象 $episodeCount 个（${episodeIds.joinToString(",")}），" +
                "成员 $memberCount 行，RCA 任务 $taskCount 条"
        )
        appendLine("  每个问题对象恰好一个任务：$episodeCount = $taskCount 即重投未产生新任务")
        appendLine(
            "  身份指纹来自生产实现 IdentityFingerprintService；" +
                "四张表为内存替身，SQL 语句未被本套件执行覆盖"
        )
        appendLine()
        appendLine("[断言]")
        checks.failureLines().forEach { appendLine("  失败：$it") }
        appendLine("  ${if (passed) "全部通过（${checks.total} 项）" else failureLabel()}")
    }

    /** 失败摘要单独成方法：内联进上面那行会把它撑到难以对照既有回放入口的宽度。 */
    private fun failureLabel(): String = "未通过（${checks.failureLines().size} 项失败）"
}

fun main() {
    val outcome = EpisodeSessionization.run()
    println(outcome.report())
    if (!outcome.passed) {
        // 非零退出码让它在 CI 或脚本里能当回归基线用，而不只是给人读的一段输出。
        exitProcess(1)
    }
}

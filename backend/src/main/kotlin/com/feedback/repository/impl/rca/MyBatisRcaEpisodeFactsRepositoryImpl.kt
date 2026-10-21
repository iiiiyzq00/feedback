package com.feedback.repository.impl.rca

import com.feedback.agent.rca.domain.ImpactCounts
import com.feedback.agent.rca.graph.EpisodeFacts
import com.feedback.agent.rca.graph.EpisodeFactsPort
import com.feedback.mapper.rca.RcaEpisodeFactsMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Repository
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Episode 事实端口的 MyBatis 实现。
 *
 * ## 三类字段：读列、派生、降级
 *
 * 1. **直接读列**：租户、应用、问题键、签名、代数、起止时间、三个影响面计数。
 * 2. **派生**：`crashOrAnr`（Crash/ANR 成员数 > 0）、`clusterMemberCount`（ACTIVE 成员数）、
 *    `affectedVersions`（成员覆盖的版本去重）、`symptomSummary`（簇规范名，无簇回退问题键）。
 * 3. **降级**：`controlVersion` 与 `logId`。
 *
 * ## 为什么 `logId` 恒为 NULL
 *
 * 本平台**没有任何 log_id / trace_id 列**（`t_feedback_source`、`t_feedback_episode`、
 * `t_feedback_content` 都没有）。[EpisodeFacts] 的 `init` 要求
 * 「`hasValidLogId = true` 时必须给出 `logId`」，所以这里只能诚实地取
 * `hasValidLogId = false, logId = null`。
 * 编一个日志 ID 出来会让 `LOG_TRACE_BY_ID` 这条强制项被触发，随后取不到日志、
 * 记成证据缺口——一条永远无法完成的门禁，比不触发它更糟：它会把每个 Episode
 * 都拖进「有未完成的强制项」从而禁止正常结案。
 *
 * ## 为什么 `controlVersion` 允许降级，而别的字段不允许
 *
 * `controlVersion` 的唯一合法来源是版本发布治理表 `t_app_version_release_fact`（V98 创建），
 * 它是**可选增强**：缺它只影响「版本对照基准」这一个字段，且
 * `ProblemProfile.controlVersion` 的契约为空时明确表示「事实不足、不做版本集中度结论」，
 * 不触发任何新的强制项（`MandatoryEvidencePlan.from` 只看 `affectedVersions.size`）。
 *
 * **该表在 V156 之后按设计已不存于库中**（`V156__retire_legacy_shadow_tables.sql` 在生产读写
 * 切换、对账与全量回归完成后把它连同另外 46 张表一起 `DROP`）。所以 `controlVersion` 为空
 * 在 V156 及之后的部署上是**结构性事实**，不是部署缺陷、更不是「少跑了一条迁移」。
 * 改成别的来源（例如拿 `t_feedback_source.app_version` 反推一个「对照版本」）是**错的**：
 * 版本对照必须有独立的发布治理事实，自己反推出来的是同一份数据的另一个视图，
 * 用它做对照组等于用被验证对象证明被验证对象。
 *
 * 但降级**只认源表不存在这一种失败**（SQLState `42S02`），列名拼错、语法错误、
 * 类型错误等一律原样抛出。理由是这两种失败必须分开：源表不存在是部署形态问题，
 * 重试和修复代码都没用；而列名写错是本次改动引入的缺陷，若也当成「不可用」，
 * 它会长期表现为 `controlVersion` 恒为 NULL，且没有任何一处会报错——
 * 那正是本仓库此前已经踩过的「缺陷因为被静默吞掉而活到生产」的形态。
 */
@Repository
class MyBatisRcaEpisodeFactsRepositoryImpl(
    private val mapper: RcaEpisodeFactsMapper
) : EpisodeFactsPort {

    private val log = LoggerFactory.getLogger(MyBatisRcaEpisodeFactsRepositoryImpl::class.java)

    /**
     * 版本对照源不可用只提示一次。
     *
     * 一次 worker 循环会处理成千上万个 Episode，逐个打一行 WARN 会把日志淹掉，
     * 而这条信息的内容与 Episode 无关（是部署形态的事实），说一次就够。
     */
    private val controlVersionSourceMissing = AtomicBoolean(false)

    override fun load(episodeId: Long): EpisodeFacts? {
        val row = mapper.loadEpisodeFacts(episodeId) ?: return null

        // 两列在 V242 里是 NOT NULL，取到 NULL 说明读到的不是这张表的正常行。
        // 这时**不**退回「当前时刻」之类的默认值：时间一旦被默默扶正，
        // 这个 Episode 在会话时间轴上的位置就错了，而下游没有任何地方能看出这一点。
        val occurredFrom = row.sessionOpenedAt
        val occurredTo = row.sessionLastEventAt
        if (occurredFrom == null || occurredTo == null) {
            log.error(
                "RCA_EPISODE_TIME_MISSING episode={} openedAt={} lastEventAt={}：事实行缺少会话时间，按事实不可读处理",
                episodeId,
                row.sessionOpenedAt,
                row.sessionLastEventAt
            )
            return null
        }

        return EpisodeFacts(
            episodeId = row.episodeId,
            tenantKey = row.tenantKey,
            appId = row.appId,
            problemKey = row.problemKey,
            generation = row.generation,
            errorSignature = row.errorSignature,
            occurredFrom = occurredFrom,
            occurredTo = occurredTo,
            impact = ImpactCounts(
                repeatCount = row.repeatCount,
                distinctUserCount = row.distinctUserCount,
                distinctDeviceCount = row.distinctDeviceCount
            ),
            crashOrAnr = row.crashAnrMemberCount > 0,
            // 平台无日志列，见类注释：只能取 false，不能编一个 ID 出来。
            hasValidLogId = false,
            logId = null,
            clusterMemberCount = row.clusterMemberCount,
            clusterId = row.clusterId,
            affectedVersions = mapper.findAffectedVersions(episodeId),
            controlVersion = loadControlVersion(row.packageName),
            // 簇名缺失时回退问题键：两者都是对「这是哪个问题」的稳定表述，
            // 而 symptomSummary 是 NOT NULL，必须给一个有意义的值而不是空串。
            symptomSummary = row.canonicalSymptom?.takeIf { it.isNotBlank() } ?: row.problemKey
        )
    }

    /**
     * 取版本对照基准；源表不存在或取不到时返回 `null`（契约允许，见类注释）。
     */
    private fun loadControlVersion(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return try {
            mapper.findActiveControlVersion(packageName)
        } catch (error: DataAccessException) {
            val sqlState = (error.rootCause as? SQLException)?.sqlState
            if (sqlState != TABLE_NOT_FOUND_SQL_STATE) throw error
            if (controlVersionSourceMissing.compareAndSet(false, true)) {
                log.warn(
                    "RCA_CONTROL_VERSION_SOURCE_MISSING package={} sqlState={}：版本发布治理表不可用，" +
                        "本次运行的 controlVersion 一律为空（版本集中度结论不可用）；" +
                        "该表由 Flyway V98 创建、V156 退役：迁移版本已过 V156 时这是预期状态，" +
                        "**不要**去补迁移（迁移是完整的，表是被有意删掉的）；" +
                        "只有部署版本早于 V156 才属于迁移未跑完",
                    packageName,
                    sqlState
                )
            }
            null
        }
    }

    private companion object {
        /**
         * MySQL 的「表不存在」。只降级这一种：它表示部署形态，而不是代码缺陷。
         */
        const val TABLE_NOT_FOUND_SQL_STATE = "42S02"
    }
}

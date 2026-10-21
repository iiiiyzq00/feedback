package com.feedback.mapper.rca

import com.feedback.model.po.rca.EpisodeMemberFactPo
import com.feedback.model.po.rca.EpisodeMemberInsertPo
import com.feedback.model.po.rca.EpisodeSessionInsertPo
import com.feedback.model.po.rca.EpisodeSessionPo
import com.feedback.model.po.rca.WorkItemInsertPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/**
 * 反馈信号接入（Episode → 成员 → 工作项 → RCA 任务）的语句。
 *
 * 接口只依赖 `model.po` 与标量（与其余 Mapper 一致），领域类型到行的翻译全在
 * `EpisodeSessionAdapter` 里做：Mapper 认得领域对象之后，
 * 「哪些 SQL 属于哪条业务规则」就再也无法从接口上看出来了。
 *
 * ## 幂等靠唯一键，不靠「先查后写」
 *
 * 接入链路是至少一次投递。三个唯一键各自挡一种重复：
 * `uk_episode_partition` 挡同一分区同一代的重复创建，
 * `uk_episode_member_revision` 挡同一条 source 的重复追加，
 * `uk_rca_agent_task_key` 挡同一幂等键的重复投递。
 * 插入语句因此**不做**「查一下在不在」，冲突一律由数据库抛出、由适配器翻译成领域结果——
 * 「先查后插」之间没有东西保证候选仍然是候选。
 */
@Mapper
interface EpisodeSessionMapper {

    /**
     * 取分区内当前活跃的一代（`generation` 最大者）。没有则返回 null。
     *
     * 按 `generation` 倒序而不是按 `created_at`：generation 是业务上的代数，
     * 创建时间只是它的一个观测值，两者在并发或时钟回拨下可能不一致。
     */
    fun findActiveEpisode(
        @Param("tenantKey") tenantKey: String,
        @Param("appId") appId: String,
        @Param("problemKey") problemKey: String,
        @Param("identityFingerprint") identityFingerprint: ByteArray
    ): EpisodeSessionPo?

    /**
     * 该会话「已经见过」的身份事实。
     *
     * 去重集合是从成员 join 回事实表推出来的，**不在 Episode 行上留副本**：
     * 副本一旦与事实表不一致，「影响面按去重后计」就再也无法从任何一张表上验证。
     * 代价是每次判定多两次 join，换来的是这个数字永远可复算。
     */
    fun findMemberFacts(@Param("episodeId") episodeId: Long): List<EpisodeMemberFactPo>

    /**
     * 该会话「已经见过」的端点键，用于重复端点判定。
     *
     * 键的拼法与 `EpisodeEndpointKey.of` 一致（分隔符是 `CHAR(31)`），
     * 两边任一处改了分隔符，「重复端点」的判定就会在库与领域层之间分叉。
     */
    fun findMemberEndpointKeys(@Param("episodeId") episodeId: Long): List<String>

    /**
     * 新建 Episode。主键回填到 [EpisodeSessionInsertPo.id]。
     *
     * 冲突（同分区同代已存在）会让语句抛错而不是返回 0：这是「并发首次接入同一分区」
     * 的信号，需要让整条信号回滚后由投递方重投，不能被悄悄忽略。
     */
    fun insertEpisode(row: EpisodeSessionInsertPo): Int

    /** 追加成员。命中 `uk_episode_member_revision` 时抛唯一键冲突，由适配器翻译成幂等命中。 */
    fun insertMember(row: EpisodeMemberInsertPo): Int

    /**
     * 更新 Episode 行上的计数、会话状态、末次事件时间与修订号。
     *
     * `error_signature` 只在原值为空时补写（`IFNULL`）：归一化是异步的，
     * 后到的签名补上即可，**不能**覆盖已有签名——覆盖会让「换了签名」这件事被掩盖，
     * 而它是开启新 generation 的依据。
     */
    fun updateEpisode(
        @Param("episodeId") episodeId: Long,
        @Param("sessionState") sessionState: String,
        @Param("sessionLastEventAt") sessionLastEventAt: LocalDateTime,
        @Param("repeatCount") repeatCount: Int,
        @Param("distinctUserCount") distinctUserCount: Int,
        @Param("distinctDeviceCount") distinctDeviceCount: Int,
        @Param("memberRevision") memberRevision: Long,
        @Param("fillErrorSignature") fillErrorSignature: String?
    ): Int

    /**
     * 建工作项或取回已有的那个。**一条语句**返回影响行数与标识（`LAST_INSERT_ID(id)`）。
     *
     * 影响行数的语义是 MySQL 的 ODKU 约定：1 = 本次插入，0 = 行已存在且所有列值未变，
     * 2 = 行已存在且被更新。本语句只把主键赋成它自己，所以只可能是 1 或 0——
     * 调用方据此判断 `created`，**不要**改成「先查再插」。
     */
    fun upsertWorkItem(row: WorkItemInsertPo): Int

    /**
     * 触达工作项：末次出现时间只前进，会话重开时累计重开次数。
     *
     * `last_seen_at` 用 `GREATEST` 而不是直接赋值：迟到事件的 event_time 更早，
     * 直接覆盖会把「最后出现时间」往回拨，既违反 `chk_work_item_time_order`，
     * 也会让「多久没再出现」这个判断失真。
     */
    fun touchWorkItem(
        @Param("episodeId") episodeId: Long,
        @Param("lastSeenAt") lastSeenAt: LocalDateTime,
        @Param("reopened") reopened: Boolean
    ): Int

    /** 投递 RCA 任务。命中 `uk_rca_agent_task_key` 时抛唯一键冲突，由适配器翻译成幂等命中。 */
    fun insertRcaTask(
        @Param("publicId") publicId: String,
        @Param("taskKey") taskKey: String,
        @Param("episodeId") episodeId: Long,
        @Param("clusterId") clusterId: Long?
    ): Int
}

package com.feedback.mapper.rca

import com.feedback.model.po.rca.RcaDomainEventInsertPo
import com.feedback.model.po.rca.RcaDomainEventPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * `t_rca_domain_event` 的写入与读出。
 *
 * ## 幂等靠唯一键，与工具调用那张表不同
 *
 * `t_rca_tool_call` 没有唯一键，所以那边用「先查后写」；这张表有
 * `uk_rca_domain_event_id`，而且键值（`event_id`）是由事件内容**确定性推导**出来的，
 * 于是 [insertIgnore] 直接用 `INSERT IGNORE`：撞键即「这件事件已经记过了」。
 *
 * 这里的区别不是风格，而是两条路径上「重复」的含义不同：
 * 工具调用的重复是**同一次取证的两次尝试**，必须沿用同一行才能让恢复判定对上账；
 * 领域事件的重复是**同一件事实被记了两遍**，第二遍除了让「迁移过几次」这个计数
 * 变成错的之外没有任何用途，所以让它无声地消失才是正确的处置。
 *
 * 注意 `INSERT IGNORE` 会把**所有**可忽略错误都吞掉（含类型不匹配被截断），
 * 不只是重复键。本表的列宽与取值都由 [RcaDomainEventInsertPo] 的构造侧控制，
 * 因此不为此加额外的防线；但若将来有人往 SQL 里加新列，必须重新评估这一点。
 *
 * ## 为什么没有 update / delete
 *
 * 事件日志一旦写入就不该被改写。[markPublished] 是唯一的例外，
 * 它改的是**传输状态**而不是事实内容，而且只由 MQ 中继调用。
 */
@Mapper
interface RcaDomainEventMapper {

    /**
     * 落一条事件。撞 `uk_rca_domain_event_id` 时静默跳过。
     *
     * **`published_at` / `publish_attempts` 不在本语句的列里**：新事件一律是未投递状态，
     * 由列默认值（NULL / 0）决定。让调用方有机会在这一刻就写投递标记，
     * 会开出一个「还没发出去就先标成已发」的口子。
     *
     * @return 1 表示新写入，0 表示撞唯一键（已记过）。调用方**不应当**把 0 当失败。
     */
    fun insertIgnore(row: RcaDomainEventInsertPo): Int

    /** 一次任务的全部事件，按 `(aggregate_version, id)` 升序——即它们实际发生的顺序。 */
    fun listByTask(@Param("taskId") taskId: Long): List<RcaDomainEventPo>

    /**
     * 尚未投递的事件，按写入顺序取一批。
     *
     * 走 `idx_rca_domain_event_publish (published_at, id)`。条件写成
     * `published_at IS NULL` 而不是「超过某个时间未投递」：本方法服务于
     * 「把还没发出去的补发一遍」，判断「是不是发得太慢/失败了」是另一个人工或监控问题，
     * 混进来会让中继在一个已经卡住的主题上反复取同一批行。
     */
    fun listUnpublished(@Param("limit") limit: Int): List<RcaDomainEventPo>

    /**
     * 按 id 取载荷原文，没有载荷（或 id 不存在）时为 null。
     *
     * 与 [listUnpublished] 分开是有意的：载荷是 JSON 列，长度不可控，而中继每轮只对
     * **真要发出去的那几条**取它。合并成一条语句会让每次轮询都为一批可能不被消费的载荷
     * 付内存代价。
     *
     * 返回的是列的字符形式而不是已解析的结构：本层不替 MQ 订阅方决定用什么反序列化器，
     * 也不把一份解析结果缓存在映射器里。
     */
    fun selectPayloadById(@Param("id") id: Long): String?

    /**
     * 标记已投递。`published_at IS NULL` 是条件的一部分：重复标记不应把首次投递时刻改掉，
     * 那个时刻是对账「积压了多久」的起点。
     *
     * **不接受投递时刻入参**，时间取 SQL 的 NOW(3)：这一列与 `occurred_at` 会被相减来算积压时长，
     * 两者必须来自同一个时钟。详见 XML 里的说明。
     */
    fun markPublished(@Param("id") id: Long): Int

    /**
     * 投递失败时递增尝试次数。**不动 `published_at`**——这一列为空才是「还没送达」。
     *
     * 尝试次数只用于诊断，**不参与任何筛选**：没有「超过 N 次就放弃」的路径。
     * 放弃一条事件不会有任何人收到通知，而它会安静地留在库里永远是未投递状态；
     * 相形之下，反复重试一个坏主题至少会在监控上留下持续增长的计数。
     * 因此本方法没有 `maxAttempts` 入参，也不该有人给它加。
     *
     * @return 1 表示计数已递增，0 表示这一行在这一刻已经投递成功（`published_at` 非空）。
     */
    fun recordPublishFailure(@Param("id") id: Long): Int
}

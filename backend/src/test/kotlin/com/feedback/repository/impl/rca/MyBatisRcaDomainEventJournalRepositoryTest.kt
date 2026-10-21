package com.feedback.repository.impl.rca

import com.feedback.agent.rca.domain.RcaDomainEvent
import com.feedback.agent.rca.domain.RcaEventType
import com.feedback.agent.rca.tools.RcaEventIdFactory
import com.feedback.mapper.rca.RcaDomainEventMapper
import com.feedback.model.po.rca.RcaDomainEventInsertPo
import com.feedback.model.po.rca.RcaDomainEventPo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 领域事件日志的写入侧：确定性标识、撞键不是错误、以及「必须有事务」这条守卫。
 *
 * ## 为什么这一条不能只靠 `rcaEntryTest` 验
 *
 * 真实库那一侧能验的是「写进去了几行」，验不了三件事：
 *
 * 1. **同一个逻辑事件在两次重放里得到同一个 `event_id`**。库里只有一行，
 *    「为什么只有一行」与「是不是第二次被静默丢了」这两种情况的可见结果完全一样。
 * 2. **`TASK_CLAIMED` 不递增版本，所以两次领取能落在同一个版本上**——
 *    这正是 `event_id` 必须把载荷算进摘要的理由，而它只有在构造出「同版本、不同载荷」
 *    这一对输入时才看得出来。
 * 3. **没有事务时必须立刻失败**。真实链路上事务永远在（适配器方法上就有），
 *    所以这条守卫在集成测试里永远不触发，也就永远得不到验证。
 *
 * 本类用假映射器（不连库），因此独占 [TransactionSynchronizationManager] 的线程状态：
 * 每条用例前后都要清干净，否则「有没有事务」会被上一条用例污染。
 */
class MyBatisRcaDomainEventJournalRepositoryTest {

    private val mapper = FakeMapper()
    private val journal = MyBatisRcaDomainEventJournalRepository(mapper)

    @AfterEach
    fun clearTransactionState() {
        TransactionSynchronizationManager.clear()
    }

    /** 打开「当前线程有活动事务」这个状态，等价于调用方方法上有 `@Transactional`。 */
    private fun inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
    }

    private fun event(
        type: RcaEventType = RcaEventType.TASK_STATE_TRANSITIONED,
        version: Long = 1,
        taskId: Long = 100,
        traceId: String? = TRACE_ID,
        payload: Map<String, Any?> = mapOf("nextNode" to "AGENT_INVESTIGATE")
    ) = RcaDomainEvent(
        aggregateType = RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK,
        aggregateId = taskId,
        aggregateVersion = version,
        type = type,
        taskId = taskId,
        episodeId = EPISODE_ID,
        traceId = traceId,
        payload = payload
    )

    // ---------------------------------------------------------------------
    // 事务守卫
    // ---------------------------------------------------------------------

    @Test
    fun `没有活动事务时立即失败，而不是各自提交`() {
        // 这是本类唯一一条「防御性」用例，也是它最该存在的一条：没有事务时，
        // 状态迁移与事件会各自提交，两边都不报错，直到有人拿事件表对账才发现历史错了。
        val failure = assertThrows(IllegalStateException::class.java) { journal.record(event()) }
        assertTrue(
            failure.message.orEmpty().contains("活动事务"),
            "失败原因没有说清是事务缺失，读日志的人只会看到一条无上下文的 IllegalStateException：${failure.message}"
        )
        assertEquals(0, mapper.inserted.size, "失败路径上不应该有任何写入落到映射器")
    }

    // ---------------------------------------------------------------------
    // 确定性标识
    // ---------------------------------------------------------------------

    @Test
    fun `同一个逻辑事件重放两次只落一行`() {
        inTransaction()
        val first = event()
        journal.record(first)
        journal.record(event())

        assertEquals(
            1, mapper.inserted.size,
            "同一件事实被记了两遍：事件的重复与工具调用的重复含义不同——" +
                "工具调用要沿用同一行才能对上恢复判定的账，而事件重复除了让计数变成错的之外没有任何用途"
        )
        assertEquals(1, mapper.rejectedDuplicates, "第二次写入应当由 INSERT IGNORE 静默跳过并返回 0 行")
    }

    @Test
    fun `同一版本上的两次领取得到两个不同的事件标识`() {
        inTransaction()
        // TASK_CLAIMED 不递增 state_version（领取不是状态迁移），所以这两条事件的
        // (聚合, 版本, 类型) 完全相同。载荷里的尝试次数把它们区分开。
        journal.record(
            event(
                type = RcaEventType.TASK_CLAIMED,
                version = 3,
                payload = linkedMapOf("leaseGeneration" to 1L, "attemptCount" to 1)
            )
        )
        journal.record(
            event(
                type = RcaEventType.TASK_CLAIMED,
                version = 3,
                payload = linkedMapOf("leaseGeneration" to 2L, "attemptCount" to 2)
            )
        )

        assertEquals(
            2, mapper.inserted.size,
            "两次领取被折叠成一条事件：等于把「这个任务被领取过几次」从历史里抹掉，" +
                "而它恰恰是崩溃恢复取证的第一步"
        )
        assertEquals(0, mapper.rejectedDuplicates)
    }

    @Test
    fun `载荷相同但事件类型不同时标识不同`() {
        inTransaction()
        journal.record(event(type = RcaEventType.TASK_STATE_TRANSITIONED))
        journal.record(event(type = RcaEventType.TASK_SETTLED))
        assertEquals(2, mapper.inserted.size, "事件类型没有进入摘要：两条不同的事实拿到了同一个标识")
    }

    // ---------------------------------------------------------------------
    // 落库形状
    // ---------------------------------------------------------------------

    @Test
    fun `空载荷写成空对象而不是 NULL`() {
        inTransaction()
        journal.record(event(type = RcaEventType.TASK_RELEASED, payload = emptyMap()))

        val row = mapper.inserted.single()
        // 让订阅方永远拿到一个可解析的对象：写成 NULL 的话，每个消费方都要多写一个
        // 「载荷可能是 null」的分支，而分支越多越容易在某一条上漏判。
        assertEquals("{}", row.payloadJson, "空载荷没有写成 {}")
    }

    @Test
    fun `载荷键排序，同一份载荷的落库文本逐字节稳定`() {
        inTransaction()
        journal.record(event(payload = linkedMapOf("taskStatus" to "RUNNING", "nextNode" to "PERSIST")))
        journal.record(event(payload = linkedMapOf("nextNode" to "PERSIST", "taskStatus" to "RUNNING")))

        // 两次写入的 event_id 相同（摘要侧本来就排序），所以第二行被忽略；
        // 这里断言的是**第一行**的文本形状：键按字典序，便于人工比对。
        val json = mapper.inserted.single().payloadJson
        assertNotNull(json)
        assertTrue(
            json!!.indexOf("\"nextNode\"") < json.indexOf("\"taskStatus\""),
            "载荷键没有排序：同一份载荷会因为调用方的书写顺序而落成不同文本：$json"
        )
    }

    @Test
    fun `事件标识与任务的越权字段无关，只由内容决定`() {
        inTransaction()
        // TraceCorrelation 那一列刻意「有就是有、没有就是没有」。它不进摘要，
        // 因此同一件事实在「本次执行有链路标识」与「没有」两种部署形态下标识一致。
        journal.record(event(traceId = TRACE_ID))
        journal.record(event(traceId = null))
        assertEquals(
            1, mapper.inserted.size,
            "traceId 参与了摘要：同一件事实会因为部署形态不同而得到两个标识"
        )
    }

    @Test
    fun `写入行上不带投递状态`() {
        inTransaction()
        journal.record(event())
        // 字段集合由 RcaDomainEventInsertPo 决定，所以这条断言真正守的是那个 PO：
        // 一旦有人往它上面加 publishedAt / publishAttempts，这里会先红一次，
        // 而不是等到「事件还没发出去就被标成已发」在生产的对账里表现出来。
        assertEquals(
            EXPECTED_FIELDS,
            RcaDomainEventInsertPo::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
                .sorted(),
            "领域侧写入行的字段集合变了：新增的列必须先回答「它属不属于领域事实」"
        )
    }

    @Test
    fun `任务的会话与链路标识原样搬运，不做兜底`() {
        inTransaction()
        journal.record(event(traceId = null))
        val row = mapper.inserted.single()
        assertNull(row.traceId, "traceId 为空时被补了一个值：事后将无法区分「真没有」与「我们填的」")
        assertEquals(EPISODE_ID, row.episodeId)
    }

    @Test
    fun `事件标识是 32 位小写十六进制，与库上的格式约束一致`() {
        inTransaction()
        journal.record(event())
        val eventId = mapper.inserted.single().eventId
        assertTrue(
            Regex("^[0-9a-f]{32}$").matches(eventId),
            "事件标识 $eventId 不满足 chk_rca_domain_event_id 的 ^[0-9a-f]{32}\$"
        )
        assertEquals(eventId, RcaEventIdFactory.of(event()), "标识与工厂的推导不一致")
    }

    @Test
    fun `未知类型的载荷值落成类型名而不是让整次迁移写不进去`() {
        inTransaction()
        journal.record(event(payload = mapOf("weird" to UnknownCarrier())))
        val json = mapper.inserted.single().payloadJson
        assertTrue(
            json.orEmpty().contains("unsupported:"),
            "认不出的类型没有落成类型名：事件缺一条的代价远大于载荷里少一个字段：$json"
        )
    }

    // ---------------------------------------------------------------------

    private class FakeMapper : RcaDomainEventMapper {

        val inserted = mutableListOf<RcaDomainEventInsertPo>()
        var rejectedDuplicates = 0

        private val seenIds = mutableSetOf<String>()

        override fun insertIgnore(row: RcaDomainEventInsertPo): Int {
            if (!seenIds.add(row.eventId)) {
                rejectedDuplicates += 1
                return 0
            }
            inserted += row
            return 1
        }

        override fun listByTask(taskId: Long): List<RcaDomainEventPo> = emptyList()

        override fun listUnpublished(limit: Int): List<RcaDomainEventPo> = emptyList()

        override fun selectPayloadById(id: Long): String? = null

        override fun markPublished(id: Long): Int = 0

        override fun recordPublishFailure(id: Long): Int = 0
    }

    private class UnknownCarrier

    private companion object {
        const val TRACE_ID = "0123456789abcdef0123456789abcdef"
        const val EPISODE_ID = 7L

        /** 写入行上允许出现的字段。见 `写入行上不带投递状态`。 */
        val EXPECTED_FIELDS = listOf(
            "aggregateId", "aggregateType", "aggregateVersion", "episodeId", "eventId",
            "eventType", "payloadJson", "taskId", "traceId"
        )
    }
}

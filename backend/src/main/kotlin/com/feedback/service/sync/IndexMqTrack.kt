package com.feedback.service.sync

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.messaging.ConsumedMqMessage
import com.feedback.messaging.MessagePublisher
import com.feedback.messaging.MqMessage
import com.feedback.messaging.MqMessageHandler
import com.feedback.messaging.MqProperties
import com.feedback.messaging.MqPublishException
import com.feedback.repository.sync.ClaimedIndexTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 派生索引同步的 MQ 轨：中继发任务，消费者执行同步。
 *
 * ## 发出去的是整份认领结果，不只是行号
 *
 * 通知轨只发行号（正文留在库里的理由是隐私，见 `NotificationMqTrack`），
 * 本轨**不能照抄那个做法**：索引任务的写回要靠 `leaseHolder` 与 `leaseGeneration`
 * 这对 fencing 令牌证明「我写的还是我领的那一次」，而令牌是在**认领的那一刻**
 * 由数据库生成的。如果只发号、让消费者重新认领，那就是第二次认领——
 * 令牌变了，中继那次认领随之作废，一条任务会被同步两遍且只有后一遍算数。
 *
 * 所以线格式是完整的 [ClaimedIndexTask]：行号、快照、重试计数、持有者、代次。
 * 消费者**不重新认领**，只执行 [DerivedIndexSyncWorker.process]。
 *
 * 载荷里有什么：`input_snapshot` 是索引发件箱自己写的 JSON
 * （`entityType` / `entityId` / `operation` / `vectorMode`），全是结构化的标识与枚举，
 * 没有正文。索引要取的正文由消费者在[DerivedIndexSyncWorker.upsert]里从库里现读，
 * 并且要过 `OutboundPrivacyGate`——隐私判断因此仍然发生在**库的那一侧**，
 * 不依赖任何从消息里来的内容。这一点与通知轨的取向一致。
 *
 * ## 租约过期就是本轨的重发机制
 *
 * 中继认领后发布失败，或者消费者始终没起，那条任务停在 `PROCESSING`；
 * `lockDue` 的 SQL 里本来就有 `task_status<>'PROCESSING' OR lease_expires_at<NOW(3)`，
 * 它原本用于修复「同步进程崩溃」，恰好也是这里的修复路径（2 分钟后重新可领取）。
 * **不新增任何补偿机制**——两条触发条件不完全一致的修复路径会造出「谁都不认领」的死角。
 *
 * ## 重复执行是安全的，但要理解它安全在哪
 *
 * 重发、broker 重投、租约过期后的再认领都会让同一条任务被处理两次以上。
 * 安全来自两处：外部写入按 `sourceId` 幂等（ES 是 `id` 覆盖、向量库是 upsert），
 * 以及写回带 fencing 令牌——晚到的那次如果令牌已经过期会被拒绝成
 * `IndexTaskWriteOutcome.LEASE_LOST`，那是**正常路径不是故障**，`process` 自己会处理。
 */
@Component
class IndexMqTrack(
    private val properties: MqProperties,
    private val publisher: MessagePublisher,
    private val worker: DerivedIndexSyncWorker
) : MqMessageHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 线格式由本类自己拼：不用容器里的 ObjectMapper 单例，与
     * `RcaDomainEventRelay`、`NotificationMqTrack` 同一个理由——这两端产出的字节
     * 会离开本进程，形状不该随别处对共享实例的配置改动而变。
     */
    private val json = ObjectMapper()

    override val topic: String get() = properties.indexSyncTopic

    /**
     * 跑一轮中继：先播种发件箱，再「认领一条 → 发一条」，直到没有可认领的任务
     * 或达到批量上限。
     *
     * [DerivedIndexSyncWorker.seedOutbox] 必须在每轮开头跑：它是把库里
     * 「索引状态不是 SYNCED」的反馈与簇转成发件箱任务的唯一入口，
     * 跳过它等于只发老任务、新变更永远进不了队列，而队列不会变空、也不会有任何报错。
     */
    fun relayPending() {
        if (!properties.enabled) return
        val errors = properties.readinessErrors()
        if (errors.isNotEmpty()) {
            log.error("索引 MQ 中继配置未就绪，本次调度跳过：errors={}", errors)
            return
        }

        worker.seedOutbox()

        var relayed = 0
        var stopped = false
        while (relayed < properties.relayBatchSize) {
            val task = worker.claimOne() ?: break
            try {
                publisher.publish(buildMessage(task))
                relayed++
            } catch (error: MqPublishException) {
                stopped = true
                log.error(
                    "索引中继投递失败，本轮停止：taskId={} holder={} generation={} 本轮已发出 {} 条。" +
                        "这条任务停在 PROCESSING，租约 2 分钟后过期并重新可领取。",
                    task.id, task.leaseHolder, task.leaseGeneration, relayed, error
                )
                break
            }
        }

        if (relayed > 0 || stopped) {
            log.info("索引中继完成：topic={} 已发任务={} 因失败停止={}", properties.indexSyncTopic, relayed, stopped)
        }
    }

    /**
     * 消费一条索引任务消息：按原样重建认领结果并执行同步。
     *
     * 体解析不出来就抛，与 `NotificationMqTrack` 同一条纪律：重投到上限后进
     * `%DLQ%<组名>`，那里至少是一个可以查的位置，而静默确认掉等于让它消失。
     */
    override fun handle(message: ConsumedMqMessage) {
        val task = parseTask(message)
        worker.process(task)
        log.debug("索引任务已处理：taskId={} holder={} generation={} msgId={}",
            task.id, task.leaseHolder, task.leaseGeneration, message.msgId)
    }

    /**
     * 认领结果 → 线格式消息。
     *
     * 键取任务行号：同一个任务的所有消息落在同一个队列上。顺序在这里不重要
     * （每个任务只发一次），但同键同队列让 broker 侧的排查能直接按行号捞。
     *
     * 标签区分实体类型（`FEEDBACK` / `CLUSTER`）——它来自发件箱自己写的快照，
     * 不含正文，与通知轨拒绝把渠道名放进标签的理由（渠道对应收件人配置）不同：
     * 实体类型只是一个枚举，没有指向任何人的信息。解析不出来时用固定标签，
     * 不让一条标签异常的消息卡住整个中继。
     */
    private fun buildMessage(task: ClaimedIndexTask): MqMessage {
        val body = json.writeValueAsString(
            linkedMapOf(
                "id" to task.id,
                // 快照原样作为**字符串**放进消息体（会被转义一次），不解析成嵌套对象：
                // 它是本平台自己写的内部快照，只有 process 会读它，
                // 让订阅方看见结构没有任何好处，反而会把内部形状固化成对外契约。
                "inputSnapshot" to task.inputSnapshot,
                "retryCount" to task.retryCount,
                "leaseHolder" to task.leaseHolder,
                "leaseGeneration" to task.leaseGeneration
            )
        )
        return MqMessage(
            topic = properties.indexSyncTopic,
            key = task.id.toString(),
            body = body,
            tag = tagOf(task),
            headers = mapOf("outboxId" to task.id.toString())
        )
    }

    /** 从快照里取实体类型做标签。取不到就退回固定标签，不抛——标签不参与任何判定。 */
    private fun tagOf(task: ClaimedIndexTask): String =
        runCatching { json.readTree(task.inputSnapshot)["entityType"]?.asText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TAG

    /**
     * 从消息体里重建认领结果。
     *
     * 五个字段**一个都不许缺**，也不做任何兜底：这条消息的全部意义就是
     * 「替中继把那次认领原样带到消费者这一侧」，缺一个字段就只能重新认领，
     * 而重新认领会换掉 fencing 令牌（见类文档）。缺字段按不可处理处理、抛出去进死信，
     * 比编一个默认值然后把一次注定被 fencing 拒绝的写回当成成功要好得多。
     */
    private fun parseTask(message: ConsumedMqMessage): ClaimedIndexTask {
        val node = runCatching { json.readTree(message.body) }.getOrNull()
            ?: throw IllegalArgumentException(
                "索引任务消息体不是合法 JSON：topic=${message.topic} msgId=${message.msgId}"
            )
        fun text(name: String): String = node[name]?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException(
                "索引任务消息体缺少文本字段 $name：topic=${message.topic} msgId=${message.msgId}"
            )

        fun number(name: String): Long = node[name]?.takeIf { it.isNumber }?.asLong()
            ?: throw IllegalArgumentException(
                "索引任务消息体缺少数字字段 $name：topic=${message.topic} msgId=${message.msgId}"
            )

        val id = number("id")
        if (id <= 0) {
            throw IllegalArgumentException(
                "索引任务消息体的 id 必须为正数，当前为 $id：topic=${message.topic} msgId=${message.msgId}"
            )
        }
        return ClaimedIndexTask(
            id = id,
            inputSnapshot = text("inputSnapshot"),
            retryCount = number("retryCount").toInt(),
            leaseHolder = text("leaseHolder"),
            leaseGeneration = number("leaseGeneration")
        )
    }

    private companion object {
        /** 快照里没有实体类型（不该发生）时用的标签。固定值，不参与任何判定。 */
        const val DEFAULT_TAG: String = "INDEX"
    }
}

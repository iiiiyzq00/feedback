package com.feedback.agent.rca.intake

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.episode.EpisodeIntakeService
import com.feedback.config.RcaProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * 接入侧调度：把 `t_feedback_source` 上的新来源送进 [EpisodeIntakeService]。
 *
 * ## 这是「原始信号 → 可执行任务」的生产入口
 *
 * 在本类之前，`EpisodeIntakeService.accept` 的生产调用方是 **0 个**：
 * 它只被离线会话化套件 `replay/EpisodeSessionizationMain.kt` 调用过。
 * 本类接的就是那一段——**断点不在编排里，编排早就写完了，缺的只是触发**。
 *
 * ## 开关在这一层，不在 handler
 *
 * 与 `service/ai/AiTaskPipeline.kt` 的 `AiWorkerScheduler` 是同一个范式：
 * handler 是薄适配器，**开关在 Scheduler**。这样「调度平台误触发」与「部署未启用」
 * 是两个独立层，任一层关闭都不会跑。
 *
 * ## 幂等不在这里实现
 *
 * 本类不判重。重复投递由接入自己的唯一键挡下：`uk_episode_member_revision` 挡重复成员、
 * `uk_rca_agent_task_key` 挡重复任务，重投走 `joinExisting` → `ALREADY_PRESENT`
 * （`duplicateDelivery=true`、影响面计数不动、`taskCreated=false`）。
 * 在这里再写一套判重就会出现两套口径，而其中一套必然先失效。
 *
 * ## 游标的诚实说明
 *
 * 游标是**进程内的**（[cursor]），不是数据库里的检查点。含义是：
 * 同一次运行内逐批推进、不重复扫；**进程重启后从 0 重扫**。
 * 重扫是安全的（幂等在上游保证），代价是一次多余的只读查询。
 * 反过来，把游标做成「重启后从上次位置继续」会引入一个更坏的失败模式——
 * 检查点写失败或写早了一个批次，被跳过的那批来源**永远不会**再被扫到，
 * 而「少了一条反馈」不会有任何报错。
 */
@Component
class RcaIntakeScheduler(
    private val candidates: RcaIntakeCandidatePort,
    private val assembler: RcaIntakeSignalAssembler,
    private val intake: EpisodeIntakeService,
    private val properties: RcaProperties,
    private val clock: RcaClock
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 已处理到的来源 ID。只在一次运行内单调推进，见类注释的说明。 */
    private val cursor = AtomicLong(0)

    /**
     * 跑一轮接入。
     *
     * 一轮最多扫 [RcaProperties.intakeMaxBatchesPerRun] 批，每批 [RcaProperties.intakeBatchSize] 条；
     * 取不满一批、或一批里一条都没组装成功，都立即停下——
     * 后者是防止「有一行永远组装不出来」时调度入口空转成无界循环。
     */
    fun runIntake() {
        if (!properties.intakeEnabled) return
        val errors = properties.readinessErrors()
        if (errors.isNotEmpty()) {
            log.error("RCA 接入配置未就绪，本次调度跳过：errors={}", errors)
            return
        }

        var accepted = 0
        var rejected = 0
        var duplicated = 0
        var batches = 0
        val arrivalTime = clock.now()

        while (batches < properties.intakeMaxBatchesPerRun) {
            val rows = candidates.findAfter(cursor.get(), properties.intakeDataEnvironment, properties.intakeBatchSize)
            if (rows.isEmpty()) break
            batches++
            var progressed = false
            for (row in rows) {
                val reason = assembler.rejectReason(row)
                if (reason != null) {
                    rejected++
                    log.warn("RCA 接入跳过来源：sourceId={} reason={}", row.sourceId, reason)
                    // 跳过的行也要推进游标，否则它会一直出现在下一批的开头，卡住整个游标。
                    // 跳过的语义是「这条不接入」，不是「下次再试」——事件时间缺失这类问题
                    // 不会因为多试几次而自愈，重试只会让它反复占住批次头部。
                    cursor.set(row.sourceId)
                    progressed = true
                    continue
                }
                val signal = requireNotNull(assembler.assemble(row)) {
                    // rejectReason 已判过，这里为 null 说明两个方法的口径分叉了。
                    "组装结果与拒绝原因不一致：sourceId=${row.sourceId}"
                }
                val outcome = intake.accept(signal, arrivalTime)
                if (outcome.duplicateDelivery) duplicated++ else accepted++
                cursor.set(row.sourceId)
                progressed = true
            }
            if (!progressed || rows.size < properties.intakeBatchSize) break
        }

        log.info(
            "RCA 接入完成：environment={} batches={} accepted={} duplicate={} rejected={} cursor={}",
            properties.intakeDataEnvironment, batches, accepted, duplicated, rejected, cursor.get()
        )
    }

    /** 当前游标位置。供运维与集成测试读取，不参与判定。 */
    fun currentCursor(): Long = cursor.get()
}

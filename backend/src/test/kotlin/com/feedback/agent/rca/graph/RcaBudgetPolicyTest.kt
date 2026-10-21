package com.feedback.agent.rca.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * 生产预算策略与部署配置之间的那一条线。
 *
 * ## 为什么值得一个用例
 *
 * [RcaBudgetPolicy.production] 是一行 `copy`，看起来不可能写错。它真的会错的方式只有两种，
 * 而两种都不会有任何报错：
 *
 * 1. 不取参数，直接返回 `DEFAULT` —— 于是 `app.rca.task-timeout-minutes` 配了也白配，
 *    而库上的 `deadline_at`（由 `claim` 用同一个配置写下）照样会按配置走。
 *    现象是「库里 11 分钟就超期、图里 15 分钟才降级」，两个数字都对，只是不是一回事；
 * 2. 顺手改别的字段 —— 于是步数/费用与库上 `budget_max_*` 三列分叉，
 *    下一次 `writeBack` 会以 CHECK 冲突的形式失败，看起来像并发写坏了数据。
 *
 * 这两个断言各封一条。它们不断言「默认值是多少才合理」——那是产品决定，
 * 而 [RcaBudgetPolicy.DEFAULT] 的 15 分钟与生产默认的 30 分钟**刻意不同**
 * （离线套件用固定时钟，那个上限永远不会触发）。
 */
class RcaBudgetPolicyTest {

    @Test
    fun `生产策略取配置给出的截止时间，不是那个离线的默认值`() {
        val production = RcaBudgetPolicy.production(deadlineMinutes = 11)

        assertEquals(
            Duration.ofMinutes(11), production.deadline,
            "配置值没有进到生产策略里：库上的 deadline_at 会按配置走，而图内按默认值降级"
        )
        assertNotEquals(
            RcaBudgetPolicy.DEFAULT.deadline, production.deadline,
            "生产策略与离线默认值相同：这个用例因此验不出「配置真的被读到了」"
        )
    }

    @Test
    fun `生产策略只在截止时间上与默认值不同`() {
        val production = RcaBudgetPolicy.production(deadlineMinutes = 30)

        assertEquals(
            RcaBudgetPolicy.DEFAULT.copy(deadline = Duration.ofMinutes(30)), production,
            "工厂顺手改了别的字段：步数/费用与库上 budget_max_* 三列分叉后，" +
                "writeBack 会以 CHECK 冲突的形式失败，而那看起来像并发写坏了数据"
        )
    }

    @Test
    fun `初始预算的截止时间从进入时刻起算，不用故障发生时间`() {
        // 用故障时间起算会让历史问题上来的任务一创建就已超期，从而永远走不到取证。
        // 这里用一个星期前的故障时间：只要截止时间仍在未来，就说明起点不是它。
        val now = LocalDateTime.of(2026, 9, 29, 10, 0)
        val faultAt = now.minusDays(7)

        val budget = RcaBudgetPolicy.production(deadlineMinutes = 30).initialBudget(now, faultAt)

        assertEquals(now.plusMinutes(30), budget.deadlineAt)
        assertFalse(budget.deadlineExceeded, "任务一创建就已超期，永远走不到取证")
    }
}

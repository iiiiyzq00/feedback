package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * 历史案例行的数据库形态，对应 `t_root_cause_analysis` 上**已人工确认**的根因结论。
 *
 * ## 为什么是 `t_root_cause_analysis` 而不是 `t_ai_knowledge`
 *
 * 交付计划原本写的是从知识库取。实测两张表的列后改成了这里，理由是端口契约里
 * 有两个字段在知识库上**没有对应列**，只能编：
 *
 * - [HistoricalCaseRow.incidentId]：`t_ai_knowledge` 没有 incident 列，
 *   它的 `source_rca_id` 指向的是 RCA 任务而不是 Incident。拿它填 incidentId
 *   会得到一个「看起来是 Incident 主键、实际是任务主键」的值，
 *   而下游要按这个 ID 去并 Incident，一旦对不上就是一条错的影响面关联。
 *   `t_root_cause_analysis.incident_id` 是真的 Incident 外键。
 * - [HistoricalCaseRow.confirmedAt]：`t_root_cause_analysis.confirmed_at` 是人工确认时刻；
 *   知识库的 `reviewed_at` 是内容复核时刻，两者不是一件事。
 *
 * `verificationPassed` 同样是**派生**而不是编的：见下方 [verificationPassed]。
 *
 * ## 仍然缺失的字段
 *
 * [HistoricalCaseRow.fixVersion] 在这张表上**没有对应列**，取 `null`。
 * 不构造一个「修复版本」出来：那会让「这条案例是靠哪个版本修好的」这个问题
 * 得到一个凭空生成的答案，而手册里修复版本正是最常被直接照抄的字段。
 */
data class RcaHistoricalCasePo(
    /** `t_root_cause_analysis.incident_id`；语句已滤掉 NULL，故此处非空。 */
    var incidentId: Long = 0,

    /** `t_root_cause_analysis.rca_title`。 */
    var title: String = "",

    /** `t_root_cause_analysis.root_cause`，已确认的根因正文。 */
    var confirmedRootCause: String = "",

    /**
     * 由 `validation_status = 'PASSED'` 派生。
     *
     * 该列的取值域是 `chk_rca_validation_status` 的 `PASSED`/`FAILED`/`PARTIAL`，
     * 取 `PASSED` 才说明这条结论经过了发布后验证。`PARTIAL` 不算通过——
     * 端口的 `hasVerifiedCase` 要求**全部**行都通过验证，把部分通过算成通过
     * 会让「存在可用先验」这句话失去依据。
     */
    var verificationPassed: Boolean = false,

    /** `t_root_cause_analysis.confirmed_at`，人工确认时刻。 */
    var confirmedAt: LocalDateTime? = null
)

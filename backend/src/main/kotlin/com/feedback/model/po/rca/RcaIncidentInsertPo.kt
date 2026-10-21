package com.feedback.model.po.rca

/**
 * Incident 写入行，对应既有表 `t_incident`。
 *
 * 这张表没有承载「稳定根因签名」的列，也没有可用于去重的唯一键（只有 `uk_public_id`），
 * 所以「新反馈并入同一 Incident」只能按 `incident_title` 判存在：
 * 标题由稳定签名拼出，因此同一条根因的后续归因会命中同一行，而不是各开一个 Incident。
 * 这也是 `RootCauseSignature` 坚持只放「换了故障实例也不会变」的字段的直接原因——
 * 影响面与时间一旦进签名，标题就会变，影响面统计随即分裂。
 *
 * `incident_severity` 是 NOT NULL 且无默认值，必须由写入方给出。
 * RCA 的证据模型里**没有**定级依据（严重度取决于业务影响面与值班判断，不是证据强度），
 * 因此一律开在最低档 `P3` 并交由人工重新定级——这里刻意不根据置信度去猜一个更高的档位：
 * 把「模型觉得挺像」翻译成 `P0` 是这套系统最不该做的事。
 */
data class RcaIncidentInsertPo(
    /** 写入后由 `useGeneratedKeys` 回填。 */
    var id: Long = 0,
    val publicId: String,
    val incidentTitle: String,
    val incidentSeverity: String
)

package com.feedback.service.station

/** Station Online 安全副本恢复契约；实现负责有界读取且不扩大事务。 */
interface StationOnlineMaskingRecoveryService {
    /** 恢复最多 limit 条待审核实时反馈，limit 必须位于 1..200。 */
    fun recover(limit: Int = 100): Int
}

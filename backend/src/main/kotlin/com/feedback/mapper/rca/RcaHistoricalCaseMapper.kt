package com.feedback.mapper.rca

import com.feedback.model.po.rca.RcaHistoricalCasePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 历史案例召回语句。只读。
 *
 * 过滤条件刻意保守：只召回 `rca_status='CONFIRMED'`、`confirmed_at` 非空、
 * 且挂在 Incident 上的结论。**漏召回是可以接受的，错召回不行**——
 * 历史案例会被模型当成先验直接照着推，一条「看起来已确认、实际没确认」的案例
 * 比没有案例更糟：它会把模型推向一个被人否定过的结论，而且账本上看起来有出处。
 */
@Mapper
interface RcaHistoricalCaseMapper {

    /**
     * 按签名提示召回已确认案例。
     *
     * @param signatureHint 召回提示，例如「组件 + 故障类型」。按 `LIKE` 做**词面**匹配，
     *   不是语义召回：它可能漏掉说法不同的同因案例，但不会把无关案例拉进来。
     * @param component 组件名。本平台没有「组件」列，映射到最接近的 `feature_module`；
     *   传空表示不按组件过滤。该映射是**窄化**方向的：映射不准只会少召回，不会多召回。
     * @param limit 上限，由调用方按端口契约的 `topK` 传入并做过钳制。
     */
    fun findConfirmedCases(
        @Param("signatureHint") signatureHint: String,
        @Param("component") component: String?,
        @Param("limit") limit: Int
    ): List<RcaHistoricalCasePo>
}

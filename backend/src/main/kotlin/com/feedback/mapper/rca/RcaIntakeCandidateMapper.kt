package com.feedback.mapper.rca

import com.feedback.model.po.rca.RcaIntakeCandidatePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * RCA 接入侧的候选来源读取。
 *
 * **只有读方法**：接入本身的写入（问题对象、成员、工作项、任务）全部由
 * `EpisodeIntakeService` 与 `EpisodeSessionAdapter` 负责，这里多开一个写口子
 * 就会出现「两处都能建问题对象」的分叉，而幂等键只有一套。
 */
@Mapper interface RcaIntakeCandidateMapper {

    /**
     * 按来源 ID 游标取一批可接入的反馈记录。
     *
     * @param dataEnvironment 数据环境。作为查询条件而不是事后过滤，见 `RcaProperties` 的说明。
     * @param limit 单批上限。
     */
    fun findIntakeCandidatesAfter(
        @Param("afterSourceId") afterSourceId: Long,
        @Param("dataEnvironment") dataEnvironment: String,
        @Param("limit") limit: Int
    ): List<RcaIntakeCandidatePo>
}

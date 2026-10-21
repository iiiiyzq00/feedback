package com.feedback.mapper.problem.analysis

import com.feedback.model.po.problem.analysis.ClusterFeedbackProjectionPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 安全列表投影单表 Mapper；generationId 防止跨代正文与成员事实混用。 */
@Mapper
interface ClusterFeedbackProjectionMapper {
    fun findByIds(@Param("generationId") generationId: Long, @Param("sourceIds") sourceIds: List<Long>): List<ClusterFeedbackProjectionPo>
}

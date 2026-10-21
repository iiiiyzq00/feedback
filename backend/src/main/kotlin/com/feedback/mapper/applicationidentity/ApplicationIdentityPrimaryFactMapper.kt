package com.feedback.mapper.applicationidentity

import com.feedback.model.po.applicationidentity.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** t_feedback_primary_problem_fact 单表 Mapper；仅承载影子事实。 */
@Mapper
interface ApplicationIdentityPrimaryFactMapper {
    /** 按 source 唯一键批量 UPSERT 强类型边界转换后的事实 PO。 */
    fun upsertAll(@Param("rows") rows: List<PrimaryProblemFactPo>): Int
    /** 按有界 source ID 集合批量读取事实，禁止 N+1。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<PrimaryProblemFactPo>
    /** 按解析状态聚合诊断计数。 */
    fun countByResolutionStatus(): List<ApplicationResolutionCountPo>
}

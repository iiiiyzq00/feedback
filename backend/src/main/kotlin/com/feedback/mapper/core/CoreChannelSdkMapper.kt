package com.feedback.mapper.core

import com.feedback.model.po.core.*
import com.feedback.model.sdk.ChannelSdk
import com.feedback.model.feedback.FeedbackIdentity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** CoreChannelSdkMapper 参数化 Mapper；仅供 Repository 实现调用并保持单表数据访问职责。 */
@Mapper
interface CoreChannelSdkMapper {
/** 按主键参数化查询记录；未命中语义由上层 Repository 统一决定。 */
    fun findById(@Param("sourceId") sourceId: Long): ChannelSdk?
/** 按去重且有界的主键集合批量查询，禁止由调用方逐条形成 N+1。 */
    fun findByIds(@Param("sourceIds") sourceIds: Collection<Long>): List<ChannelSdk>
/** 按稳定唯一键执行参数化幂等写入，Repository 负责解释并校验影响行数。 */
    fun upsert(@Param("row") row: ChannelSdk): Int
}

package com.feedback.repository.feedback.listprojection

import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.mapper.feedback.listprojection.FeedbackListGenerationReadMapper
import org.springframework.stereotype.Repository

/** generation 读取的共享单表边界，供反馈列表与问题分析固定同一 ACTIVE 快照。 */
@Repository
class FeedbackListProjectionReadRepository(private val mapper: FeedbackListGenerationReadMapper) {
    fun activeGenerations(): List<FeedbackListProjectionGenerationPo> = mapper.findActive()
}

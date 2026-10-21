package com.feedback.mapper.feedback.detail

import com.feedback.model.po.feedback.detail.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper interface FeedbackDetailAuditMapper {
    fun findFieldHistory(@Param("targetId") targetId: String): List<FeedbackFieldHistoryPo>
    fun insertAccessAudit(@Param("userId") userId: Long, @Param("operation") operation: String, @Param("targetType") targetType: String, @Param("targetId") targetId: String, @Param("detailJson") detailJson: String): Int
}
/** 当前 MAIN 归簇摘要和完整单反馈归簇时间轴的单表读取边界。 */

